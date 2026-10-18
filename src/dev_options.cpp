#include "dev_options.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>

#include <rex/cvar.h>
#include <rex/memory.h>
#include <rex/system/kernel_state.h>

#include "fh1_guest_address.h"
#include "pinyon_shift_diagnostics.h"

// Off by default: each restores the game's own value when turned off.
REXCVAR_DEFINE_BOOL(pinyon_shift_skip_voiceover, false, "Pinyon Shift",
                    "Let A close a dialog before its voice-over ends (the allowskipvoiceover "
                    "developer option)");
REXCVAR_DEFINE_BOOL(pinyon_shift_no_attract_video, false, "Pinyon Shift",
                    "Do not replay the intro movie on an idle title screen (the noattractvideo "
                    "developer option)");
REXCVAR_DEFINE_BOOL(pinyon_shift_no_auto_reset, false, "Pinyon Shift",
                    "Do not reset the car to the road automatically when it leaves it (the "
                    "NoHandOfGodReset developer option)");
REXCVAR_DEFINE_BOOL(pinyon_shift_photo_camera_through_walls, false, "Pinyon Shift",
                    "Let the photo mode camera pass through walls and terrain (the "
                    "disablephotomodecollisiondetection developer option)");

namespace pinyon_shift::dev_options {

namespace {

struct Option {
  const char* cvar;
  const char* option;
  // Byte offset in the options object; 0 where the build has none.
  uint32_t offset;
};

// Base-disc offsets, from the option visitor sub_824F8150 (each name's
// matcher call receives this + offset). nocameravibration and hidepoipins
// are parsed but no guest code reads them, and skiptutorials gates only the
// Kinect showroom tutorial, so they are not offered; neither is
// allowhighzoomaperture (+4920), which no guest code reads (#488).
#if defined(PINYON_SHIFT_TITLE_UPDATE_V4) && PINYON_SHIFT_TITLE_UPDATE_V4
constexpr std::array<Option, 4> kOptionList = {{
#include "dev_options_v4.inc"
}};
#else
constexpr std::array<Option, 4> kOptionList = {{
    {"pinyon_shift_skip_voiceover", "allowskipvoiceover", 4924},
    {"pinyon_shift_no_attract_video", "noattractvideo", 4222},
    {"pinyon_shift_no_auto_reset", "NoHandOfGodReset", 4937},
    {"pinyon_shift_photo_camera_through_walls", "disablephotomodecollisiondetection", 4921},
}};
#endif

struct State {
  // The game's own value, read before the first write; -1 until then.
  int original = -1;
  bool written = false;
};

std::mutex g_mutex;
std::array<State, kOptionList.size()> g_state;

// In kOptionList's order.
std::array<bool, kOptionList.size()> Wanted() {
  return {REXCVAR_GET(pinyon_shift_skip_voiceover), REXCVAR_GET(pinyon_shift_no_attract_video),
          REXCVAR_GET(pinyon_shift_no_auto_reset),
          REXCVAR_GET(pinyon_shift_photo_camera_through_walls)};
}

uint8_t* Guest(uint32_t address) {
  auto* base = rex::system::kernel_state()->memory()->virtual_membase();
  return rex::memory::GuestPtr<uint8_t*>(base, address);
}

uint32_t Load32(uint32_t address) {
  auto* base = rex::system::kernel_state()->memory()->virtual_membase();
  return static_cast<uint32_t>(*rex::memory::GuestPtr<rex::be_u32*>(base, address));
}

}  // namespace

void Update() {
  if (!rex::system::kernel_state()) return;
  const auto wanted_list = Wanted();
  static std::atomic<bool> any_written{false};
  const bool any_wanted =
      std::find(wanted_list.begin(), wanted_list.end(), true) != wanted_list.end();
  if (!any_wanted && !any_written.load(std::memory_order_relaxed)) return;
  std::lock_guard lock(g_mutex);
  // Before the constructor runs it would overwrite our bytes with defaults.
  // The launch-options singleton; its vtable word tells the constructor has
  // run (site/findings/systems.toml, launch-options object).
  const uint32_t object = FH1_ADDR(0x832E29A0u);
  if (Load32(object) != FH1_ADDR(0x8200E724u)) return;
  for (size_t i = 0; i < kOptionList.size(); ++i) {
    const Option& option = kOptionList[i];
    if (option.offset == 0) continue;
    State& state = g_state[i];
    uint8_t* flag = Guest(object + option.offset);
    const bool wanted = wanted_list[i];
    if (wanted) {
      if (state.original < 0) state.original = *flag;
      if (*flag != 1) {
        *flag = 1;
        if (!state.written) {
          diagnostics::RecordEvent("dev_option.set",
                                   {{"option", option.option}, {"value", "1"},
                                    {"original", std::to_string(state.original)}});
        }
      }
      state.written = true;
      any_written.store(true, std::memory_order_relaxed);
    } else if (state.written) {
      *flag = uint8_t(state.original < 0 ? 0 : state.original);
      state.written = false;
      diagnostics::RecordEvent("dev_option.set",
                               {{"option", option.option},
                                {"value", std::to_string(int(*flag))}});
    }
  }
}

}  // namespace pinyon_shift::dev_options
