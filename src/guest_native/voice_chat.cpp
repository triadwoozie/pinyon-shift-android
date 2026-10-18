// The XDK voice-chat (XHV2) thread's tick (NEAR_NATIVE_RESEARCH NN-4.5).
//
// The voice thread (entry 82463D60, v4 82493658) waits on a 100 Hz periodic
// timer and calls its tick sub_8245DBE0 (v4 sub_8248D438) every period, offline
// too. The tick polls the four user slots for voice packets, headsets and
// sign-in changes; the game plays no voice chat in single player, and the
// Odin spent about 0.16 ms a frame of CPU in the thread. With
// pinyon_shift_voice_chat_idle (the default) the tick does nothing and waits
// 50 ms first, so the thread wakes about 20 times a second instead of 100
// (the periodic timer's signals coalesce meanwhile); it still leaves its loop
// at shutdown, at most 50 ms later. Skipping the tick alone took the thread
// from 0.146 to 0.082 ms a frame on the Odin, in-run.

#include <chrono>
#include <rex/cs_scheduler.h>
#include <cstdint>

#include <rex/cvar.h>
#include <rex/ppc/context.h>
#include <rex/thread.h>

#include "fh1_guest_address.h"
#include "guest_native/guest_native.h"

REXCVAR_DEFINE_BOOL(pinyon_shift_voice_chat_idle, true, "Pinyon Shift",
                    "Skip the voice-chat (XHV2) thread's 100 Hz tick, which polls for voice "
                    "packets and headsets the game never uses offline (NN-4.5)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

namespace pinyon_shift::guest_native {
namespace {

bool SkipTick(PPCContext&, uint8_t*) {
  if (rex::cs_scheduler::InActor()) rex::cs_scheduler::Sleep(50000000);
  else rex::thread::WaitUntil(std::chrono::steady_clock::now() + std::chrono::milliseconds(50));
  return true;
}

bool TickSkipped() { return REXCVAR_GET(pinyon_shift_voice_chat_idle); }

Replacement voice_tick_replacement = {
    .name = "xhv.tick",
    .guest_address = pinyon_shift::fh1::kTitleUpdateV4 ? 0x8248D438u : 0x8245DBE0u,
    .guest_code_size = 336,
    .guest_code_fnv1a =
        pinyon_shift::fh1::kTitleUpdateV4 ? 0xE2657C1E7B4F239Bull : 0xBBF29A6C7800C0E3ull,
    .returns = kReturnNone,
    .enabled = TickSkipped,
    .native = SkipTick,
    .written = nullptr,
};

}  // namespace
}  // namespace pinyon_shift::guest_native

PINYON_GUEST_NATIVE(8245DBE0, 8248D438, ::pinyon_shift::guest_native::voice_tick_replacement)
