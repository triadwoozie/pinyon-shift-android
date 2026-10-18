// Native twin of FMOD Ex's ParamEQ unit (NEAR_NATIVE_RESEARCH NN-4.3).
//
// The ParamEQ read (sub_82FC6CC0, v4 sub_83087D70) is reached only through
// the this-adjusting thunk sub_82FC7A00 (v4 sub_83088AB0), called indirectly
// from FMOD's DSP graph, so the replacement sits on the thunk and works with
// the generated code as it is (no host_overridden_functions entry). The twin
// implements the contract recorded in site/findings (threads.toml "FMOD
// ParamEQ (six-channel biquad)") with the published design in
// fmod_dsp_math.h: the peaking-EQ biquad's coefficients come from the unit's
// parameters, never from the executable, so the output differs from the
// translated unit by float rounding and is accepted within the audio
// threshold (docs/LEGAL.md "Native replacements of game and library code").
//
// On by default (pinyon_shift_fmod_native_parameq, hot): on the Odin it took
// 0.14 ms a frame (5.7 %) off the FMOD mixer thread. With
// pinyon_shift_fmod_dsp_verify N (or pinyon_shift_guest_native_verify N)
// every Nth call runs the twin on saved state, rolls its writes back, runs
// the translated original for real and compares the two outputs as a
// per-call signal-to-error ratio (fmod_dsp.verify events); the game keeps the
// original's results.
//
// A twin of the channel matrix mix (sub_82FAE0B8 and its ramp sub_82FAB740)
// verified clean but gave no measurable gain and was removed; see
// site/pages/lessons.md and audio.toml "FMOD matrix mix".

#include <algorithm>
#include <atomic>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <fmt/format.h>
#include <rex/cvar.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>
#include <rex/system/xmemory.h>

#include "fh1_guest_address.h"
#include "guest_native/fmod_dsp_math.h"
#include "guest_native/guest_native.h"
#include "pinyon_shift_diagnostics.h"

REXCVAR_DEFINE_BOOL(pinyon_shift_fmod_native_parameq, true, "Pinyon Shift",
                    "Run FMOD's ParamEQ effect as native code (a peaking-EQ biquad from its "
                    "parameters; NN-4.3)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_UINT32(pinyon_shift_fmod_dsp_verify, 0, "Pinyon Shift",
                      "Every Nth call of a native FMOD DSP twin also runs the original and "
                      "compares the outputs as a signal-to-error ratio, keeping the original's "
                      "(0 = off, 1 = every call)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_DOUBLE(pinyon_shift_fmod_dsp_verify_snr_db, 60.0, "Pinyon Shift",
                      "Signal-to-error ratio a verified FMOD DSP twin call must reach (dB)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

#define FMOD_DSP_IMP(base_hex, v4_hex) \
  PINYON_GUEST_NATIVE_JOIN(__imp__sub_, PINYON_GUEST_NATIVE_PICK(base_hex, v4_hex))

extern "C" REX_FUNC(FMOD_DSP_IMP(82FC7A00, 83088AB0));

namespace pinyon_shift::guest_native {
namespace {

namespace dsp = fmod_dsp;
constexpr bool kV4 = pinyon_shift::fh1::kTitleUpdateV4;

// ---- Guest memory --------------------------------------------------------

uint8_t* Host(uint8_t* base, uint32_t address) {
  return base + address + rex::memory::detail::PhysicalHostOffset(address);
}
uint32_t LoadU32(uint8_t* base, uint32_t address) {
  uint32_t value;
  std::memcpy(&value, Host(base, address), sizeof(value));
  return std::byteswap(value);
}
uint16_t LoadU16(uint8_t* base, uint32_t address) {
  uint16_t value;
  std::memcpy(&value, Host(base, address), sizeof(value));
  return std::byteswap(value);
}
float LoadF32(uint8_t* base, uint32_t address) {
  return std::bit_cast<float>(LoadU32(base, address));
}
void StoreF32(uint8_t* base, uint32_t address, float value) {
  const uint32_t bits = std::byteswap(std::bit_cast<uint32_t>(value));
  std::memcpy(Host(base, address), &bits, sizeof(bits));
}

// A guest float array that lies in one memory region (one host offset).
bool OneRegion(uint32_t address, size_t count) {
  if (count == 0) return true;
  const uint64_t last = uint64_t(address) + count * 4 - 1;
  if (last > 0xFFFFFFFFull) return false;
  return rex::memory::detail::PhysicalHostOffset(address) ==
         rex::memory::detail::PhysicalHostOffset(uint32_t(last));
}
void LoadFloats(uint8_t* base, uint32_t address, size_t count, float* out) {
  const uint8_t* host = Host(base, address);
  for (size_t i = 0; i < count; ++i) {
    uint32_t bits;
    std::memcpy(&bits, host + 4 * i, sizeof(bits));
    out[i] = std::bit_cast<float>(std::byteswap(bits));
  }
}
// The largest block a twin takes (frames x channels); FMOD mixes 256 frames.
constexpr size_t kMaxSamples = 1u << 16;

// A helper's guest code checked with the twins that depend on its contract.
// Declared so CheckAllGuestCode hashes it before the code bytes are retired;
// it is never dispatched.
bool Never() { return false; }
bool Decline(PPCContext&, uint8_t*) { return false; }

bool GuestCodeMatches(const Replacement& replacement, uint8_t* base) {
  const int state = replacement.code_state.load(std::memory_order_acquire);
  if (state != 0) return state > 0;
  return HashGuestCode(base + replacement.guest_address, replacement.guest_code_size) ==
         replacement.guest_code_fnv1a;
}

// A twin's contract checks, answered once. GuestCodeMatches hashes a function
// nothing has marked checked, and the check-only helpers stay unmarked unless
// code retirement checked them, so asking it on every call hashed kilobytes
// of guest code serially (about 5 us a call on the Odin).
bool ContractMatches(std::atomic<int>& cache, bool (*check)(uint8_t*), uint8_t* base) {
  const int state = cache.load(std::memory_order_relaxed);
  if (state != 0) return state > 0;
  const bool matches = check(base);
  cache.store(matches ? 1 : -1, std::memory_order_relaxed);
  return matches;
}

// ---- Verify --------------------------------------------------------------

constexpr uint64_t kMaxLoggedMismatches = 16;
constexpr uint64_t kMaxLoggedStateDifferences = 8;
constexpr uint64_t kSummaryEvery = 4096;
// An error below this RMS passes whatever the ratio: 1e-5 of full scale
// (-100 dBFS) is 10 dB below one step of the 16-bit output (3.05e-5), and
// both units have at most unity gain to the output in the title's mixes, so
// such an error cannot be heard. The ratio alone fails calls whose own output
// is near silence (a level ramping out, a filter fed silence) on rounding.
constexpr double kNegligibleErrorRms = 1e-5;

struct VerifyTotals {
  std::mutex mutex;
  uint64_t verified = 0, below = 0, state_differs = 0, raced = 0;
  double min_snr = INFINITY;
};

struct VerifyResult {
  double snr, error_rms;
  size_t samples;
  // The twin left different state bytes (first difference described).
  bool state_differs;
  std::string state_detail;
  // An input field the game thread writes (parameters, target levels,
  // volume) changed while the call was compared, so the two runs saw
  // different inputs; such calls are counted apart.
  bool raced;
  std::string detail;
};

std::string Db(double value) {
  return std::isinf(value) ? std::string(value > 0 ? "inf" : "-inf")
                           : fmt::format("{:.1f}", value);
}

using OwnedFields = std::vector<std::pair<std::string_view, std::string>>;
void Emit(std::string_view event, const OwnedFields& owned) {
  std::vector<diagnostics::Field> fields;
  fields.reserve(owned.size());
  for (const auto& [key, value] : owned) fields.push_back({key, value});
  diagnostics::RecordEvent(event, std::span<const diagnostics::Field>(fields));
}

void RecordVerify(Replacement& replacement, VerifyTotals& totals, const VerifyResult& r,
                  const PPCContext& input) {
  const double threshold = REXCVAR_GET(pinyon_shift_fmod_dsp_verify_snr_db);
  const bool below = !r.raced && r.snr < threshold && r.error_rms > kNegligibleErrorRms;
  replacement.stats.verified.fetch_add(1, std::memory_order_relaxed);
  if (below) replacement.stats.mismatched.fetch_add(1, std::memory_order_relaxed);
  uint64_t verified, below_count, differs, raced;
  double min_snr;
  {
    std::lock_guard lock(totals.mutex);
    verified = ++totals.verified;
    if (below) ++totals.below;
    if (r.state_differs && !r.raced) ++totals.state_differs;
    if (r.raced) ++totals.raced;
    below_count = totals.below;
    differs = totals.state_differs;
    raced = totals.raced;
    if (!r.raced && r.error_rms > kNegligibleErrorRms) totals.min_snr = std::min(totals.min_snr, r.snr);
    min_snr = totals.min_snr;
  }
  auto registers = [&](OwnedFields& fields) {
    fields.push_back({"r3", fmt::format("{:08X}", input.r3.u32)});
    fields.push_back({"r4", fmt::format("{:08X}", input.r4.u32)});
    fields.push_back({"r5", fmt::format("{:08X}", input.r5.u32)});
    fields.push_back({"r6", std::to_string(input.r6.u32)});
    fields.push_back({"r7", std::to_string(input.r7.u32)});
    fields.push_back({"r8", std::to_string(input.r8.u32)});
  };
  if (below && below_count <= kMaxLoggedMismatches) {
    OwnedFields fields = {{"name", replacement.name},
                          {"snr_db", Db(r.snr)},
                          {"error_rms", fmt::format("{:.3g}", r.error_rms)},
                          {"samples", std::to_string(r.samples)},
                          {"detail", r.detail}};
    registers(fields);
    Emit("fmod_dsp.verify_mismatch", fields);
  }
  if (r.state_differs && !r.raced && differs <= kMaxLoggedStateDifferences) {
    OwnedFields fields = {{"name", replacement.name},
                          {"state", r.state_detail},
                          {"detail", r.detail}};
    registers(fields);
    Emit("fmod_dsp.verify_state", fields);
  }
  if (verified % kSummaryEvery == 0 || verified == 1) {
    diagnostics::RecordEvent("fmod_dsp.verify",
                             {{"name", replacement.name},
                              {"verified", std::to_string(verified)},
                              {"below_threshold", std::to_string(below_count)},
                              {"state_differs", std::to_string(differs)},
                              {"raced", std::to_string(raced)},
                              {"min_snr_db", Db(min_snr)},
                              {"threshold_db", fmt::format("{:.1f}", threshold)},
                              {"floor_rms", fmt::format("{:.0e}", kNegligibleErrorRms)}});
  }
}

bool VerifyDue(std::atomic<uint64_t>& calls) {
  const uint32_t every = REXCVAR_GET(pinyon_shift_fmod_dsp_verify);
  if (!every) return false;
  return calls.fetch_add(1, std::memory_order_relaxed) % every == 0;
}

// Saved guest bytes, restored after the twin's trial run.
struct Snapshot {
  struct Range {
    uint32_t address;
    std::vector<uint8_t> bytes;
  };
  std::vector<Range> ranges;
  void Add(uint8_t* base, uint32_t address, size_t size) {
    if (!address || !size) return;
    Range range{address, std::vector<uint8_t>(size)};
    for (size_t i = 0; i < size; ++i) range.bytes[i] = *Host(base, uint32_t(address + i));
    ranges.push_back(std::move(range));
  }
  void Restore(uint8_t* base) const {
    for (const Range& range : ranges) {
      for (size_t i = 0; i < range.bytes.size(); ++i) {
        *Host(base, uint32_t(range.address + i)) = range.bytes[i];
      }
    }
  }
  std::vector<uint8_t> Read(uint8_t* base) const {
    std::vector<uint8_t> bytes;
    for (const Range& range : ranges) {
      for (size_t i = 0; i < range.bytes.size(); ++i) {
        bytes.push_back(*Host(base, uint32_t(range.address + i)));
      }
    }
    return bytes;
  }
};

// ---- ParamEQ -------------------------------------------------------------
//
// Unit layout (site/findings/threads.toml, "FMOD ParamEQ"): +20 the FMOD
// system (its output rate at +1188), +36 the active-channel mask (u16, bit c
// for channel c), +312/316/320 the centre, bandwidth and gain in use and
// +324/328/332 the ones last set, per channel c (up to 16) the input history
// x1, x2 at +336 + 8c and the output history y1, y2 at +464 + 8c, and the
// coefficients a0, a1, a2, b0, b1, b2 at +592.

constexpr int kParamEqMaxChannels = 16;

bool ParamEqBody(PPCContext& ctx, uint8_t* base, uint32_t unit) {
  const uint32_t in = ctx.r4.u32, out = ctx.r5.u32, frames = ctx.r6.u32;
  const int32_t channels = ctx.r7.s32;
  if (in == 0) {
    ctx.r3.u64 = 0;
    return true;
  }
  if (unit == 0 || channels <= 0 || channels > kParamEqMaxChannels) return false;
  const size_t samples = size_t(frames) * size_t(channels);
  if (samples > kMaxSamples || !OneRegion(in, samples) || !OneRegion(out, samples)) return false;
  // The filter reads a frame before writing it, so the buffers must be the
  // same or apart.
  if (in != out && in < out + samples * 4 && out < in + samples * 4) return false;
  const uint32_t system = LoadU32(base, unit + 20);
  if (system == 0) return false;
  const int32_t rate = static_cast<int32_t>(LoadU32(base, system + 1188));
  if (rate <= 0) return false;
  const uint8_t* x = Host(base, in);
  uint8_t* y = Host(base, out);

  const uint32_t mask = LoadU16(base, unit + 36) & ((1u << channels) - 1u);
  if (mask == 0) {
    // No active channel: the input passes through.
    if (x != y) std::memmove(y, x, samples * 4);
    ctx.r3.u64 = 0;
    return true;
  }

  // Parameters set since the last block take effect now, and the stored
  // coefficients follow them so the translated unit stays consistent.
  float centre = LoadF32(base, unit + 312), bandwidth = LoadF32(base, unit + 316),
        gain = LoadF32(base, unit + 320);
  const float new_centre = LoadF32(base, unit + 324), new_bandwidth = LoadF32(base, unit + 328),
              new_gain = LoadF32(base, unit + 332);
  const bool changed = !(centre == new_centre && bandwidth == new_bandwidth && gain == new_gain);
  if (changed) {
    centre = new_centre;
    bandwidth = new_bandwidth;
    gain = new_gain;
  }
  // FMOD keeps these positive; anything else is left to the original.
  if (!(centre > 0.0f && bandwidth > 0.0f && gain > 0.0f) || !std::isfinite(centre) ||
      !std::isfinite(bandwidth) || !std::isfinite(gain)) {
    return false;
  }
  const dsp::PeakingEq eq = dsp::DesignPeakingEqBoth(centre, bandwidth, gain, rate);
  if (changed) {
    StoreF32(base, unit + 312, centre);
    StoreF32(base, unit + 316, bandwidth);
    StoreF32(base, unit + 320, gain);
    const dsp::Biquad& c = eq.raw;
    const float stored[6] = {c.a0, c.a1, c.a2, c.b0, c.b1, c.b2};
    for (int i = 0; i < 6; ++i) StoreF32(base, unit + 592 + 4 * i, stored[i]);
  }

  // Histories: x[n-1], x[n-2] at +336 + 8c, y[n-1], y[n-2] at +464 + 8c.
  dsp::BiquadHistory h[kParamEqMaxChannels];
  for (int ch = 0; ch < channels; ++ch) {
    h[ch] = {LoadF32(base, unit + 336 + 8 * ch), LoadF32(base, unit + 340 + 8 * ch),
             LoadF32(base, unit + 464 + 8 * ch), LoadF32(base, unit + 468 + 8 * ch)};
  }
  const uint32_t all = (1u << channels) - 1u;
  bool fused = mask == all;
  if (fused) {
    switch (channels) {
      case 1: dsp::RunBiquadsBE<1>(eq.norm, h, x, y, frames); break;
      case 2: dsp::RunBiquadsBE<2>(eq.norm, h, x, y, frames); break;
      case 6: dsp::RunBiquadsBE<6>(eq.norm, h, x, y, frames); break;
      case 8: dsp::RunBiquadsBE<8>(eq.norm, h, x, y, frames); break;
      default: fused = false;
    }
  }
  if (!fused) {
    for (int ch = 0; ch < channels; ++ch) {
      if (mask & (1u << ch)) {
        dsp::RunBiquadBE(eq.norm, h[ch], x + 4 * ch, y + 4 * ch, frames, size_t(channels));
      } else if (x != y) {
        for (uint32_t f = 0; f < frames; ++f) {
          std::memcpy(y + 4 * (size_t(f) * channels + ch), x + 4 * (size_t(f) * channels + ch), 4);
        }
      }
    }
  }
  for (int ch = 0; ch < channels; ++ch) {
    if (!(mask & (1u << ch))) continue;
    StoreF32(base, unit + 336 + 8 * ch, h[ch].x1);
    StoreF32(base, unit + 340 + 8 * ch, h[ch].x2);
    StoreF32(base, unit + 464 + 8 * ch, h[ch].y1);
    StoreF32(base, unit + 468 + 8 * ch, h[ch].y2);
  }
  ctx.r3.u64 = 0;
  return true;
}

uint32_t ThunkUnit(const PPCContext& ctx) { return ctx.r3.u32 ? ctx.r3.u32 - 28 : 0; }

VerifyTotals parameq_totals;

void VerifyParamEq(Replacement& replacement, PPCFunc* original, PPCContext& ctx, uint8_t* base,
                   bool thunk) {
  const PPCContext input = ctx;
  const uint32_t unit = thunk ? ThunkUnit(ctx) : ctx.r3.u32;
  const size_t samples = size_t(ctx.r6.u32) * size_t(std::max(ctx.r7.s32, 0));
  if (unit == 0 || ctx.r4.u32 == 0 || samples == 0 || samples > kMaxSamples) {
    original(ctx, base);
    return;
  }
  // Restore what either run writes: the parameters in use (+312..324), the
  // histories (+336..616) and the output. The targets at +324..332 and the
  // mask at +36 belong to the game thread's setters; restoring them could
  // undo a write made between the two runs, so they are only watched.
  Snapshot saved, watched;
  saved.Add(base, unit + 312, 12);
  saved.Add(base, unit + 336, 616 - 336);
  saved.Add(base, ctx.r5.u32, samples * 4);
  watched.Add(base, unit + 36, 2);
  watched.Add(base, unit + 324, 12);
  const std::vector<uint8_t> inputs_before = watched.Read(base);
  const float centre = LoadF32(base, unit + 312), bandwidth = LoadF32(base, unit + 316),
              gain = LoadF32(base, unit + 320);

  PPCContext trial = input;
  const bool handled = ParamEqBody(trial, base, unit);
  std::vector<float> native(samples), reference(samples);
  if (handled) LoadFloats(base, input.r5.u32, samples, native.data());
  saved.Restore(base);
  original(ctx, base);
  if (!handled) {
    replacement.stats.declined.fetch_add(1, std::memory_order_relaxed);
    return;
  }
  LoadFloats(base, input.r5.u32, samples, reference.data());
  VerifyResult r{};
  r.snr = dsp::SnrDb(reference.data(), native.data(), samples);
  r.error_rms = dsp::ErrorRms(reference.data(), native.data(), samples);
  r.samples = samples;
  r.state_differs = trial.r3.u32 != ctx.r3.u32;
  if (r.state_differs) r.state_detail = fmt::format("r3 {:08X}/{:08X}", trial.r3.u32, ctx.r3.u32);
  r.raced = watched.Read(base) != inputs_before;
  const uint32_t system = LoadU32(base, unit + 20);
  r.detail = fmt::format(
      "centre {} bw {} gain {} -> {} {} {} mask {:04X} rate {}", centre, bandwidth, gain,
      LoadF32(base, unit + 324), LoadF32(base, unit + 328), LoadF32(base, unit + 332),
      LoadU16(base, unit + 36),
      system ? static_cast<int32_t>(LoadU32(base, system + 1188)) : 0);
  RecordVerify(replacement, parameq_totals, r, input);
}

std::atomic<uint64_t> parameq_verify_calls{0};
bool ParamEqEnabled() { return REXCVAR_GET(pinyon_shift_fmod_native_parameq); }

extern Replacement parameq_coefficients_check;
extern Replacement parameq_body_replacement;
extern Replacement parameq_thunk_replacement;

bool ParamEqContractCheck(uint8_t* base) {
  return GuestCodeMatches(parameq_body_replacement, base) &&
         GuestCodeMatches(parameq_coefficients_check, base);
}
std::atomic<int> parameq_contract{0};
bool ParamEqContractMatches(uint8_t* base) {
  return ContractMatches(parameq_contract, ParamEqContractCheck, base);
}

bool ParamEqThunkNative(PPCContext& ctx, uint8_t* base) {
  if (!ParamEqContractMatches(base)) return false;
  if (VerifyDue(parameq_verify_calls)) {
    VerifyParamEq(parameq_thunk_replacement, &FMOD_DSP_IMP(82FC7A00, 83088AB0), ctx, base, true);
    return true;
  }
  return ParamEqBody(ctx, base, ThunkUnit(ctx));
}
void ParamEqThunkVerify(Replacement& self, PPCFunc* original, PPCContext& ctx, uint8_t* base) {
  if (!ParamEqContractMatches(base)) {
    self.stats.declined.fetch_add(1, std::memory_order_relaxed);
    original(ctx, base);
    return;
  }
  VerifyParamEq(self, original, ctx, base, true);
}

Replacement parameq_coefficients_check = {
    .name = "fmod.parameq_coefficients",
    .guest_address = kV4 ? 0x83087C80u : 0x82FC6BD0u,
    .guest_code_size = 240,
    .guest_code_fnv1a = kV4 ? 0x8E5A1EA04238AB0Full : 0x49C9CADDD3623031ull,
    .returns = kReturnR3,
    .enabled = Never,
    .native = Decline,
    .written = nullptr,
};
Declared parameq_coefficients_declared{parameq_coefficients_check};

Replacement parameq_thunk_replacement = {
    .name = "fmod.parameq_read_thunk",
    .guest_address = kV4 ? 0x83088AB0u : 0x82FC7A00u,
    .guest_code_size = 20,
    .guest_code_fnv1a = 0x2832A425674E15A3ull,
    .returns = kReturnR3,
    .enabled = ParamEqEnabled,
    .native = ParamEqThunkNative,
    .written = nullptr,
    .verify = ParamEqThunkVerify,
};

// The body the twin stands in for, checked with the thunk (its only caller).
Replacement parameq_body_replacement = {
    .name = "fmod.parameq_read",
    .guest_address = kV4 ? 0x83087D70u : 0x82FC6CC0u,
    .guest_code_size = 3008,
    .guest_code_fnv1a = kV4 ? 0xB723CD3075B540CCull : 0x49015048DA280D68ull,
    .returns = kReturnR3,
    .enabled = Never,
    .native = Decline,
    .written = nullptr,
};
Declared parameq_body_declared{parameq_body_replacement};

}  // namespace
}  // namespace pinyon_shift::guest_native

PINYON_GUEST_NATIVE(82FC7A00, 83088AB0, ::pinyon_shift::guest_native::parameq_thunk_replacement)
