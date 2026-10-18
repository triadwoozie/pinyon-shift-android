#include "auto_start.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>

#include <rex/cvar.h>
#include <rex/input/input.h>

#include "pinyon_shift_diagnostics.h"

REXCVAR_DEFINE_BOOL(pinyon_shift_auto_start_single_player, false, "Pinyon Shift",
                    "With a profile on disk, pass Press Start and SINGLE PLAYER at boot and "
                    "load straight into the career");

namespace pinyon_shift::auto_start {

namespace {

using namespace std::chrono_literals;
using rex::X_RESULT;
using rex::X_STATUS;
using rex::input::DeviceId;

constexpr DeviceId kAutoStartDevice = static_cast<DeviceId>(0x41535450);  // ASTP

// The files that mark each screen, from a file-open trace of the title flow
// on a played save: the title loop starts with Press Start, the profile is
// read once Press Start is accepted (its last read is the thumbnails
// folder, after which SINGLE PLAYER is shown), and the world's route and
// navigation files open once SINGLE PLAYER is chosen.
enum Mark : unsigned {
  kTitle = 1u << 0,
  kProfileRead = 1u << 1,
  kProfileDone = 1u << 2,
  kWorld = 1u << 3,
};

std::atomic<bool> g_armed{};
std::atomic<bool> g_pressing{};
std::mutex g_mutex;
std::condition_variable g_changed;
unsigned g_marks = 0;

bool WaitFor(unsigned marks, std::chrono::milliseconds timeout) {
  std::unique_lock lock(g_mutex);
  return g_changed.wait_for(lock, timeout, [&] { return (g_marks & marks) != 0; });
}

bool Seen(unsigned marks) {
  std::lock_guard lock(g_mutex);
  return (g_marks & marks) != 0;
}

void PressA() {
  g_pressing.store(true, std::memory_order_release);
  std::this_thread::sleep_for(150ms);
  g_pressing.store(false, std::memory_order_release);
}

void Stop(const char* outcome) {
  diagnostics::RecordEvent("auto_start.end", {{"outcome", outcome}});
}

// Presses A until one of `confirm` is seen, at most `tries` times.
bool PressUntil(unsigned confirm, int tries, std::chrono::milliseconds wait, const char* step) {
  for (int attempt = 1; attempt <= tries; ++attempt) {
    if (Seen(confirm)) {
      return true;
    }
    PressA();
    diagnostics::RecordEvent("auto_start.press",
                             {{"step", step}, {"attempt", std::to_string(attempt)}});
    if (WaitFor(confirm, wait)) {
      return true;
    }
  }
  return Seen(confirm);
}

void Run() {
  if (!WaitFor(kTitle | kProfileRead | kWorld, 180s)) {
    return Stop("no_title");
  }
  if (!Seen(kProfileRead | kWorld)) {
    // The title takes input a moment after its loop starts.
    std::this_thread::sleep_for(1500ms);
    if (!PressUntil(kProfileRead | kWorld, 6, 2500ms, "press_start")) {
      return Stop("press_start_not_taken");
    }
  }
  if (!Seen(kWorld)) {
    if (!WaitFor(kProfileDone | kWorld, 15s)) {
      // The profile read stopped short: a prompt or a missing save is
      // waiting for the player.
      return Stop("profile_incomplete");
    }
    std::this_thread::sleep_for(1500ms);
    if (!PressUntil(kWorld, 2, 4000ms, "single_player")) {
      return Stop("single_player_not_taken");
    }
  }
  Stop("loading");
}

bool HasProfile(const std::filesystem::path& user_root) {
  std::error_code error;
  std::filesystem::recursive_directory_iterator it(
      user_root, std::filesystem::directory_options::skip_permission_denied, error);
  for (int visited = 0; !error && it != std::filesystem::recursive_directory_iterator() &&
                        visited < 4096;
       it.increment(error), ++visited) {
    if (it.depth() > 4) {
      it.disable_recursion_pending();
      continue;
    }
    std::string name = it->path().filename().string();
    for (char& c : name) {
      c = char(c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c);
    }
    if (name == "forzaprofile" && it->is_regular_file(error)) {
      return true;
    }
  }
  return false;
}

class AutoStartDriver final : public rex::input::InputDriver {
 public:
  AutoStartDriver() : InputDriver(nullptr, 0) {}
  X_STATUS Setup() override { return X_STATUS_SUCCESS; }
  void EnumerateDevices(std::vector<rex::input::DeviceInfo>& out) override {
    rex::input::DeviceInfo info;
    info.id = kAutoStartDevice;
    info.name = "Start in single player";
    info.synthetic = true;
    out.push_back(std::move(info));
  }
  X_RESULT GetDeviceState(DeviceId id, rex::input::X_INPUT_STATE* out) override {
    if (id != kAutoStartDevice) {
      return X_ERROR_DEVICE_NOT_CONNECTED;
    }
    if (out) {
      *out = {};
      if (g_pressing.load(std::memory_order_acquire)) {
        out->gamepad.buttons = rex::input::X_INPUT_GAMEPAD_A;
      }
      out->packet_number = ++packet_;
    }
    return X_ERROR_SUCCESS;
  }
  X_RESULT GetDeviceCapabilities(DeviceId id, uint32_t,
                                 rex::input::X_INPUT_CAPABILITIES* out) override {
    if (id != kAutoStartDevice) {
      return X_ERROR_DEVICE_NOT_CONNECTED;
    }
    if (out) {
      *out = {};
      out->type = 1;
      out->sub_type = 1;
      out->gamepad.buttons = UINT16_MAX;
    }
    return X_ERROR_SUCCESS;
  }
  X_RESULT SetDeviceVibration(DeviceId id, rex::input::X_INPUT_VIBRATION*) override {
    return id == kAutoStartDevice ? X_ERROR_SUCCESS : X_ERROR_DEVICE_NOT_CONNECTED;
  }
  X_RESULT GetDeviceKeystroke(DeviceId id, uint32_t, rex::input::X_INPUT_KEYSTROKE*) override {
    return id == kAutoStartDevice ? X_ERROR_EMPTY : X_ERROR_DEVICE_NOT_CONNECTED;
  }

 private:
  uint32_t packet_ = 0;
};

}  // namespace

bool Configure(const std::filesystem::path& user_root) {
  if (!REXCVAR_GET(pinyon_shift_auto_start_single_player)) {
    return false;
  }
  // A first launch creates its profile through the menus: leave those to
  // the player.
  if (!HasProfile(user_root)) {
    Stop("no_profile");
    return false;
  }
  if (g_armed.exchange(true)) {
    return true;
  }
  diagnostics::RecordEvent("auto_start.armed", {});
  std::thread(Run).detach();
  return true;
}

bool Armed() { return g_armed.load(std::memory_order_acquire); }

std::unique_ptr<rex::input::InputDriver> CreateDriver() {
  return std::make_unique<AutoStartDriver>();
}

void ObserveFileOpened(std::string_view path) {
  if (!g_armed.load(std::memory_order_acquire)) {
    return;
  }
  unsigned mark = 0;
  if (path.ends_with("videos\\pressstart.wmv")) {
    mark = kTitle;
  } else if (path.ends_with(":\\forzaprofile")) {
    mark = kProfileRead;
  } else if (path.ends_with(":\\thumbnails\\")) {
    mark = kProfileDone;
  } else if (path.find("\\trackroute") != std::string_view::npos ||
             path.ends_with("\\colorado.nav")) {
    mark = kWorld;
  }
  if (!mark) {
    return;
  }
  {
    std::lock_guard lock(g_mutex);
    if (g_marks & mark) {
      return;
    }
    g_marks |= mark;
  }
  g_changed.notify_all();
}

}  // namespace pinyon_shift::auto_start
