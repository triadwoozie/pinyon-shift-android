// Title waits that spin (#431).
//
// The render thread's frame function (sub_8259F3E8, v4 sub_826164D0) returns
// early when the simulation has no new frame for it, after rebuilding the
// gamma ramp, reading the time and calling SleepEx(0, TRUE) (the wrapper
// sub_82A6D7D8, v4 sub_82B2A848). The host returns from a zero alertable sleep
// at once, so while the render thread waits for the simulation it runs that
// path back to back: on the Odin it then retired six times the instructions
// of a run without the wait, mostly clock reads, guest memcpy and the gamma
// path. With pinyon_shift_render_idle_sleep_us above zero (200 by default), a
// sleep from that call site first waits that long on a host timer (Windows'
// own alertable SleepEx cannot sleep under a millisecond), then runs the
// original, which delivers any APC that arrived meanwhile, so the title's
// view of the call is unchanged. In-run A/B on the Odin (fh1-race-sustained, 30 fps limit):
// the render thread's CPU fell from 477-839 to 242-253 ms a second, other
// threads unchanged, and frames over 40 ms from 8-13 to 3-4 a run.
//
// The replacement never handles a call itself: it always returns false, so
// guest_native.summary counts every call of the wrapper as declined.

#include <chrono>
#include <rex/cs_scheduler.h>
#include <cstdint>

#include <rex/cvar.h>
#include <rex/ppc/context.h>
#include <rex/thread.h>

#include "fh1_guest_address.h"
#include "guest_native/guest_native.h"

REXCVAR_DEFINE_UINT32(pinyon_shift_render_idle_sleep_us, 200, "Pinyon Shift",
                      "Microseconds the render thread sleeps when it has no new frame to "
                      "draw, instead of yielding and polling again (#431; 0 = the title's own "
                      "spin)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

namespace pinyon_shift::guest_native {
namespace {

// Return address of the render frame's idle SleepEx(0, TRUE).
constexpr uint32_t kRenderIdleReturn =
    pinyon_shift::fh1::kTitleUpdateV4 ? 0x82616CD4u : 0x8259FBECu;

bool SleepIdle(PPCContext& ctx, uint8_t*) {
  const uint32_t sleep_us = REXCVAR_GET(pinyon_shift_render_idle_sleep_us);
  if (sleep_us && ctx.r3.u32 == 0 && static_cast<uint32_t>(ctx.lr) == kRenderIdleReturn) {
    if (rex::cs_scheduler::InActor()) rex::cs_scheduler::Sleep(uint64_t(sleep_us)*1000);
    else rex::thread::WaitUntil(std::chrono::steady_clock::now() + std::chrono::microseconds(sleep_us));
  }
  return false;
}

bool SleepEnabled() { return REXCVAR_GET(pinyon_shift_render_idle_sleep_us) != 0; }

Replacement sleep_replacement = {
    .name = "title.sleep_ex",
    .guest_address = pinyon_shift::fh1::kTitleUpdateV4 ? 0x82B2A848u : 0x82A6D7D8u,
    .guest_code_size = 56,
    .guest_code_fnv1a = 0x55426CA58921F8BEull,
    .returns = kReturnR3,
    .enabled = SleepEnabled,
    .native = SleepIdle,
    .written = nullptr,
};

}  // namespace
}  // namespace pinyon_shift::guest_native

PINYON_GUEST_NATIVE(82A6D7D8, 82B2A848, ::pinyon_shift::guest_native::sleep_replacement)
