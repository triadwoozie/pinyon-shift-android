// Checks of the NN-4.3 FMOD ParamEQ twin's host math (src/guest_native/fmod_dsp_math.h).
//
// The twin itself is compared with the translated original in the game
// (pinyon_shift_fmod_dsp_verify); this checks their math against independent
// references on synthetic signals: the peaking EQ's frequency response, a
// double-precision filter and the big-endian in-place kernels the twin runs
// on guest memory, plus a host timing. No game code or data.
//
//   .local/toolchain/llvm-20.1.8/bin/clang++.exe -std=c++23 -O2 -Isrc \
//       tools/fmod-dsp-test/fmod_dsp_test.cpp -o .local/fmod-dsp-test.exe
//   .local/fmod-dsp-test.exe

#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <complex>
#include <cstdio>
#include <random>
#include <string>
#include <utility>
#include <vector>

#include "guest_native/fmod_dsp_math.h"

namespace dsp = pinyon_shift::guest_native::fmod_dsp;

namespace {

int failures = 0;

void Check(bool ok, const char* what, double value = 0.0) {
  std::printf("%s %-58s %.6g\n", ok ? "ok  " : "FAIL", what, value);
  if (!ok) ++failures;
}

constexpr double kPi = 3.14159265358979323846;

double Magnitude(const dsp::Biquad& c, double w) {
  const std::complex<double> z1 = std::polar(1.0, -w), z2 = z1 * z1;
  const std::complex<double> num = double(c.b0) + double(c.b1) * z1 + double(c.b2) * z2;
  const std::complex<double> den = double(c.a0) + double(c.a1) * z1 + double(c.a2) * z2;
  return std::abs(num / den);
}

std::vector<float> Noise(size_t n, unsigned seed, float amplitude) {
  std::mt19937 rng(seed);
  std::uniform_real_distribution<float> d(-amplitude, amplitude);
  std::vector<float> v(n);
  for (float& x : v) x = d(rng);
  return v;
}

void PeakingEq() {
  const double rate = 48000;
  {
    const dsp::Biquad c = dsp::DesignPeakingEq(1000, 1.0, 1.0, rate);
    Check(c.b0 == c.a0 && c.b1 == c.a1 && c.b2 == c.a2, "unit gain gives equal numerator and denominator");
  }
  // Float coefficients limit how closely a narrow filter far below 100 Hz
  // follows the design (fc 40, Q 5, gain 0.05 is 2.6 % off at the centre), so
  // that case only checks the float filter against a double one.
  struct Case {
    double centre, bandwidth, gain, min_snr;
    bool response;
  } cases[] = {{1000, 1.0, 2.0, 90, true},  {120, 0.5, 0.25, 90, true},
               {8000, 3.0, 3.0, 90, true},  {15000, 5.0, 1.5, 90, true},
               {40, 0.2, 0.05, 70, false}};
  for (const Case& k : cases) {
    const dsp::Biquad c = dsp::DesignPeakingEq(k.centre, k.bandwidth, k.gain, rate);
    const double w0 = 2 * kPi * k.centre / rate;
    char text[96];
    if (k.response) {
      std::snprintf(text, sizeof(text), "fc %.0f bw %.2f gain %.2f: |H(w0)| = gain^2", k.centre,
                    k.bandwidth, k.gain);
      Check(std::fabs(Magnitude(c, w0) / (k.gain * k.gain) - 1) < 1e-3, text, Magnitude(c, w0));
      std::snprintf(text, sizeof(text), "fc %.0f: |H(0)| = 1 and |H(pi)| = 1", k.centre);
      Check(std::fabs(Magnitude(c, 0) - 1) < 1e-3 && std::fabs(Magnitude(c, kPi) - 1) < 1e-3,
            text);
    }

    // Float filter against a double one with the same coefficients.
    const size_t n = 4096;
    std::vector<float> in = Noise(n, 7, 0.5f), out(n);
    dsp::BiquadHistory h{};
    dsp::RunBiquad(c, h, in.data(), out.data(), n, 1);
    std::vector<double> ref(n);
    double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    for (size_t i = 0; i < n; ++i) {
      const double x0 = in[i];
      const double y0 = (c.b0 * x0 + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2) / c.a0;
      ref[i] = y0;
      x2 = x1, x1 = x0, y2 = y1, y1 = y0;
    }
    std::vector<float> reff(ref.begin(), ref.end());
    std::snprintf(text, sizeof(text), "fc %.0f: float filter vs double, SNR dB > %.0f", k.centre,
                  k.min_snr);
    const double snr = dsp::SnrDb(reff.data(), out.data(), n);
    Check(snr > k.min_snr, text, snr);
  }
  // Interleaved channels and block splits carry the history exactly.
  {
    const dsp::Biquad c = dsp::DesignPeakingEq(500, 1.0, 2.0, rate);
    const size_t frames = 512, channels = 6;
    std::vector<float> in = Noise(frames * channels, 11, 0.3f), whole(in.size()), split(in.size());
    for (size_t ch = 0; ch < channels; ++ch) {
      dsp::BiquadHistory a{}, b{};
      dsp::RunBiquad(c, a, in.data() + ch, whole.data() + ch, frames, channels);
      dsp::RunBiquad(c, b, in.data() + ch, split.data() + ch, 256, channels);
      dsp::RunBiquad(c, b, in.data() + ch + 256 * channels, split.data() + ch + 256 * channels,
                     256, channels);
    }
    Check(whole == split, "two 256-frame blocks equal one 512-frame block (6 channels)");
  }
  // A sine at the centre settles at gain^2.
  {
    const double centre = 2000, gain = 1.5;
    const dsp::Biquad c = dsp::DesignPeakingEq(centre, 1.0, gain, rate);
    const size_t n = 48000;
    std::vector<float> in(n), out(n);
    for (size_t i = 0; i < n; ++i) in[i] = float(0.25 * std::sin(2 * kPi * centre * i / rate));
    dsp::BiquadHistory h{};
    dsp::RunBiquad(c, h, in.data(), out.data(), n, 1);
    double peak = 0;
    for (size_t i = n / 2; i < n; ++i) peak = std::max(peak, double(std::fabs(out[i])));
    Check(std::fabs(peak / 0.25 / (gain * gain) - 1) < 2e-3, "steady sine at the centre gains gain^2",
          peak / 0.25);
  }
}

void Snr() {
  const float a[4] = {1, -1, 1, -1}, b[4] = {1, -1, 1, -1}, c[4] = {1.001f, -1, 1, -1};
  Check(std::isinf(dsp::SnrDb(a, b, 4)) && dsp::SnrDb(a, b, 4) > 0, "equal signals: infinite SNR");
  const double snr = dsp::SnrDb(a, c, 4);
  Check(std::fabs(snr - 10 * std::log10(4 / (0.001 * 0.001))) < 0.1, "SNR of a known error", snr);
}

// ---- Big-endian kernels (the twins run these on guest memory) --------------

std::vector<uint8_t> ToBE(const std::vector<float>& v) {
  std::vector<uint8_t> b(v.size() * 4);
  for (size_t i = 0; i < v.size(); ++i) dsp::StoreBE(b.data() + 4 * i, v[i]);
  return b;
}
std::vector<float> FromBE(const std::vector<uint8_t>& b) {
  std::vector<float> v(b.size() / 4);
  for (size_t i = 0; i < v.size(); ++i) v[i] = dsp::LoadBE(b.data() + 4 * i);
  return v;
}

void Kernels() {
  const auto eq = dsp::DesignPeakingEqBoth(1000, 1.0 / 0.7, 1.6, 48000);
  for (int channels : {1, 2, 6, 8}) {
    const size_t frames = 256;
    const std::vector<float> in = Noise(frames * channels, 11 + channels, 0.5f);
    // Host reference: the raw (a0-scaled) float filter, channel by channel.
    std::vector<float> ref(in.size());
    for (int c = 0; c < channels; ++c) {
      dsp::BiquadHistory h{0.1f, -0.05f, 0.02f, 0.01f};
      dsp::RunBiquad(eq.raw, h, in.data() + c, ref.data() + c, frames, channels);
    }
    std::vector<uint8_t> buf = ToBE(in);
    std::vector<dsp::BiquadHistory> h(channels, dsp::BiquadHistory{0.1f, -0.05f, 0.02f, 0.01f});
    switch (channels) {
      case 1: dsp::RunBiquadsBE<1>(eq.norm, h.data(), buf.data(), buf.data(), frames); break;
      case 2: dsp::RunBiquadsBE<2>(eq.norm, h.data(), buf.data(), buf.data(), frames); break;
      case 6: dsp::RunBiquadsBE<6>(eq.norm, h.data(), buf.data(), buf.data(), frames); break;
      case 8: dsp::RunBiquadsBE<8>(eq.norm, h.data(), buf.data(), buf.data(), frames); break;
    }
    const std::vector<float> got = FromBE(buf);
    const double snr = dsp::SnrDb(ref.data(), got.data(), got.size());
    const std::string text =
        "BE biquad in place, " + std::to_string(channels) + " ch, vs host filter SNR dB > 100";
    Check(snr > 100, text.c_str(), snr);
    // One strided channel gives the same as the fused kernel's channel.
    std::vector<uint8_t> one = ToBE(in);
    dsp::BiquadHistory h1{0.1f, -0.05f, 0.02f, 0.01f};
    dsp::RunBiquadBE(eq.norm, h1, one.data(), one.data(), frames, channels);
    const std::vector<float> got1 = FromBE(one);
    bool same = true;
    for (size_t f = 0; f < frames; ++f) {
      same &= std::fabs(got1[f * channels] - got[f * channels]) <= 1e-6f;
    }
    const std::string text1 =
        "strided single channel matches the fused kernel, " + std::to_string(channels) + " ch";
    Check(same, text1.c_str());
  }
}

// ---- Host timing ----------------------------------------------------------
//
// Per-call nanoseconds of the ParamEQ kernel against the first twin's shape
// (host-order copies, one pass per channel dividing by a0), and of the
// guest-code hash the first twin ran on every call.

template <class F>
double NsPerCall(F&& f) {
  for (int i = 0; i < 200; ++i) f();
  double best = 1e30;
  for (int rep = 0; rep < 5; ++rep) {
    const int n = 4000;
    const auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < n; ++i) f();
    const auto t1 = std::chrono::steady_clock::now();
    best = std::min(best, std::chrono::duration<double, std::nano>(t1 - t0).count() / n);
  }
  return best;
}

volatile uint64_t sink;

__attribute__((noinline)) uint64_t Fnv(const uint8_t* p, size_t n) {
  uint64_t h = 0xCBF29CE484222325ull;
  for (size_t i = 0; i < n; ++i) h = (h ^ p[i]) * 0x100000001B3ull;
  return h;
}

void Timing() {
  std::printf("\nhost timing, ns per call (best of 5)\n");
  const size_t frames = 256;
  {
    const auto eq = dsp::DesignPeakingEqBoth(1000, 1.0 / 0.7, 1.6, 48000);
    std::vector<uint8_t> buf = ToBE(Noise(frames * 6, 3, 0.5f));
    dsp::BiquadHistory h[6]{};
    const double fused =
        NsPerCall([&] { dsp::RunBiquadsBE<6>(eq.norm, h, buf.data(), buf.data(), frames); });
    std::vector<float> host(frames * 6);
    const double old = NsPerCall([&] {
      for (size_t k = 0; k < host.size(); ++k) host[k] = dsp::LoadBE(buf.data() + 4 * k);
      for (int c = 0; c < 6; ++c) {
        dsp::RunBiquad(eq.raw, h[c], host.data() + c, host.data() + c, frames, 6);
      }
      for (size_t k = 0; k < host.size(); ++k) dsp::StoreBE(buf.data() + 4 * k, host[k]);
    });
    std::printf("  parameq 6 ch x %zu frames:    twin %8.0f   first twin %8.0f\n", frames, fused,
                old);
  }
  {
    std::vector<uint8_t> code(1964, 0x5A);
    std::printf("  FNV-1a over 1964 code bytes:  %8.0f (once per call before; now cached)\n",
                NsPerCall([&] { ++code[0]; sink = Fnv(code.data(), code.size()); }));
  }
}

}  // namespace

int main() {
  PeakingEq();
  Snr();
  Kernels();
  Timing();
  std::printf("%s (%d failed)\n", failures ? "FAILED" : "passed", failures);
  return failures ? 1 : 0;
}
