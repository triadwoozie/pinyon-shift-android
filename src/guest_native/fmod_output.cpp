#include <rex/cs_scheduler.h>
// FMOD's output played from its ring on the host (NEAR_NATIVE_RESEARCH NN-4.4).
//
// FMOD Ex's Xenon output creates the title's one XAudio2 engine and one float
// source voice, submits one looping buffer (its output ring) and then only
// polls the voice's GetState to learn how far playback has got. Every 5.33 ms
// the host Audio Worker runs the guest render-driver callback, which wakes the
// guest XAudio2 pass; in play that pass walks about 41 voices (XHV2's idle
// voice graph) to mix FMOD's one voice into the master. With
// pinyon_shift_native_audio_output on, the worker instead copies the next 256
// frames from FMOD's ring into the render-driver frame itself
// (rex::audio::HostFrameSource), and GetState answers FMOD's polls for its
// voice with the host's count of frames played. The guest graph then simply
// does not run: FMOD's guest voice stays started and frozen where it was.
//
// The guest path comes back on its own whenever something else needs the
// pass: a source voice other than FMOD's is started (a movie with sound, a
// remote talker), or the XAudio2 API was called less than
// pinyon_shift_xaudio2_idle_ms ago (voice creation, destruction, flushes and
// matrix changes around movie open and close). The hand-back waits until the
// host has played a whole number of ring lengths since it took over, so the
// frozen guest voice sits at the same ring offset as the host cursor; from
// then on GetState reports the guest's count plus the accumulated host
// offset. Neither switch touches the guest voice (no Stop, Start or
// resubmission), so the setting can be toggled live for an in-run A/B.
//
// pinyon_shift_native_audio_shadow keeps the guest path and, for every block,
// also builds the host block (and its neighbours a block earlier and later)
// and compares it with the frame the guest submitted; each second it logs the
// largest absolute difference, the SNR, the best-matching lag and the
// counter phase (how far the guest's SamplesPlayed moved from 256 a block).
//
// Hooks (Replacements in the style of fmod_stereo.cpp; the guest code is
// checked before use and v4 addresses come from tools/map-fh1-v4-addresses.py):
// observe-only on CreateSourceVoice, CreateSubmixVoice, SubmitSourceBuffer,
// Start, Stop, DestroyVoice, FlushSourceBuffers, SetOutputMatrix and
// SetEffectChain; GetState answers for FMOD's voice. No FMOD state layout is
// read: only the public XAUDIO2_BUFFER, XAUDIO2_VOICE_STATE and
// WAVEFORMATEXTENSIBLE structs (big-endian, 32-bit pointers) and the
// arguments FMOD passes. Interface slots: site/findings/audio.toml, "XAudio2
// voices in a race".

#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <numeric>
#include <string>
#include <string_view>

#include <fmt/format.h>
#include <rex/audio/host_frame_source.h>
#include <rex/cvar.h>
#include <rex/session_probe.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>
#include <rex/system/xmemory.h>

#include "fh1_guest_address.h"
#include "guest_native/guest_native.h"
#include "pinyon_shift_diagnostics.h"

REXCVAR_DEFINE_BOOL(pinyon_shift_native_audio_output, true, "Pinyon Shift",
                    "Play FMOD's output ring on the host instead of running the game's "
                    "XAudio2 pass, falling back to the pass while a movie or another voice "
                    "plays (NN-4.4)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_BOOL(pinyon_shift_native_audio_shadow, false, "Pinyon Shift",
                    "Diagnostics: also build each audio block on the host and log how it "
                    "compares with the game's XAudio2 pass every second (NN-4.4)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_UINT32(pinyon_shift_xaudio2_idle_ms, 1000, "Pinyon Shift",
                      "With native audio output on, how long the game's XAudio2 pass keeps "
                      "running after the game last called the XAudio2 API (NN-4.4)")
    .range(0, 60000)
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

REXCVAR_DECLARE(bool, pinyon_shift_guest_native);

// The original GetState, called from the replacement.
extern "C" REX_FUNC(PINYON_GUEST_NATIVE_JOIN(__imp__sub_, PINYON_GUEST_NATIVE_PICK(830B0A38,
                                                                                  83171D10)));

// Verification uses the scheduler's clock for host features that affect guest state.
static inline __attribute__((always_inline)) std::chrono::steady_clock::time_point CsSemanticNow() {
  if (rex::cs_scheduler::InActor())
    return std::chrono::steady_clock::time_point(std::chrono::nanoseconds(rex::cs_scheduler::Now()));
  return std::chrono::steady_clock::now();
}

namespace pinyon_shift::guest_native {
namespace {

constexpr bool kV4 = pinyon_shift::fh1::kTitleUpdateV4;

// Return addresses of FMOD's output init calls to CreateSourceVoice and
// SubmitSourceBuffer (the only calls from FMOD; base sub_82FFFC38, v4
// sub_830C0DC0), and the source voice's interface table.
constexpr uint32_t kFmodCreateReturn = kV4 ? 0x830C10C8u : 0x82FFFF40u;
constexpr uint32_t kFmodSubmitReturn = kV4 ? 0x830C1290u : 0x83000108u;
constexpr uint32_t kSourceVoiceTable = kV4 ? 0x822C7628u : 0x822B4508u;

// Public XAudio2 values.
constexpr uint32_t kVoiceNoSamplesPlayed = 0x100;  // XAUDIO2_VOICE_NOSAMPLESPLAYED
constexpr uint32_t kLoopInfinite = 255;            // XAUDIO2_LOOP_INFINITE
constexpr uint16_t kFormatIeeeFloat = 3;           // WAVE_FORMAT_IEEE_FLOAT
constexpr uint16_t kFormatExtensible = 0xFFFE;     // WAVE_FORMAT_EXTENSIBLE
constexpr uint32_t kOutputRate = 48000;

constexpr uint32_t kBlock = rex::audio::kRenderFrameChannelSamples;
constexpr uint32_t kFrameWords = rex::audio::kRenderFrameWords;
constexpr size_t kMaxStartedVoices = 64;
constexpr uint32_t kMaxModeEvents = 256;
// Longest hand-back accepted, in blocks (about 0.34 s).
constexpr uint64_t kMaxAlignBlocks = 64;

using Clock = std::chrono::steady_clock;

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(CsSemanticNow().time_since_epoch())
      .count();
}

uint8_t* Host(uint8_t* base, uint32_t address) {
  return base + address + rex::memory::detail::PhysicalHostOffset(address);
}
uint16_t Load16(uint8_t* base, uint32_t address) {
  uint16_t value;
  std::memcpy(&value, Host(base, address), sizeof(value));
  return std::byteswap(value);
}
uint32_t Load32(uint8_t* base, uint32_t address) {
  uint32_t value;
  std::memcpy(&value, Host(base, address), sizeof(value));
  return std::byteswap(value);
}
uint64_t Load64(uint8_t* base, uint32_t address) {
  uint64_t value;
  std::memcpy(&value, Host(base, address), sizeof(value));
  return std::byteswap(value);
}
void Store32(uint8_t* base, uint32_t address, uint32_t value) {
  value = std::byteswap(value);
  std::memcpy(Host(base, address), &value, sizeof(value));
}
void Store64(uint8_t* base, uint32_t address, uint64_t value) {
  value = std::byteswap(value);
  std::memcpy(Host(base, address), &value, sizeof(value));
}

// What FMOD's output init passed to XAudio2, as observed.
struct FmodOutput {
  // Where CreateSourceVoice writes FMOD's voice (its ppSourceVoice).
  uint32_t voice_slot = 0;
  bool format_seen = false;
  uint16_t format_tag = 0;
  uint16_t channels = 0;
  uint32_t rate = 0;
  uint16_t block_align = 0;
  uint16_t bits = 0;
  uint32_t subformat = 0;  // first word of the extensible sub-format GUID
  // From the looping SubmitSourceBuffer.
  uint32_t voice = 0;
  uint32_t ring = 0;
  uint32_t ring_bytes = 0;
  uint32_t ring_frames = 0;
  bool usable = false;
  std::string reason;
};

// A GetState result for FMOD's voice taken while no guest block ran, with the
// number of guest blocks completed at the time.
struct Observation {
  uint64_t samples = 0;
  uint64_t blocks = 0;
  bool valid = false;
};

struct State {
  std::atomic<uint8_t*> base{nullptr};

  std::mutex fmod_mutex;
  FmodOutput fmod;  // under fmod_mutex
  std::atomic<uint32_t> fmod_voice{0};
  std::atomic<uint32_t> fmod_generation{0};
  std::atomic<bool> fmod_started{false};

  // Steady-clock time of the last XAudio2 API call (FMOD's polls excluded).
  std::atomic<int64_t> last_api_ns{0};

  std::mutex voices_mutex;
  std::array<uint32_t, kMaxStartedVoices> started_voices{};  // under voices_mutex
  size_t started_count = 0;                                  // under voices_mutex
  std::atomic<uint32_t> others_started{0};

  // Guest blocks: odd while the guest callback (and so the pass) runs.
  std::atomic<uint64_t> epoch{0};

  std::mutex observation_mutex;
  Observation observation;  // under observation_mutex
  std::atomic<uint32_t> last_context{0};
  std::atomic<uint32_t> last_queued{1};

  // The switch. While native_active is set, GetState reports host_counter;
  // otherwise the guest's own count plus offset. The two agree at every switch.
  std::mutex switch_mutex;
  std::atomic<bool> native_active{false};
  std::atomic<uint64_t> host_counter{0};
  std::atomic<uint64_t> offset{0};
  // The guest voice's count while it is frozen (native).
  std::atomic<uint64_t> frozen_guest{0};
  // FMOD's ring length in frames; every offset is a multiple of it.
  std::atomic<uint64_t> ring_frames{1};
  // Bumped by every FMOD poll: FMOD writes its ring and then polls, so a
  // worker that acquires this sees the ring contents written before the poll.
  std::atomic<uint64_t> poll_seq{0};

  // Shadow diagnostics written by the GetState hook.
  std::atomic<uint64_t> phase_max_abs{0};
  std::atomic<uint64_t> phase_errors{0};
  std::atomic<uint64_t> observations{0};
  std::atomic<uint64_t> moved_while_native{0};
};

State& S() {
  static State* state = new State;  // never destroyed: guest threads outlive statics
  return *state;
}

void TouchApi() { S().last_api_ns.store(NowNs(), std::memory_order_relaxed); }

void CaptureBase(uint8_t* base) {
  if (S().base.load(std::memory_order_relaxed) != base) {
    S().base.store(base, std::memory_order_release);
  }
}

// Hands FMOD back to the guest at once, without waiting for ring alignment
// (FMOD stopped, flushed or replaced its voice). The offset must stay a whole
// number of rings, or FMOD would write where the guest voice does not read,
// so the count FMOD sees jumps forward to the next ring boundary (by less
// than one ring) rather than back.
void ForceGuestNow() {
  State& s = S();
  std::lock_guard lock(s.switch_mutex);
  if (!s.native_active.load(std::memory_order_acquire)) return;
  const uint64_t ring = std::max<uint64_t>(s.ring_frames.load(std::memory_order_relaxed), 1);
  const uint64_t ahead = s.host_counter.load(std::memory_order_acquire) -
                         s.frozen_guest.load(std::memory_order_relaxed);
  s.offset.store((ahead + ring - 1) / ring * ring, std::memory_order_relaxed);
  s.native_active.store(false, std::memory_order_release);
}

void InvalidateFmod(std::string_view reason, bool voice_gone) {
  State& s = S();
  ForceGuestNow();
  {
    std::lock_guard lock(s.fmod_mutex);
    s.fmod.usable = false;
    s.fmod.reason = reason;
    if (voice_gone) {
      s.fmod.voice = 0;
      s.fmod_voice.store(0, std::memory_order_release);
      s.offset.store(0, std::memory_order_relaxed);
      s.fmod_started.store(false, std::memory_order_relaxed);
    }
  }
  s.fmod_generation.fetch_add(1, std::memory_order_acq_rel);
}

void ResetObservations() {
  std::lock_guard lock(S().observation_mutex);
  S().observation = {};
}

bool IsSourceVoice(uint8_t* base, uint32_t voice) {
  return voice && Load32(base, voice) == kSourceVoiceTable;
}

void AddStarted(uint32_t voice) {
  State& s = S();
  std::lock_guard lock(s.voices_mutex);
  for (size_t i = 0; i < s.started_count; ++i) {
    if (s.started_voices[i] == voice) return;
  }
  if (s.started_count < s.started_voices.size()) {
    s.started_voices[s.started_count++] = voice;
  }
  // A full table still counts the voice: the guest path stays on.
  s.others_started.fetch_add(1, std::memory_order_acq_rel);
}

void RemoveStarted(uint32_t voice) {
  State& s = S();
  std::lock_guard lock(s.voices_mutex);
  for (size_t i = 0; i < s.started_count; ++i) {
    if (s.started_voices[i] == voice) {
      s.started_voices[i] = s.started_voices[--s.started_count];
      s.others_started.fetch_sub(1, std::memory_order_acq_rel);
      return;
    }
  }
}

// ---- Observe-only hooks: they record and decline, so the original runs. ----

// CreateSourceVoice(this, ppSourceVoice, pSourceFormat, Flags, MaxFrequencyRatio, ...).
bool ObserveCreateSource(PPCContext& ctx, uint8_t* base) {
  CaptureBase(base);
  TouchApi();
  if (static_cast<uint32_t>(ctx.lr) != kFmodCreateReturn) return false;
  const uint32_t slot = ctx.r4.u32;
  const uint32_t format = ctx.r5.u32;
  if (!slot || !format) return false;
  // WAVEFORMATEX: tag +0, channels +2, rate +4, block align +12, bits +14,
  // cbSize +16; WAVEFORMATEXTENSIBLE adds the sub-format GUID at +24.
  FmodOutput seen;
  seen.voice_slot = slot;
  seen.format_seen = true;
  seen.format_tag = Load16(base, format + 0);
  seen.channels = Load16(base, format + 2);
  seen.rate = Load32(base, format + 4);
  seen.block_align = Load16(base, format + 12);
  seen.bits = Load16(base, format + 14);
  seen.subformat = seen.format_tag == kFormatExtensible && Load16(base, format + 16) >= 22
                       ? Load32(base, format + 24)
                       : 0;
  std::lock_guard lock(S().fmod_mutex);
  FmodOutput& fmod = S().fmod;
  fmod.voice_slot = seen.voice_slot;
  fmod.format_seen = true;
  fmod.format_tag = seen.format_tag;
  fmod.channels = seen.channels;
  fmod.rate = seen.rate;
  fmod.block_align = seen.block_align;
  fmod.bits = seen.bits;
  fmod.subformat = seen.subformat;
  return false;
}

bool ObserveApi(PPCContext&, uint8_t* base) {
  CaptureBase(base);
  TouchApi();
  return false;
}

// Why FMOD's ring cannot be played on the host, empty when it can.
std::string RingProblem(const FmodOutput& fmod, uint32_t flags, uint32_t play_begin,
                        uint32_t play_length, uint32_t loop_begin, uint32_t loop_length,
                        uint32_t loop_count) {
  if (!fmod.format_seen) return "create not observed";
  const bool is_float = fmod.format_tag == kFormatIeeeFloat ||
                        (fmod.format_tag == kFormatExtensible && fmod.subformat == 3);
  if (!is_float || fmod.bits != 32) return "not 32-bit float";
  if (fmod.channels != 2 && fmod.channels != 6) return "channels not 2 or 6";
  if (fmod.rate != kOutputRate) return "rate not 48000";
  if (fmod.block_align != fmod.channels * 4u) return "block align";
  if (!fmod.ring || !fmod.ring_frames || fmod.ring_bytes % fmod.block_align) return "ring size";
  if (flags != 0) return "buffer flags";
  if (play_begin != 0 || (play_length != 0 && play_length != fmod.ring_frames)) {
    return "play region";
  }
  if (loop_begin != 0 || (loop_length != 0 && loop_length != fmod.ring_frames)) {
    return "loop region";
  }
  if (loop_count != kLoopInfinite) return "not looping";
  return {};
}

// SubmitSourceBuffer(this, pBuffer, pBufferWMA). XAUDIO2_BUFFER: Flags +0,
// AudioBytes +4, pAudioData +8, PlayBegin +12, PlayLength +16, LoopBegin +20,
// LoopLength +24, LoopCount +28, pContext +32.
bool ObserveSubmit(PPCContext& ctx, uint8_t* base) {
  CaptureBase(base);
  TouchApi();
  if (static_cast<uint32_t>(ctx.lr) != kFmodSubmitReturn) return false;
  const uint32_t voice = ctx.r3.u32;
  const uint32_t buffer = ctx.r4.u32;
  if (!voice || !buffer) return false;
  State& s = S();
  const uint32_t flags = Load32(base, buffer + 0);
  const uint32_t bytes = Load32(base, buffer + 4);
  const uint32_t data = Load32(base, buffer + 8);
  const uint32_t play_begin = Load32(base, buffer + 12);
  const uint32_t play_length = Load32(base, buffer + 16);
  const uint32_t loop_begin = Load32(base, buffer + 20);
  const uint32_t loop_length = Load32(base, buffer + 24);
  const uint32_t loop_count = Load32(base, buffer + 28);
  const uint32_t context = Load32(base, buffer + 32);

  ForceGuestNow();
  FmodOutput snapshot;
  {
    std::lock_guard lock(s.fmod_mutex);
    FmodOutput& fmod = s.fmod;
    const bool new_voice = fmod.voice != voice;
    fmod.voice = voice;
    fmod.ring = data;
    fmod.ring_bytes = bytes;
    fmod.ring_frames = fmod.block_align ? bytes / fmod.block_align : 0;
    fmod.reason = RingProblem(fmod, flags, play_begin, play_length, loop_begin, loop_length,
                              loop_count);
    // The create wrote FMOD's voice to its slot: check that this is that voice.
    if (fmod.reason.empty() && fmod.voice_slot && Load32(base, fmod.voice_slot) != voice) {
      fmod.reason = "voice is not the created one";
    }
    fmod.usable = fmod.reason.empty();
    s.ring_frames.store(std::max<uint32_t>(fmod.ring_frames, 1), std::memory_order_relaxed);
    if (new_voice) {
      s.offset.store(0, std::memory_order_relaxed);
      s.fmod_started.store(false, std::memory_order_relaxed);
    }
    s.last_context.store(context, std::memory_order_relaxed);
    s.last_queued.store(1, std::memory_order_relaxed);
    s.fmod_voice.store(voice, std::memory_order_release);
    snapshot = fmod;
  }
  ResetObservations();
  s.fmod_generation.fetch_add(1, std::memory_order_acq_rel);
  diagnostics::RecordEvent(
      "audio.native_output_fmod",
      {{"voice", fmt::format("{:08X}", voice)},
       {"ring", fmt::format("{:08X}", snapshot.ring)},
       {"ring_bytes", std::to_string(snapshot.ring_bytes)},
       {"ring_frames", std::to_string(snapshot.ring_frames)},
       {"channels", std::to_string(snapshot.channels)},
       {"rate", std::to_string(snapshot.rate)},
       {"format_tag", fmt::format("{:04X}", snapshot.format_tag)},
       {"usable", snapshot.usable ? "1" : "0"},
       {"reason", snapshot.reason}});
  return false;
}

// Start(this, Flags, OperationSet).
bool ObserveStart(PPCContext& ctx, uint8_t* base) {
  CaptureBase(base);
  TouchApi();
  const uint32_t voice = ctx.r3.u32;
  if (voice && voice == S().fmod_voice.load(std::memory_order_acquire)) {
    S().fmod_started.store(true, std::memory_order_release);
    ResetObservations();
  } else if (IsSourceVoice(base, voice)) {
    AddStarted(voice);
  }
  return false;
}

// Stop(this, Flags, OperationSet).
bool ObserveStop(PPCContext& ctx, uint8_t* base) {
  CaptureBase(base);
  TouchApi();
  const uint32_t voice = ctx.r3.u32;
  if (voice && voice == S().fmod_voice.load(std::memory_order_acquire)) {
    S().fmod_started.store(false, std::memory_order_release);
    ForceGuestNow();
  } else {
    RemoveStarted(voice);
  }
  return false;
}

// DestroyVoice(this): the interface thunk.
bool ObserveDestroy(PPCContext& ctx, uint8_t* base) {
  CaptureBase(base);
  TouchApi();
  const uint32_t voice = ctx.r3.u32;
  RemoveStarted(voice);
  if (voice && voice == S().fmod_voice.load(std::memory_order_acquire)) {
    InvalidateFmod("voice destroyed", true);
  }
  return false;
}

// FlushSourceBuffers(this).
bool ObserveFlush(PPCContext& ctx, uint8_t* base) {
  CaptureBase(base);
  TouchApi();
  const uint32_t voice = ctx.r3.u32;
  if (voice && voice == S().fmod_voice.load(std::memory_order_acquire)) {
    InvalidateFmod("buffers flushed", false);
  }
  return false;
}

// ---- GetState(this, pVoiceState, Flags) ----
// XAUDIO2_VOICE_STATE: pCurrentBufferContext +0, BuffersQueued +4,
// SamplesPlayed +8 (u64, left alone with XAUDIO2_VOICE_NOSAMPLESPLAYED).

void RecordObservation(uint64_t samples, uint64_t blocks, bool shadow) {
  State& s = S();
  std::lock_guard lock(s.observation_mutex);
  Observation& last = s.observation;
  if (shadow && last.valid && s.fmod_started.load(std::memory_order_relaxed) &&
      blocks >= last.blocks) {
    const uint64_t expected = last.samples + kBlock * (blocks - last.blocks);
    const uint64_t phase = samples > expected ? samples - expected : expected - samples;
    s.observations.fetch_add(1, std::memory_order_relaxed);
    if (phase) {
      s.phase_errors.fetch_add(1, std::memory_order_relaxed);
      uint64_t seen = s.phase_max_abs.load(std::memory_order_relaxed);
      while (phase > seen &&
             !s.phase_max_abs.compare_exchange_weak(seen, phase, std::memory_order_relaxed)) {
      }
    }
  }
  last = {samples, blocks, true};
}

void CallOriginalGetState(PPCContext& ctx, uint8_t* base) {
  const uint64_t lr = ctx.lr;
  PINYON_GUEST_NATIVE_JOIN(__imp__sub_, PINYON_GUEST_NATIVE_PICK(830B0A38, 83171D10))(ctx, base);
  ctx.lr = lr;
}

bool AnswerGetStateLive(PPCContext& ctx, uint8_t* base) {
  State& s = S();
  const uint32_t voice = ctx.r3.u32;
  if (!voice || voice != s.fmod_voice.load(std::memory_order_acquire)) return false;
  const uint32_t state = ctx.r4.u32;
  const uint32_t flags = ctx.r5.u32;
  if (!state) return false;
  CaptureBase(base);
  s.poll_seq.fetch_add(1, std::memory_order_acq_rel);
  const bool shadow = REXCVAR_GET(pinyon_shift_native_audio_shadow);

  if (s.native_active.load(std::memory_order_acquire)) {
    const uint64_t samples = s.host_counter.load(std::memory_order_acquire);
    if (shadow) {
      // The frozen guest voice must not move while the host plays.
      CallOriginalGetState(ctx, base);
      if (!(flags & kVoiceNoSamplesPlayed) &&
          Load64(base, state + 8) != s.frozen_guest.load(std::memory_order_relaxed)) {
        s.moved_while_native.fetch_add(1, std::memory_order_relaxed);
      }
    }
    Store32(base, state + 0, s.last_context.load(std::memory_order_relaxed));
    Store32(base, state + 4, s.last_queued.load(std::memory_order_relaxed));
    if (!(flags & kVoiceNoSamplesPlayed)) Store64(base, state + 8, samples);
    return true;
  }

  const uint64_t before = s.epoch.load(std::memory_order_acquire);
  CallOriginalGetState(ctx, base);
  const uint64_t after = s.epoch.load(std::memory_order_acquire);
  s.last_context.store(Load32(base, state + 0), std::memory_order_relaxed);
  s.last_queued.store(Load32(base, state + 4), std::memory_order_relaxed);
  if (!(flags & kVoiceNoSamplesPlayed)) {
    const uint64_t samples = Load64(base, state + 8);
    // Only a poll that no guest block overlapped is an exact count.
    if (before == after && !(before & 1)) RecordObservation(samples, before / 2, shadow);
    const uint64_t offset = s.offset.load(std::memory_order_acquire);
    if (offset) Store64(base, state + 8, samples + offset);
  }
  return true;
}

bool AnswerGetState(PPCContext& ctx, uint8_t* base) {
  if (!rex::session_probe::Enabled()) return AnswerGetStateLive(ctx, base);
  const uint32_t voice = ctx.r3.u32, state = ctx.r4.u32, flags = ctx.r5.u32;
  if (!voice || !state) return false;
  const uint64_t key = (uint64_t(voice) << 32) | state;
  rex::session_probe::Scalar(rex::session_probe::Kind::kHostHook, key, flags);
  if (!rex::session_probe::Replaying()) {
    rex::session_probe::Suppress private_query;
    if (!AnswerGetStateLive(ctx, base)) CallOriginalGetState(ctx, base);
  }
  rex::session_probe::Blob(rex::session_probe::Kind::kHostHook, key, Host(base, state), 16);
  return true;
}

size_t GetStateWritten(const PPCContext& ctx, uint8_t*, GuestRange* ranges) {
  if (!ctx.r4.u32) return 0;
  ranges[0] = {ctx.r4.u32, 16};
  return 1;
}

bool Always() { return true; }

// Interface methods by slot (site/findings/audio.toml). Sizes are the
// functions' .pdata lengths; the three interface thunks are 8 bytes.
Replacement get_state_replacement = {
    .name = "xaudio2.get_state",
    .guest_address = kV4 ? 0x83171D10u : 0x830B0A38u,
    .guest_code_size = 264,
    .guest_code_fnv1a = kV4 ? 0x3B450F2601374FFEull : 0xEA7E83AA24F23FC6ull,
    .returns = kReturnNone,
    .enabled = Always,
    .native = AnswerGetState,
    .written = GetStateWritten,
};
Replacement submit_replacement = {
    .name = "xaudio2.submit_source_buffer",
    .guest_address = kV4 ? 0x83171B88u : 0x830B08B0u,
    .guest_code_size = 392,
    .guest_code_fnv1a = kV4 ? 0x9D52A63C62A88425ull : 0x6687EBDE3A998505ull,
    .returns = kReturnR3,
    .enabled = Always,
    .native = ObserveSubmit,
    .written = nullptr,
};
Replacement start_replacement = {
    .name = "xaudio2.start",
    .guest_address = kV4 ? 0x83171848u : 0x830B0570u,
    .guest_code_size = 340,
    .guest_code_fnv1a = kV4 ? 0x525820108AA285E3ull : 0x9617166AB3FD3EB4ull,
    .returns = kReturnR3,
    .enabled = Always,
    .native = ObserveStart,
    .written = nullptr,
};
Replacement stop_replacement = {
    .name = "xaudio2.stop",
    .guest_address = kV4 ? 0x831719A0u : 0x830B06C8u,
    .guest_code_size = 484,
    .guest_code_fnv1a = kV4 ? 0xC5DA373DED8ED85Dull : 0x4FFD148883A8B632ull,
    .returns = kReturnR3,
    .enabled = Always,
    .native = ObserveStop,
    .written = nullptr,
};
Replacement destroy_replacement = {
    .name = "xaudio2.destroy_voice",
    .guest_address = kV4 ? 0x83171230u : 0x830B6510u,
    .guest_code_size = 8,
    .guest_code_fnv1a = kV4 ? 0x85F5FF83D065DD75ull : 0x8461D583CF0EB226ull,
    .returns = kReturnNone,
    .enabled = Always,
    .native = ObserveDestroy,
    .written = nullptr,
};
Replacement flush_replacement = {
    .name = "xaudio2.flush_source_buffers",
    .guest_address = kV4 ? 0x831703C0u : 0x830AF230u,
    .guest_code_size = 164,
    .guest_code_fnv1a = 0x18D463D4DCFD52A2ull,
    .returns = kReturnR3,
    .enabled = Always,
    .native = ObserveFlush,
    .written = nullptr,
};
Replacement create_source_replacement = {
    .name = "xaudio2.create_source_voice",
    .guest_address = kV4 ? 0x831696F8u : 0x830A86F8u,
    .guest_code_size = 540,
    .guest_code_fnv1a = kV4 ? 0x06C4AA671B793982ull : 0x1A9D1130B1BF0324ull,
    .returns = kReturnR3,
    .enabled = Always,
    .native = ObserveCreateSource,
    .written = nullptr,
};
Replacement create_submix_replacement = {
    .name = "xaudio2.create_submix_voice",
    .guest_address = kV4 ? 0x8316A6A8u : 0x830A96A8u,
    .guest_code_size = 440,
    .guest_code_fnv1a = kV4 ? 0x4300D8FF7D5258E5ull : 0x7AFA769902DC23E4ull,
    .returns = kReturnR3,
    .enabled = Always,
    .native = ObserveApi,
    .written = nullptr,
};
Replacement output_matrix_replacement = {
    .name = "xaudio2.set_output_matrix",
    .guest_address = kV4 ? 0x831764C8u : 0x830B6500u,
    .guest_code_size = 8,
    .guest_code_fnv1a = kV4 ? 0x84EE3383CF86D44Full : 0x84733383CF1DC513ull,
    .returns = kReturnR3,
    .enabled = Always,
    .native = ObserveApi,
    .written = nullptr,
};
Replacement effect_chain_replacement = {
    .name = "xaudio2.set_effect_chain",
    .guest_address = kV4 ? 0x831776E8u : 0x830B5260u,
    .guest_code_size = 8,
    .guest_code_fnv1a = kV4 ? 0x84C4AB83CF62E823ull : 0x84FBCB83CF9260F3ull,
    .returns = kReturnR3,
    .enabled = Always,
    .native = ObserveApi,
    .written = nullptr,
};

// Whether a hook's guest code matched (checked now if no call has yet).
bool HookInstalled(const Replacement& replacement, uint8_t* base) {
  const int state = replacement.code_state.load(std::memory_order_acquire);
  if (state != 0) return state > 0;
  // Unchecked: the code bytes are still readable (retire_code.cpp checks
  // every replacement before it retires them).
  return HashGuestCode(base + replacement.guest_address, replacement.guest_code_size) ==
         replacement.guest_code_fnv1a;
}

// ---- The Audio Worker side ----

class FmodFrameSource final : public rex::audio::HostFrameSource {
 public:
  bool Accepts(uint32_t, uint32_t) override {
    // FH1 registers one render-driver client, XAudio2's.
    bool expected = false;
    return accepted_.compare_exchange_strong(expected, true);
  }

  bool BuildFrame(uint32_t* frame) override {
    if (!rex::session_probe::Enabled()) return BuildFrameLive(frame);
    const bool live = BuildFrameLive(frame);
    const bool built = rex::session_probe::Scalar(rex::session_probe::Kind::kHostHook,
        UINT64_C(0x4353303141554401), live, true);
    if (built) rex::session_probe::Bytes(rex::session_probe::Kind::kHostHook,
        UINT64_C(0x4353303141554402), frame, kFrameWords * sizeof(uint32_t));
    return built;
  }

  bool BuildFrameLive(uint32_t* frame) {
    State& s = S();
    Refresh();
    PendingEvent event;
    bool built = false;
    {
      std::lock_guard lock(s.switch_mutex);
      // A guest thread may have handed back at once (voice or ring gone).
      if (mode_ != Mode::kGuest && !s.native_active.load(std::memory_order_acquire)) {
        mode_ = Mode::kGuest;
        event = {"guest", "forced"};
        ++fallbacks_;
      }
      const char* blocker = NativeBlocker();
      const bool want = blocker == nullptr;
      if (mode_ == Mode::kGuest) {
        if (want && EnterNative()) {
          mode_ = Mode::kNative;
          event = {"native", "idle"};
        }
      } else if (!want) {
        if (mode_ == Mode::kNative) {
          mode_ = Mode::kHandingBack;
          handback_reason_ = blocker;
        }
      } else if (mode_ == Mode::kHandingBack) {
        mode_ = Mode::kNative;
      }
      if (mode_ == Mode::kHandingBack &&
          (s.host_counter.load(std::memory_order_relaxed) - native_start_) % ring_frames_ == 0) {
        LeaveNative();
        mode_ = Mode::kGuest;
        event = {"guest", handback_reason_};
        ++fallbacks_;
      }
      if (mode_ != Mode::kGuest) {
        ReadBlock(s.host_counter.load(std::memory_order_relaxed), frame, true);
        s.host_counter.fetch_add(kBlock, std::memory_order_acq_rel);
        ++native_blocks_;
        built = true;
      }
    }
    if (event.mode) LogMode(event);
    if (built) MaybeLog();
    return built;
  }

  void GuestBlockBegin() override {
    State& s = S();
    if (REXCVAR_GET(pinyon_shift_native_audio_shadow)) PrepareShadow();
    s.epoch.fetch_add(1, std::memory_order_acq_rel);
  }

  void GuestBlockEnd() override {
    S().epoch.fetch_add(1, std::memory_order_acq_rel);
    ++guest_blocks_;
    {
      std::lock_guard lock(shadow_mutex_);
      shadow_pending_ = false;
    }
    MaybeLog();
  }

  void ObserveGuestFrame(const uint32_t* frame) override {
    std::lock_guard lock(shadow_mutex_);
    if (!shadow_pending_) return;
    shadow_pending_ = false;
    ++shadow_.blocks;
    for (uint32_t i = 0; i < kFrameWords; ++i) {
      const float guest = Float(frame[i]);
      shadow_.signal += double(guest) * guest;
      for (size_t lag = 0; lag < kLags; ++lag) {
        if (!lag_valid_[lag]) continue;
        const float difference = guest - Float(lag_frames_[lag][i]);
        shadow_.noise[lag] += double(difference) * difference;
        shadow_.max_abs[lag] = std::max(shadow_.max_abs[lag], std::abs(difference));
      }
    }
    for (size_t lag = 0; lag < kLags; ++lag) shadow_.compared[lag] += lag_valid_[lag] ? 1 : 0;
  }

 private:
  enum class Mode { kGuest, kNative, kHandingBack };
  struct PendingEvent {
    const char* mode = nullptr;
    const char* reason = nullptr;
  };
  // Shadow lags in blocks: the block before the guest's count, at it, after it.
  static constexpr size_t kLags = 3;
  static constexpr int kLagBlocks[kLags] = {-1, 0, 1};
  struct ShadowStats {
    uint64_t blocks = 0;
    double signal = 0;
    std::array<double, kLags> noise{};
    std::array<float, kLags> max_abs{};
    std::array<uint64_t, kLags> compared{};
  };

  static float Float(uint32_t big_endian) {
    return std::bit_cast<float>(std::byteswap(big_endian));
  }

  // Picks up a new FMOD output (or its loss) from the hooks.
  void Refresh() {
    State& s = S();
    const uint32_t generation = s.fmod_generation.load(std::memory_order_acquire);
    if (generation == generation_) return;
    generation_ = generation;
    uint8_t* base = s.base.load(std::memory_order_acquire);
    FmodOutput fmod;
    {
      std::lock_guard lock(s.fmod_mutex);
      fmod = s.fmod;
    }
    usable_ = fmod.usable && base;
    ring_frames_ = fmod.ring_frames ? fmod.ring_frames : 1;
    // The hand-back waits for the host to have played whole rings; with a
    // ring that is not a multiple of a block that can take ring/gcd blocks.
    if (usable_ && ring_frames_ / std::gcd(ring_frames_, uint64_t(kBlock)) > kMaxAlignBlocks) {
      usable_ = false;
      diagnostics::RecordEvent("audio.native_output_unavailable",
                               {{"reason", "ring not block aligned"},
                                {"ring_frames", std::to_string(ring_frames_)}});
    }
    channels_ = fmod.channels;
    ring_ = usable_ ? reinterpret_cast<const uint32_t*>(Host(base, fmod.ring)) : nullptr;
    if (usable_ && !hooks_checked_) {
      hooks_checked_ = true;
      for (const Replacement* hook :
           {&get_state_replacement, &submit_replacement, &start_replacement, &stop_replacement,
            &destroy_replacement, &flush_replacement, &create_source_replacement}) {
        if (!HookInstalled(*hook, base)) {
          hooks_ok_ = false;
          diagnostics::RecordEvent("audio.native_output_unavailable",
                                   {{"reason", "guest code mismatch"}, {"hook", hook->name}});
        }
      }
    }
    // A new output always arrives in guest mode (its hooks force the hand-back
    // first), so no native window spans two rings.
  }

  // Null when the host may play FMOD's ring now, otherwise why not.
  const char* NativeBlocker() const {
    State& s = S();
    if (!REXCVAR_GET(pinyon_shift_native_audio_output)) return "setting off";
    if (!REXCVAR_GET(pinyon_shift_guest_native)) return "guest_native off";
    if (!usable_ || !hooks_ok_) return "no usable fmod output";
    if (!s.fmod_started.load(std::memory_order_acquire)) return "fmod voice stopped";
    if (s.others_started.load(std::memory_order_acquire)) return "other voice started";
    const int64_t idle_ns = int64_t(REXCVAR_GET(pinyon_shift_xaudio2_idle_ms)) * 1000000;
    if (NowNs() - s.last_api_ns.load(std::memory_order_relaxed) < idle_ns) return "api activity";
    return nullptr;
  }

  // Guest to host at a block boundary: needs a poll taken since the last guest
  // block, so the frozen count is exact. Under switch_mutex.
  bool EnterNative() {
    State& s = S();
    Observation observation;
    {
      std::lock_guard lock(s.observation_mutex);
      observation = s.observation;
    }
    if (!observation.valid || observation.blocks != s.epoch.load(std::memory_order_acquire) / 2) {
      return false;
    }
    const uint64_t counter = observation.samples + s.offset.load(std::memory_order_relaxed);
    s.frozen_guest.store(observation.samples, std::memory_order_relaxed);
    s.host_counter.store(counter, std::memory_order_release);
    native_start_ = counter;
    s.native_active.store(true, std::memory_order_release);
    ++switches_;
    return true;
  }

  // Host to guest once the host has played whole rings since it took over, so
  // the frozen guest voice is at the host cursor's ring offset. Under
  // switch_mutex.
  void LeaveNative() {
    State& s = S();
    s.offset.store(s.host_counter.load(std::memory_order_relaxed) -
                       s.frozen_guest.load(std::memory_order_relaxed),
                   std::memory_order_release);
    s.native_active.store(false, std::memory_order_release);
    ++switches_;
  }

  // 256 frames of FMOD's ring from `counter` into the planar six-channel
  // frame; stereo fills the two front channels. Both sides are big-endian.
  void ReadBlock(uint64_t counter, uint32_t* frame, bool acquire) const {
    if (acquire) (void)S().poll_seq.load(std::memory_order_acquire);
    uint64_t position = counter % ring_frames_;
    const uint32_t channels = channels_;
    for (uint32_t i = 0; i < kBlock; ++i) {
      const uint32_t* source = ring_ + position * channels;
      for (uint32_t channel = 0; channel < channels; ++channel) {
        frame[channel * kBlock + i] = source[channel];
      }
      if (++position == ring_frames_) position = 0;
    }
    std::fill(frame + channels * kBlock, frame + kFrameWords, 0u);
  }

  // Before a guest block: the host's blocks at the guest's count and a block
  // either side, for ObserveGuestFrame to compare.
  void PrepareShadow() {
    State& s = S();
    if (mode_ != Mode::kGuest || !usable_ || !ring_ ||
        !s.fmod_started.load(std::memory_order_acquire)) {
      return;
    }
    Observation observation;
    {
      std::lock_guard lock(s.observation_mutex);
      observation = s.observation;
    }
    const uint64_t blocks = s.epoch.load(std::memory_order_acquire) / 2;
    if (!observation.valid || observation.blocks > blocks) return;
    const uint64_t count = observation.samples + kBlock * (blocks - observation.blocks);
    std::lock_guard lock(shadow_mutex_);
    for (size_t lag = 0; lag < kLags; ++lag) {
      const int64_t start = int64_t(count) + int64_t(kLagBlocks[lag]) * kBlock;
      lag_valid_[lag] = start >= 0;
      if (lag_valid_[lag]) ReadBlock(uint64_t(start), lag_frames_[lag].data(), lag == 0);
    }
    shadow_pending_ = true;
  }

  static std::string Snr(double signal, double noise) {
    if (noise == 0) return signal == 0 ? "silent" : "exact";
    if (signal == 0) return "-inf";
    return fmt::format("{:.1f}", 10.0 * std::log10(signal / noise));
  }

  void LogMode(const PendingEvent& event) {
    if (mode_events_ >= kMaxModeEvents) return;
    ++mode_events_;
    State& s = S();
    diagnostics::RecordEvent(
        "audio.native_output_mode",
        {{"mode", event.mode},
         {"reason", event.reason ? event.reason : ""},
         {"host_counter", std::to_string(s.host_counter.load(std::memory_order_relaxed))},
         {"offset", std::to_string(s.offset.load(std::memory_order_relaxed))},
         {"ring_frames", std::to_string(ring_frames_)},
         {"switches", std::to_string(switches_)},
         {"fallbacks", std::to_string(fallbacks_)}});
  }

  // Once a second while shadow mode is on.
  void MaybeLog() {
    if (!REXCVAR_GET(pinyon_shift_native_audio_shadow)) return;
    const int64_t now = NowNs();
    if (now - last_log_ns_ < 1000000000) return;
    last_log_ns_ = now;
    State& s = S();
    ShadowStats stats;
    {
      std::lock_guard lock(shadow_mutex_);
      stats = shadow_;
      shadow_ = {};
    }
    size_t best = 1;
    for (size_t lag = 0; lag < kLags; ++lag) {
      if (stats.compared[lag] == stats.blocks && stats.noise[lag] < stats.noise[best]) best = lag;
    }
    const uint64_t samples = stats.blocks * kFrameWords;
    diagnostics::RecordEvent(
        "audio.native_output_shadow",
        {{"mode", mode_ == Mode::kGuest ? "guest" : "native"},
         {"blocks", std::to_string(stats.blocks)},
         {"max_abs_diff", fmt::format("{:.9g}", stats.max_abs[1])},
         {"snr_db", Snr(stats.signal, stats.noise[1])},
         {"best_lag_blocks", std::to_string(kLagBlocks[best])},
         {"best_snr_db", Snr(stats.signal, stats.noise[best])},
         {"guest_rms",
          fmt::format("{:.6g}", samples ? std::sqrt(stats.signal / double(samples)) : 0.0)},
         {"phase_max_abs", std::to_string(s.phase_max_abs.exchange(0, std::memory_order_relaxed))},
         {"phase_errors", std::to_string(s.phase_errors.exchange(0, std::memory_order_relaxed))},
         {"observations", std::to_string(s.observations.exchange(0, std::memory_order_relaxed))},
         {"moved_while_native",
          std::to_string(s.moved_while_native.exchange(0, std::memory_order_relaxed))},
         {"guest_blocks", std::to_string(guest_blocks_)},
         {"native_blocks", std::to_string(native_blocks_)},
         {"switches", std::to_string(switches_)},
         {"fallbacks", std::to_string(fallbacks_)}});
  }

  std::atomic<bool> accepted_{false};

  // Audio Worker only.
  Mode mode_ = Mode::kGuest;
  uint32_t generation_ = 0;
  bool usable_ = false;
  bool hooks_checked_ = false;
  bool hooks_ok_ = true;
  const uint32_t* ring_ = nullptr;
  uint64_t ring_frames_ = 1;
  uint32_t channels_ = 0;
  uint64_t native_start_ = 0;
  const char* handback_reason_ = "";
  uint64_t switches_ = 0;
  uint64_t fallbacks_ = 0;
  uint64_t guest_blocks_ = 0;
  uint64_t native_blocks_ = 0;
  uint32_t mode_events_ = 0;
  int64_t last_log_ns_ = 0;

  std::mutex shadow_mutex_;
  bool shadow_pending_ = false;               // under shadow_mutex_
  std::array<bool, kLags> lag_valid_{};       // under shadow_mutex_
  std::array<std::array<uint32_t, kFrameWords>, kLags> lag_frames_{};  // under shadow_mutex_
  ShadowStats shadow_;                         // under shadow_mutex_
};

// Offered to the render-driver client the title registers at boot; never
// destroyed, as the Audio Worker may run until process exit.
const bool frame_source_registered = [] {
  rex::audio::SetHostFrameSource(new FmodFrameSource);
  return true;
}();

}  // namespace
}  // namespace pinyon_shift::guest_native

PINYON_GUEST_NATIVE(830B0A38, 83171D10, ::pinyon_shift::guest_native::get_state_replacement)
PINYON_GUEST_NATIVE(830B08B0, 83171B88, ::pinyon_shift::guest_native::submit_replacement)
PINYON_GUEST_NATIVE(830B0570, 83171848, ::pinyon_shift::guest_native::start_replacement)
PINYON_GUEST_NATIVE(830B06C8, 831719A0, ::pinyon_shift::guest_native::stop_replacement)
PINYON_GUEST_NATIVE(830B6510, 83171230, ::pinyon_shift::guest_native::destroy_replacement)
PINYON_GUEST_NATIVE(830AF230, 831703C0, ::pinyon_shift::guest_native::flush_replacement)
PINYON_GUEST_NATIVE(830A86F8, 831696F8, ::pinyon_shift::guest_native::create_source_replacement)
PINYON_GUEST_NATIVE(830A96A8, 8316A6A8, ::pinyon_shift::guest_native::create_submix_replacement)
PINYON_GUEST_NATIVE(830B6500, 831764C8, ::pinyon_shift::guest_native::output_matrix_replacement)
PINYON_GUEST_NATIVE(830B5260, 831776E8, ::pinyon_shift::guest_native::effect_chain_replacement)
