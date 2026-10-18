#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace pinyon_shift::telemetry {

// What the host knows about the player's car in one frame (#481): the
// fields read for DualSense effects (site/findings/objects.toml, car
// dynamics). Everything else in the packet stays zero.
struct CarSample {
  bool driving = false;  // a player car exists and the game is in control of it
  float rpm = 0.0f;
  float redline_rpm = 0.0f;
  int gear = 0;  // 0 neutral, -1 reverse, 1-8
  float speed_mps = 0.0f;
  // Longitudinal slip per wheel (FL, FR, RL, RR), 1 at the tyre's peak grip.
  std::array<float, 4> wheel_slip{};
  float throttle = 0.0f;  // 0-1
  float brake = 0.0f;     // 0-1
};

// Forza Motorsport 7's "Data Out" Dash packet: 311 bytes, little endian.
// Telemetry tools (SimHub, dashboards, motion software) read this layout.
constexpr size_t kDashPacketSize = 311;
// FM7 sends neutral as 11 and reverse as 0.
constexpr uint8_t kDashNeutralGear = 11;

std::array<uint8_t, kDashPacketSize> EncodeDash(const CarSample& sample, uint32_t timestamp_ms);

}  // namespace pinyon_shift::telemetry
