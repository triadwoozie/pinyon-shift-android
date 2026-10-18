#include "telemetry/forza_dash.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace pinyon_shift::telemetry {

namespace {

// Field offsets in the FM7 Dash layout: the 232-byte "Sled" block, then the
// dash fields.
constexpr size_t kIsRaceOn = 0;          // s32
constexpr size_t kTimestampMs = 4;       // u32
constexpr size_t kEngineMaxRpm = 8;      // f32
constexpr size_t kCurrentEngineRpm = 16; // f32
constexpr size_t kTireSlipRatio = 84;    // 4 x f32, FL FR RL RR
constexpr size_t kSpeed = 244;           // f32 m/s
constexpr size_t kAccel = 303;           // u8 0-255
constexpr size_t kBrake = 304;           // u8 0-255
constexpr size_t kGear = 307;            // u8

class Writer {
 public:
  explicit Writer(std::array<uint8_t, kDashPacketSize>& out) : out_(out) {}
  void U32(size_t offset, uint32_t value) {
    for (int i = 0; i < 4; ++i) out_[offset + i] = uint8_t(value >> (8 * i));
  }
  void F32(size_t offset, float value) {
    if (!std::isfinite(value)) value = 0.0f;
    uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    U32(offset, bits);
  }
  void U8(size_t offset, uint8_t value) { out_[offset] = value; }

 private:
  std::array<uint8_t, kDashPacketSize>& out_;
};

uint8_t Unit(float value) {
  if (!std::isfinite(value)) return 0;
  return uint8_t(std::lround(std::clamp(value, 0.0f, 1.0f) * 255.0f));
}

}  // namespace

std::array<uint8_t, kDashPacketSize> EncodeDash(const CarSample& sample, uint32_t timestamp_ms) {
  std::array<uint8_t, kDashPacketSize> packet{};
  Writer out(packet);
  out.U32(kTimestampMs, timestamp_ms);
  // Outside a car (menus, loading) the packet says so and carries no data,
  // as the game's own Data Out does.
  if (!sample.driving) return packet;
  out.U32(kIsRaceOn, 1);
  out.F32(kEngineMaxRpm, sample.redline_rpm);
  out.F32(kCurrentEngineRpm, sample.rpm);
  for (size_t wheel = 0; wheel < 4; ++wheel) {
    out.F32(kTireSlipRatio + 4 * wheel, sample.wheel_slip[wheel]);
  }
  out.F32(kSpeed, sample.speed_mps);
  out.U8(kAccel, Unit(sample.throttle));
  out.U8(kBrake, Unit(sample.brake));
  out.U8(kGear, sample.gear < 0   ? uint8_t(0)
                : sample.gear == 0 ? kDashNeutralGear
                                   : uint8_t(std::min(sample.gear, 10)));
  return packet;
}

}  // namespace pinyon_shift::telemetry
