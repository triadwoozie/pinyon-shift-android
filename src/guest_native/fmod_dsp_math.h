#pragma once

// Host math of the native FMOD ParamEQ twin (NEAR_NATIVE_RESEARCH NN-4.3),
// kept free of guest state so tools/fmod-dsp-test can check it on its own.
//
// It comes from a published design and the contract in site/findings
// (threads.toml "FMOD ParamEQ (six-channel biquad)"), never from the
// executable: the peaking-EQ biquad of R. Bristow-Johnson's "Cookbook
// formulae for audio EQ biquad filter coefficients", in direct form I.

#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace pinyon_shift::guest_native::fmod_dsp {

// ---- Peaking EQ -----------------------------------------------------------

// Direct-form coefficients before normalisation: y = (b.x - a1 y1 - a2 y2) / a0.
struct Biquad {
  float b0, b1, b2, a0, a1, a2;
};

// The same filter divided through by a0: y = b.x - a1 y1 - a2 y2.
struct BiquadNorm {
  float b0, b1, b2, a1, a2;
};

struct PeakingEq {
  Biquad raw;       // as the unit stores them
  BiquadNorm norm;  // as the twin runs them (normalised in double)
};

// The cookbook's peaking EQ for FMOD's ParamEQ parameters: the centre in Hz,
// the bandwidth used as 1/Q and the gain used as the cookbook's A (so the
// centre is boosted or cut by gain squared).
inline PeakingEq DesignPeakingEqBoth(double centre, double bandwidth, double gain, double rate) {
  constexpr double kPi = 3.14159265358979323846;
  const double w0 = 2.0 * kPi * centre / rate;
  const double alpha = std::sin(w0) * bandwidth / 2.0;  // sin(w0) / (2 Q)
  const double cos_w0 = std::cos(w0);
  const double b0 = 1.0 + alpha * gain, b1 = -2.0 * cos_w0, b2 = 1.0 - alpha * gain;
  const double a0 = 1.0 + alpha / gain, a1 = -2.0 * cos_w0, a2 = 1.0 - alpha / gain;
  return {{float(b0), float(b1), float(b2), float(a0), float(a1), float(a2)},
          {float(b0 / a0), float(b1 / a0), float(b2 / a0), float(a1 / a0), float(a2 / a0)}};
}
inline Biquad DesignPeakingEq(double centre, double bandwidth, double gain, double rate) {
  return DesignPeakingEqBoth(centre, bandwidth, gain, rate).raw;
}

struct BiquadHistory {
  float x1, x2, y1, y2;
};

// Filters `frames` samples of one channel, `stride` floats apart.
inline void RunBiquad(const Biquad& c, BiquadHistory& h, const float* in, float* out,
                      size_t frames, size_t stride) {
  const float inv_a0 = 1.0f / c.a0;
  float x1 = h.x1, x2 = h.x2, y1 = h.y1, y2 = h.y2;
  for (size_t f = 0; f < frames; ++f) {
    const float x0 = in[f * stride];
    const float y0 = (c.b0 * x0 + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2) * inv_a0;
    out[f * stride] = y0;
    x2 = x1;
    x1 = x0;
    y2 = y1;
    y1 = y0;
  }
  // Keep a decayed tail out of the subnormal range (about -400 dB).
  auto flush = [](float v) { return std::fabs(v) < 1e-20f ? 0.0f : v; };
  h = {flush(x1), flush(x2), flush(y1), flush(y2)};
}

// ---- Big-endian float buffers (guest memory) --------------------------------

inline float LoadBE(const uint8_t* p) {
  uint32_t bits;
  std::memcpy(&bits, p, 4);
  return std::bit_cast<float>(std::byteswap(bits));
}
inline void StoreBE(uint8_t* p, float value) {
  const uint32_t bits = std::byteswap(std::bit_cast<uint32_t>(value));
  std::memcpy(p, &bits, 4);
}

// Keeps a decayed filter tail out of the subnormal range (about -400 dB).
inline float FlushTiny(float v) { return std::fabs(v) < 1e-20f ? 0.0f : v; }

// All C channels of an interleaved big-endian block through one filter, frame
// by frame so the channels' recursions overlap. `in` may equal `out`.
template <int C>
inline void RunBiquadsBE(const BiquadNorm& c, BiquadHistory* h, const uint8_t* in, uint8_t* out,
                         size_t frames) {
  float x1[C], x2[C], y1[C], y2[C];
  for (int ch = 0; ch < C; ++ch) x1[ch] = h[ch].x1, x2[ch] = h[ch].x2, y1[ch] = h[ch].y1,
                                 y2[ch] = h[ch].y2;
  for (size_t f = 0; f < frames; ++f) {
    float x0[C];
    for (int ch = 0; ch < C; ++ch) x0[ch] = LoadBE(in + 4 * (f * C + ch));
    for (int ch = 0; ch < C; ++ch) {
      const float feed = c.b0 * x0[ch] + c.b1 * x1[ch] + c.b2 * x2[ch] - c.a2 * y2[ch];
      const float y0 = feed - c.a1 * y1[ch];
      x2[ch] = x1[ch], x1[ch] = x0[ch], y2[ch] = y1[ch], y1[ch] = y0;
    }
    for (int ch = 0; ch < C; ++ch) StoreBE(out + 4 * (f * C + ch), y1[ch]);
  }
  for (int ch = 0; ch < C; ++ch) {
    h[ch] = {FlushTiny(x1[ch]), FlushTiny(x2[ch]), FlushTiny(y1[ch]), FlushTiny(y2[ch])};
  }
}

// One channel, `stride` channels apart (a partly active mask).
inline void RunBiquadBE(const BiquadNorm& c, BiquadHistory& h, const uint8_t* in, uint8_t* out,
                        size_t frames, size_t stride) {
  float x1 = h.x1, x2 = h.x2, y1 = h.y1, y2 = h.y2;
  for (size_t f = 0; f < frames; ++f) {
    const float x0 = LoadBE(in + 4 * f * stride);
    const float y0 = c.b0 * x0 + c.b1 * x1 + c.b2 * x2 - c.a2 * y2 - c.a1 * y1;
    StoreBE(out + 4 * f * stride, y0);
    x2 = x1, x1 = x0, y2 = y1, y1 = y0;
  }
  h = {FlushTiny(x1), FlushTiny(x2), FlushTiny(y1), FlushTiny(y2)};
}

// ---- Verification ---------------------------------------------------------

// Signal-to-error ratio of `test` against `reference` in dB (infinite when equal).
inline double SnrDb(const float* reference, const float* test, size_t count) {
  double signal = 0.0, error = 0.0;
  for (size_t i = 0; i < count; ++i) {
    const double r = reference[i], d = double(test[i]) - r;
    signal += r * r;
    error += d * d;
  }
  if (error == 0.0) return INFINITY;
  if (signal == 0.0) return -INFINITY;
  return 10.0 * std::log10(signal / error);
}

// Root-mean-square of `test - reference`.
inline double ErrorRms(const float* reference, const float* test, size_t count) {
  if (count == 0) return 0.0;
  double error = 0.0;
  for (size_t i = 0; i < count; ++i) {
    const double d = double(test[i]) - double(reference[i]);
    error += d * d;
  }
  return std::sqrt(error / double(count));
}

}  // namespace pinyon_shift::guest_native::fmod_dsp
