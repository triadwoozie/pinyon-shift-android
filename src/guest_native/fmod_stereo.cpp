// FMOD mixes in stereo instead of 5.1 (NEAR_NATIVE_RESEARCH NN-4.2).
//
// The title keeps FMOD Ex's default speaker mode, 5.1, so the mixer and its
// effects (ParamEQ, echo, the high/low-pass unit) run six channels, and the
// host then folds them to stereo for a phone or handheld. FMOD's public
// speaker-mode setter (sub_82F86298, v4 sub_83047350) is never called; it is
// allowed only before init. This replaces the title's System::init wrapper
// (sub_82F86498, v4 sub_83047550): when pinyon_shift_fmod_stereo is on it
// first calls the setter with stereo (FMOD_SPEAKERMODE_STEREO, 2) on the same
// system handle, then runs the original init, which builds the output voices
// with two channels. The host's fold then passes the two channels through.
// site/findings/audio.toml, "speaker mode and the 5.1 mix".

#include <cstdint>

#include <rex/audio/downmix.h>
#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/ppc/context.h>
#include <rex/ppc/func.h>

#include "fh1_guest_address.h"
#include "guest_native/guest_native.h"
#include "pinyon_shift_diagnostics.h"

// On by default on Android and Linux (the Steam Deck); off on Windows and macOS, where
// 5.1 speaker setups are common.
REXCVAR_DEFINE_BOOL(pinyon_shift_fmod_stereo, REX_PLATFORM_LINUX, "Pinyon Shift",
                    "Mix the game's audio in stereo instead of 5.1 (FMOD's speaker mode, set "
                    "before it starts; NN-4.2). Saves mixer CPU on stereo devices; a 5.1 "
                    "speaker setup loses its surround channels")
    .lifecycle(rex::cvar::Lifecycle::kRequiresRestart);

extern "C" REX_FUNC(PINYON_GUEST_NATIVE_JOIN(__imp__sub_, PINYON_GUEST_NATIVE_PICK(82F86298,
                                                                                  83047350)));

namespace pinyon_shift::guest_native {
namespace {

constexpr uint32_t kSpeakerModeStereo = 2;

bool InitInStereo(PPCContext& ctx, uint8_t* base) {
  // The init arguments (handle, max channels, flags, extra driver data) and
  // the link register, kept across the setter call.
  const uint64_t r3 = ctx.r3.u64, r4 = ctx.r4.u64, r5 = ctx.r5.u64, r6 = ctx.r6.u64;
  const uint64_t lr = ctx.lr;
  ctx.r4.u64 = kSpeakerModeStereo;
  PINYON_GUEST_NATIVE_JOIN(__imp__sub_, PINYON_GUEST_NATIVE_PICK(82F86298, 83047350))(ctx, base);
  const uint32_t result = ctx.r3.u32;
  ctx.r3.u64 = r3;
  ctx.r4.u64 = r4;
  ctx.r5.u64 = r5;
  ctx.r6.u64 = r6;
  ctx.lr = lr;
  diagnostics::RecordEvent("audio.fmod_speaker_mode",
                           {{"mode", "stereo"}, {"result", std::to_string(result)}});
  if (result == 0) {
    // FMOD's output is two channels now: pass them through at unity.
    rex::audio::StereoFold fold = rex::audio::GetStereoFold();
    fold.scale = 1.0f;
    rex::audio::SetStereoFold(fold);
  }
  // Decline, so the original init runs with the arguments restored.
  return false;
}

bool StereoEnabled() { return REXCVAR_GET(pinyon_shift_fmod_stereo); }

Replacement fmod_init_replacement = {
    .name = "fmod.init_stereo",
    .guest_address = pinyon_shift::fh1::kTitleUpdateV4 ? 0x83047550u : 0x82F86498u,
    .guest_code_size = 68,
    .guest_code_fnv1a =
        pinyon_shift::fh1::kTitleUpdateV4 ? 0x9CD8755C4412C3C1ull : 0x7810FB4BF01C5A11ull,
    .returns = kReturnR3,
    .enabled = StereoEnabled,
    .native = InitInStereo,
    .written = nullptr,
};

}  // namespace
}  // namespace pinyon_shift::guest_native

PINYON_GUEST_NATIVE(82F86498, 83047550, ::pinyon_shift::guest_native::fmod_init_replacement)
