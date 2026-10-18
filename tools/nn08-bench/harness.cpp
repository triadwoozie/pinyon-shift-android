// NN-0.8 calibration bench harness (docs/NEAR_NATIVE_RESEARCH.md NN-0.8).
//
// Runs copies of generated guest functions (one per overhead variant, built
// by prepare.py into .local) and their native twins on a fake guest memory
// arena with synthetic inputs, checks that the variants agree, and reports
// ns per call and, on Linux and Android, user-mode instructions and cycles.
//
// This file holds no game code and no game data: the inputs are synthetic,
// the constants are published formulas (Cody and Waite's pow, the LZX
// format's position slots, an RBJ peaking-EQ biquad), and the field offsets
// are the ones recorded in site/findings.
//
//   nn08_bench [--case NAME] [--variant NAME] [--repeats N] [--ms N]
//              [--cpu N] [--csv] [--solo]

#include <algorithm>
#include <atomic>
#include <bit>
#include <cinttypes>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <limits>
#include <random>
#include <string>
#include <vector>

#include <rex/ppc/context.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <linux/perf_event.h>
#include <sched.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>
#endif

using EntryFn = void (*)(PPCContext&, uint8_t*);

// The SDK's fixed IEEE FP mode switch (NN-1.1, cvar ppc_fixed_fp_mode), at
// the shipped default: on for ARM64, off for x86-64. The translated code reads
// it on the flush-mode toggles' path.
namespace rex::ppc {
#if defined(__aarch64__)
std::atomic<bool> g_fixed_fp_mode{true};
#else
std::atomic<bool> g_fixed_fp_mode{false};
#endif
}  // namespace rex::ppc

// NN-1.12 rows: "norestrict" everywhere; the reserved-register and pinned rows
// (fixed, pin, pinsep) on AArch64 only.
#if defined(__aarch64__)
#define NN08_PIN_VARIANTS(X, c) X(c, norestrict) X(c, fixed) X(c, pin) X(c, pinsep)
#else
#define NN08_PIN_VARIANTS(X, c) X(c, norestrict)
#endif
#define NN08_VARIANTS(X, c) \
  X(c, base) X(c, novol) X(c, noswap) X(c, noflush) X(c, nolr) X(c, inline) X(c, locals) \
  X(c, allswap) X(c, all) NN08_PIN_VARIANTS(X, c)
#define NN08_DECL(c, v) void nn08_##c##_##v##_entry(PPCContext&, uint8_t*);
NN08_VARIANTS(NN08_DECL, pow)
NN08_VARIANTS(NN08_DECL, lzx)
NN08_VARIANTS(NN08_DECL, biquad)
NN08_VARIANTS(NN08_DECL, walk)
NN08_VARIANTS(NN08_DECL, physics)
void nn08_pow_native_entry(PPCContext&, uint8_t*);
void nn08_lzx_native_entry(PPCContext&, uint8_t*);
void nn08_biquad_native_entry(PPCContext&, uint8_t*);
void nn08_walk_native_entry(PPCContext&, uint8_t*);

namespace {

struct Variant {
  const char* name;
  EntryFn fn;
  bool host_order;  // scalar guest data in host byte order (noswap builds)
  const char* what;
};

#define NN08_ROW(c, v) {#v, nn08_##c##_##v##_entry, false, nullptr},
// Descriptions of each variant, in table order.
const char* Describe(const std::string& v) {
  if (v == "base") return "translated, as shipped";
  if (v == "novol") return "no volatile on guest loads/stores";
  if (v == "noswap") return "no byte swap on scalar loads/stores (unsafe)";
  if (v == "noflush") return "no flush-mode (MXCSR/FPCR) writes";
  if (v == "nolr") return "no link-register stores";
  if (v == "inline") return "helpers and callees inlinable";
  if (v == "locals") return "registers in locals, ctx at calls/returns";
  if (v == "allswap") return "all of the above except no-swap";
  if (v == "all") return "all of the above";
  if (v == "norestrict") return "base, ctx not __restrict";
  if (v == "fixed") return "base, x27/x28 reserved (-ffixed)";
  if (v == "pin") return "ctx in x27, base in x28, no arguments";
  if (v == "pinsep") return "pin, separate-storage instead of restrict";
  if (v == "native") return "native twin";
  return "";
}

//----------------------------------------------------------------------------
// Guest memory arena: 4 GB of address space, committed where the bench uses it.

constexpr uint32_t kHeap = 0x40000000u, kHeapSize = 0x02000000u;
constexpr uint32_t kStack = 0x70000000u, kStackSize = 0x00100000u;
constexpr uint32_t kImage = 0x82000000u, kImageSize = 0x02000000u;
constexpr uint32_t kStackTop = kStack + kStackSize - 0x10000u;

uint8_t* g_base = nullptr;

bool CommitRange(uint32_t address, uint32_t size) {
#if defined(_WIN32)
  return VirtualAlloc(g_base + address, size, MEM_COMMIT, PAGE_READWRITE) != nullptr;
#else
  return mprotect(g_base + address, size, PROT_READ | PROT_WRITE) == 0;
#endif
}

bool InitArena() {
  const uint64_t size = 0x100000000ull;
#if defined(_WIN32)
  g_base = static_cast<uint8_t*>(VirtualAlloc(nullptr, size, MEM_RESERVE, PAGE_NOACCESS));
  if (!g_base) return false;
#else
  void* p = mmap(nullptr, size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
  if (p == MAP_FAILED) return false;
  g_base = static_cast<uint8_t*>(p);
#endif
  return CommitRange(kHeap, kHeapSize) && CommitRange(kStack, kStackSize) &&
         CommitRange(kImage, kImageSize);
}

void ClearArena() {
  std::memset(g_base + kHeap, 0, kHeapSize);
  std::memset(g_base + kStack, 0, kStackSize);
  std::memset(g_base + kImage, 0, kImageSize);
}

// Guest data writer and reader; host_order is the no-swap builds' layout.
struct Mem {
  bool host_order = false;
  uint32_t sw32(uint32_t v) const { return host_order ? v : __builtin_bswap32(v); }
  uint64_t sw64(uint64_t v) const { return host_order ? v : __builtin_bswap64(v); }
  uint16_t sw16(uint16_t v) const { return host_order ? v : __builtin_bswap16(v); }
  void u8(uint32_t a, uint8_t v) const { g_base[a] = v; }
  void u16(uint32_t a, uint16_t v) const { v = sw16(v); std::memcpy(g_base + a, &v, 2); }
  void u32(uint32_t a, uint32_t v) const { v = sw32(v); std::memcpy(g_base + a, &v, 4); }
  void u64(uint32_t a, uint64_t v) const { v = sw64(v); std::memcpy(g_base + a, &v, 8); }
  void f32(uint32_t a, float v) const { u32(a, std::bit_cast<uint32_t>(v)); }
  void f64(uint32_t a, double v) const { u64(a, std::bit_cast<uint64_t>(v)); }
  // Vector data is always big-endian: vector loads keep their byte reversal.
  void vec(uint32_t a, const float* v) const {
    for (int i = 0; i < 4; ++i) {
      uint32_t w = __builtin_bswap32(std::bit_cast<uint32_t>(v[i]));
      std::memcpy(g_base + a + 4 * i, &w, 4);
    }
  }
  uint8_t r8(uint32_t a) const { return g_base[a]; }
  uint32_t r32(uint32_t a) const { uint32_t v; std::memcpy(&v, g_base + a, 4); return sw32(v); }
  float rf32(uint32_t a) const { return std::bit_cast<float>(r32(a)); }
  float rvec(uint32_t a) const {  // one big-endian float of vector data
    uint32_t v; std::memcpy(&v, g_base + a, 4); return std::bit_cast<float>(__builtin_bswap32(v));
  }
};

//----------------------------------------------------------------------------
// Clocks and counters.

uint64_t NowNs() {
#if defined(_WIN32)
  static LARGE_INTEGER freq = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return f; }();
  LARGE_INTEGER t;
  QueryPerformanceCounter(&t);
  return uint64_t(double(t.QuadPart) * 1e9 / double(freq.QuadPart));
#else
  timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return uint64_t(ts.tv_sec) * 1000000000ull + uint64_t(ts.tv_nsec);
#endif
}

struct Counters {
  bool ok = false;
  std::string why;
#if !defined(_WIN32)
  int leader = -1, instr = -1;
  static int Open(uint64_t config, int group) {
    perf_event_attr attr{};
    attr.size = sizeof(attr);
    attr.type = PERF_TYPE_HARDWARE;
    attr.config = config;
    attr.disabled = group == -1 ? 1 : 0;
    attr.exclude_kernel = 1;
    attr.exclude_hv = 1;
    attr.read_format = PERF_FORMAT_GROUP;
    return int(syscall(__NR_perf_event_open, &attr, 0, -1, group, 0));
  }
  void Init() {
    leader = Open(PERF_COUNT_HW_CPU_CYCLES, -1);
    if (leader < 0) { why = std::string("perf_event_open cycles: ") + strerror(errno); return; }
    instr = Open(PERF_COUNT_HW_INSTRUCTIONS, leader);
    if (instr < 0) { why = std::string("perf_event_open instructions: ") + strerror(errno); return; }
    ok = true;
  }
  void Start() {
    if (!ok) return;
    ioctl(leader, PERF_EVENT_IOC_RESET, PERF_IOC_FLAG_GROUP);
    ioctl(leader, PERF_EVENT_IOC_ENABLE, PERF_IOC_FLAG_GROUP);
  }
  void Stop(uint64_t& cycles, uint64_t& instructions) {
    cycles = instructions = 0;
    if (!ok) return;
    ioctl(leader, PERF_EVENT_IOC_DISABLE, PERF_IOC_FLAG_GROUP);
    uint64_t data[3] = {};
    if (read(leader, data, sizeof(data)) == sizeof(data)) {
      cycles = data[1];
      instructions = data[2];
    }
  }
#else
  void Init() { why = "no counters on Windows (time only)"; }
  void Start() {}
  void Stop(uint64_t& c, uint64_t& i) { c = i = 0; }
#endif
};

Counters g_counters;

//----------------------------------------------------------------------------
// Cases.

alignas(64) PPCContext g_ctx;

void FreshContext() {
  std::memset(static_cast<void*>(&g_ctx), 0, sizeof(g_ctx));
  g_ctx.msr = 0x200A000;
  g_ctx.fpscr.InitHost();
  g_ctx.fpscr.disableFlushModeUnconditional();
  g_ctx.r1.u64 = kStackTop;
}

struct Case {
  std::string name;
  std::string function;  // guest base address
  std::string unit;      // what one call does
  double tolerance;      // relative, against the base variant
  std::vector<Variant> variants;
  std::function<void(const Mem&)> setup;          // arena contents
  std::function<void(const Mem&, uint32_t)> arg;  // per-call registers and restored state
  std::function<std::vector<double>(const Mem&, EntryFn)> check;
  std::function<std::string(const Mem&, const std::vector<double>&)> truth;  // optional
};

std::mt19937_64 Rng(uint64_t seed) { return std::mt19937_64(seed); }

// ---- pow (sub_82438EA8): Cody and Waite's constants, computed here.
constexpr int kPowInputs = 1024;
std::vector<std::pair<double, double>> g_pow_in;

void PowSetup(const Mem& m) {
  m.f64(0x82000E90, 0.0);
  m.f64(0x82000DB8, 1.0);
  m.f64(0x82000E08, -1.0);
  m.f64(0x82000DF0, 0.0625);
  m.f64(0x82000DB0, 2.0);
  m.f64(0x82000E80, 0.5);
  m.f64(0x82001E90, 1024.0);                                   // integral-y limit
  m.f64(0x832970E8, 1e18);                                     // huge |y|
  m.f64(0x832973C8, std::numeric_limits<double>::infinity());  // overflow result
  m.f64(0x820C7368, 16.0);
  const uint32_t t = 0x820D7F50;
  for (int j = 1; j <= 17; ++j) m.f64(t + 8 * j, std::exp2(-(j - 1) / 16.0));  // 2^(-k/16)
  for (int j = 19; j <= 26; ++j) m.f64(t + 8 * j, 0.0);                         // tails
  const double log2e = 1.4426950408889634074;
  m.f64(t + 8 * 27, log2e);
  m.f64(t + 8 * 28, log2e - 1.0);
  const double p[4] = {1.0 / 12, 1.0 / 80, 1.0 / 448, 1.0 / 2304};  // 2 atanh series in z
  for (int j = 0; j < 4; ++j) m.f64(t + 8 * (29 + j), p[j]);
  double c = 1.0;
  for (int k = 1; k <= 7; ++k) {  // ln2^k / k!
    c *= 0.69314718055994530942 / k;
    m.f64(t + 8 * (32 + k), c);
  }
  m.f64(t + 8 * 40, 16.0 * 1023);
  m.f64(t + 8 * 41, -16.0 * 1021);
  m.f64(0x820D8178, 2.2250738585072014e-308);  // smallest normal
  const uint32_t l = 0x820D80F8;               // Cody and Waite's log
  m.f64(l + 0, 0.70710678118654752440);
  m.f64(l + 8, 0.693359375);
  m.f64(l + 40, 0.16383943563021534222e2);   // a1
  m.f64(l + 64, 0.31203222091924532844e3);   // b1
  const uint32_t c0 = 0x820D8148;
  m.f64(c0 + 0, 2.121944400546905827679e-4);  // -C2
  m.f64(c0 + 8, 0.76949932108494879777e3);    // -b0
  m.f64(c0 + 16, 0.64124943423745581147e2);   // -a0
  m.f64(c0 + 24, 0.35667977739034646171e2);   // -b2
  m.f64(c0 + 32, 0.78956112887491257267e0);   // -a2
}

void PowInputs() {
  auto rng = Rng(431);
  std::uniform_real_distribution<double> u(0.0, 1.0);
  for (int i = 0; i < kPowInputs; ++i) {
    double x, y;
    const double r = u(rng);
    if (r < 0.15) {  // integral powers, the repeated-squaring path
      x = std::floor(2 + 8 * u(rng)) * (u(rng) < 0.5 ? 1 : 0.5);
      y = std::floor(1 + 7 * u(rng));
    } else if (r < 0.25) {  // x near 1
      x = 1.0 + (u(rng) - 0.5) * 0.08;
      y = (u(rng) - 0.5) * 6;
    } else {
      x = std::exp((u(rng) * 2 - 1) * 4.6);  // 0.01 .. 100
      y = (u(rng) - 0.5) * 6;
    }
    g_pow_in.push_back({x, y});
  }
}

// ---- LZX (sub_830F2210): a synthetic stream from our own encoder.
constexpr uint32_t kLzxState = kHeap + 0x00000000u;
constexpr uint32_t kLzxWindow = kHeap + 0x00010000u;
constexpr uint32_t kLzxInput = kHeap + 0x00040000u;
constexpr uint32_t kLzxBytes = 32768;
std::vector<uint8_t> g_lzx_stream, g_lzx_plain;
uint8_t g_main_len[512], g_len_len[249], g_aligned_len[8];
uint32_t g_main_code[512], g_len_code[249], g_aligned_code[8];
const int kExtra[32] = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
                        7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14};
uint32_t kSlotBase[32];

void Canonical(const uint8_t* len, int n, uint32_t* code) {
  uint32_t next = 0;
  for (int l = 1; l <= 16; ++l) {
    for (int s = 0; s < n; ++s)
      if (len[s] == l) code[s] = next++;
    next <<= 1;
  }
}

struct BitWriter {
  std::vector<uint8_t>& out;
  uint32_t acc = 0;
  int count = 0;
  void put(uint32_t value, int bits) {
    for (int i = bits - 1; i >= 0; --i) {
      acc = (acc << 1) | ((value >> i) & 1);
      if (++count == 16) {
        out.push_back(uint8_t(acc & 0xFF));
        out.push_back(uint8_t(acc >> 8));
        acc = 0;
        count = 0;
      }
    }
  }
  void flush() {
    while (count) put(0, 1);
    for (int i = 0; i < 64; ++i) out.push_back(0);
  }
};

void LzxEncode() {
  // Code lengths: common literals 8 bits, rare 10; match symbols 9 or 10.
  for (int s = 0; s < 256; ++s) g_main_len[s] = (s >= 32 && s < 96) ? 8 : 10;
  for (int s = 256; s < 512; ++s) g_main_len[s] = ((s - 256) >> 3) < 16 ? 9 : 10;
  for (int s = 0; s < 249; ++s) g_len_len[s] = 8;
  for (int s = 0; s < 8; ++s) g_aligned_len[s] = 3;
  Canonical(g_main_len, 512, g_main_code);
  Canonical(g_len_len, 249, g_len_code);
  Canonical(g_aligned_len, 8, g_aligned_code);
  const uint32_t pos_base[32] = {0,    1,    2,    3,     4,     6,     8,     12,
                                 16,   24,   32,   48,    64,    96,    128,   192,
                                 256,  384,  512,  768,   1024,  1536,  2048,  3072,
                                 4096, 6144, 8192, 12288, 16384, 24576, 32768, 49152};
  for (int s = 0; s < 32; ++s) kSlotBase[s] = s < 3 ? 0 : pos_base[s] - 2;

  auto rng = Rng(830);
  std::uniform_real_distribution<double> u(0.0, 1.0);
  BitWriter w{g_lzx_stream};
  uint32_t r[3] = {1, 1, 1};
  auto& out = g_lzx_plain;
  while (out.size() < kLzxBytes) {
    const uint32_t pos = uint32_t(out.size());
    const bool match = pos > 8 && u(rng) < 0.55;
    if (!match) {
      const int lit = u(rng) < 0.85 ? 32 + int(u(rng) * 64) : int(u(rng) * 256);
      w.put(g_main_code[lit], g_main_len[lit]);
      out.push_back(uint8_t(lit));
      continue;
    }
    uint32_t length = u(rng) < 0.9 ? 2 + uint32_t(u(rng) * 18) : 2 + uint32_t(u(rng) * 256);
    length = std::min<uint32_t>(length, kLzxBytes + 16 - pos);
    if (length < 2) length = 2;
    int slot;
    uint32_t offset;
    const double pick = u(rng);
    if (pick < 0.08 && r[0] <= pos) {
      slot = 0; offset = r[0];
    } else if (pick < 0.12 && r[1] <= pos) {
      slot = 1; offset = r[1]; r[1] = r[0];
    } else if (pick < 0.15 && r[2] <= pos) {
      slot = 2; offset = r[2]; r[2] = r[0];
    } else {
      const uint32_t limit = std::min<uint32_t>(pos, 65000);
      offset = 1 + uint32_t(std::exp(u(rng) * std::log(double(limit))));
      offset = std::min(offset, limit);
      slot = 3;
      while (slot < 31 && kSlotBase[slot + 1] <= offset) ++slot;
      r[2] = r[1];
      r[1] = r[0];
    }
    r[0] = offset;
    const uint32_t header = std::min<uint32_t>(length - 2, 7);
    const int symbol = 256 + (slot << 3) + int(header);
    w.put(g_main_code[symbol], g_main_len[symbol]);
    if (header == 7) {
      const uint32_t l = length - 2 - 7;
      w.put(g_len_code[l], g_len_len[l]);
    }
    if (slot >= 3) {
      const int extra = kExtra[slot];
      const uint32_t value = offset - kSlotBase[slot];
      if (extra >= 3) {
        if (extra > 3) w.put(value >> 3, extra - 3);
        w.put(g_aligned_code[value & 7], 3);
      } else if (extra > 0) {
        w.put(value, extra);
      }
    }
    for (uint32_t i = 0; i < length; ++i) out.push_back(out[out.size() - offset]);
  }
  w.flush();
}

void LzxSetup(const Mem& m) {
  const uint32_t s = kLzxState;
  m.u32(s + 0, kLzxWindow);
  m.u32(s + 8, 0xFFFF);
  // Main tree table: the top 10 bits of the buffer.
  for (int sym = 0; sym < 512; ++sym) {
    const int l = g_main_len[sym];
    const uint32_t first = g_main_code[sym] << (10 - l), count = 1u << (10 - l);
    for (uint32_t e = 0; e < count; ++e) m.u16(s + 24 + 2 * (first + e), uint16_t(sym));
  }
  for (int sym = 0; sym < 249; ++sym) m.u16(s + 2072 + 2 * g_len_code[sym], uint16_t(sym));
  for (int sym = 0; sym < 512; ++sym) m.u8(s + 2584 + sym, g_main_len[sym]);
  for (int sym = 0; sym < 249; ++sym) m.u8(s + 3256 + sym, g_len_len[sym]);
  for (int sym = 0; sym < 8; ++sym) {
    for (uint32_t e = 0; e < 16; ++e) m.u8(s + 3508 + g_aligned_code[sym] * 16 + e, uint8_t(sym));
    m.u8(s + 3636 + sym, g_aligned_len[sym]);
  }
  for (int slot = 0; slot < 32; ++slot) {
    m.u8(s + 12004 + slot, uint8_t(kExtra[slot]));
    m.u32(s + 12056 + 4 * slot, kSlotBase[slot]);
  }
  m.u32(s + 11016, kLzxInput + uint32_t(g_lzx_stream.size()));
  std::memcpy(g_base + kLzxInput, g_lzx_stream.data(), g_lzx_stream.size());
  // The final copy's byte-select constant: take exactly `length` bytes (the
  // LZX contract) from the source chunk.
  std::memset(g_base + 0x822D5360, 0x11, 16);
}

void LzxArg(const Mem& m, uint32_t) {
  const uint32_t s = kLzxState;
  const uint8_t* in = g_lzx_stream.data();
  const uint32_t first = (uint32_t(in[1]) << 24) | (uint32_t(in[0]) << 16) |
                         (uint32_t(in[3]) << 8) | uint32_t(in[2]);
  m.u32(s + 11012, kLzxInput + 4);
  m.u32(s + 11952, first);
  m.u8(s + 11956, 16);
  m.u32(s + 12, 1);
  m.u32(s + 16, 1);
  m.u32(s + 20, 1);
  g_ctx.r1.u64 = kStackTop;
  g_ctx.r3.u64 = s;
  g_ctx.r4.u64 = 0;
  g_ctx.r5.u64 = kLzxBytes;
}

// ---- ParamEQ biquad (sub_82FC6CC0), six channels, one 256-frame mix block.
constexpr uint32_t kBqUnit = kHeap + 0x00100000u;
constexpr uint32_t kBqIn = kHeap + 0x00110000u;
constexpr uint32_t kBqOut = kHeap + 0x00120000u;
constexpr uint32_t kBqFrames = 256;

void BiquadSetup(const Mem& m) {
  const uint32_t s = kBqUnit;
  m.u16(s + 36, 0x3F);
  // Centre 1 kHz, bandwidth, gain +6 dB, unchanged since the last call.
  const float centre = 1000.0f, bandwidth = 1.0f, gain = 2.0f;
  m.f32(s + 312, centre); m.f32(s + 324, centre);
  m.f32(s + 316, bandwidth); m.f32(s + 328, bandwidth);
  m.f32(s + 320, gain); m.f32(s + 332, gain);
  // RBJ cookbook peaking EQ at 48 kHz.
  const double fs = 48000, a = std::sqrt(2.0), w0 = 2 * 3.14159265358979 * centre / fs;
  const double alpha = std::sin(w0) / (2 * 1.0);
  const double b0 = 1 + alpha * a, b1 = -2 * std::cos(w0), b2 = 1 - alpha * a;
  const double a0 = 1 + alpha / a, a1 = -2 * std::cos(w0), a2 = 1 - alpha / a;
  m.f32(s + 592, float(a0)); m.f32(s + 596, float(a1)); m.f32(s + 600, float(a2));
  m.f32(s + 604, float(b0)); m.f32(s + 608, float(b1)); m.f32(s + 612, float(b2));
  m.f32(0x82000D28, 1.0f);
  m.f32(0x83290458, 1e-18f);  // anti-denormal offset
  auto rng = Rng(6);
  std::uniform_real_distribution<float> noise(-0.05f, 0.05f);
  for (uint32_t i = 0; i < kBqFrames; ++i)
    for (uint32_t c = 0; c < 6; ++c) {
      const float t = float(i) / 48000.0f;
      const float v = 0.3f * std::sin(2 * 3.14159265f * (220.0f * (c + 1)) * t) + noise(rng);
      m.f32(kBqIn + 4 * (6 * i + c), v);
    }
}

void BiquadArg(const Mem&, uint32_t) {
  g_ctx.r1.u64 = kStackTop;
  g_ctx.r3.u64 = kBqUnit;
  g_ctx.r4.u64 = kBqIn;
  g_ctx.r5.u64 = kBqOut;
  g_ctx.r6.u64 = kBqFrames;
  g_ctx.r7.u64 = 6;
}

// ---- constant upload walk (sub_824168B0).
constexpr uint32_t kWalkDevice = kHeap + 0x00200000u;
constexpr uint32_t kWalkRing = kHeap + 0x00300000u;
constexpr uint32_t kWalkSource = kHeap + 0x00280000u;
constexpr int kWalkMasks = 64;
uint64_t g_walk_mask[kWalkMasks];

void WalkMasks() {
  auto rng = Rng(824);
  std::uniform_int_distribution<int> start(0, 63), len(1, 12), runs(1, 5);
  for (auto& mask : g_walk_mask) {
    mask = 0;
    for (int k = runs(rng); k > 0; --k) {
      const int s = start(rng), l = len(rng);
      for (int b = s; b < std::min(64, s + l); ++b) mask |= 1ull << (63 - b);
    }
  }
}

void WalkSetup(const Mem& m) {
  m.u32(kWalkDevice + 52, kWalkRing + 0x80000);
  for (uint32_t i = 0; i < 64 * 16; ++i) {
    const float v[4] = {float(i), float(i) + 0.25f, float(i) + 0.5f, float(i) + 0.75f};
    m.vec(kWalkSource + 16 * i, v);
  }
}

uint32_t g_walk_last = 0;

void WalkArg(const Mem& m, uint32_t i) {
  g_walk_last = i;
  m.u32(kWalkDevice + 48, kWalkRing - 4 + 4 * (i & 3));
  g_ctx.r1.u64 = kStackTop;
  g_ctx.r3.u64 = kWalkDevice;
  g_ctx.r4.u64 = g_walk_mask[i % kWalkMasks];
  g_ctx.r5.u64 = 0x4000;
  g_ctx.r6.u64 = kWalkSource;
}

// ---- physics (sub_82D21DB0): a plausible object built from synthetic floats.
constexpr uint32_t kPhysObject = kHeap + 0x00400000u;
constexpr uint32_t kPhysForce = kHeap + 0x00410000u;
constexpr uint32_t kPhysTorque = kHeap + 0x00410010u;
constexpr uint32_t kPhysTable = 0x82B4A248u;  // a constant block the function reads
constexpr uint32_t kPhysSize = 11520;

void PhysicsSetup(const Mem& m) {
  auto rng = Rng(82);
  std::uniform_real_distribution<float> u(0.2f, 1.2f);
  // Vector fields (+112 .. +160 copied as doublewords, +192, +6368, +6384,
  // +6432, +6480) and scalar fields share the object; every word is a
  // positive float so dot products, square roots and divisions stay finite.
  for (uint32_t off = 0; off < kPhysSize; off += 16) {
    const float v[4] = {u(rng), u(rng), u(rng), u(rng)};
    if (off >= 112 && off < 208) m.vec(kPhysObject + off, v);
    else if (off >= 6368 && off < 6400) m.vec(kPhysObject + off, v);
    else if (off >= 6432 && off < 6448) m.vec(kPhysObject + off, v);
    else if (off >= 6464 && off < 6496) m.vec(kPhysObject + off, v);
    else for (int k = 0; k < 4; ++k) m.f32(kPhysObject + off + 4 * k, v[k]);
  }
  m.u32(kPhysObject + 6324, 0);  // both force paths taken
  m.u32(kPhysObject + 6328, 0);
  m.u8(kPhysObject + 6660, 0);
  for (uint32_t off = 0; off < 512; off += 4) m.f32(kPhysTable + off, u(rng));
  m.f32(0x82000D28, 1.0f);
  const float zero[4] = {0, 0, 0, 0};
  m.vec(kPhysForce, zero);
  m.vec(kPhysTorque, zero);
}

void PhysicsArg(const Mem&, uint32_t) {
  g_ctx.r1.u64 = kStackTop;
  g_ctx.r3.u64 = kPhysObject;
  g_ctx.r4.u64 = kPhysForce;
  g_ctx.r5.u64 = kPhysTorque;
}

//----------------------------------------------------------------------------

template <typename Arg>
std::vector<double> Calls(const Mem& m, EntryFn fn, Arg arg, int calls,
                          std::function<void(std::vector<double>&)> collect) {
  std::vector<double> out;
  for (int i = 0; i < calls; ++i) {
    arg(m, uint32_t(i));
    fn(g_ctx, g_base);
    collect(out);
  }
  return out;
}

std::vector<Case> MakeCases() {
  std::vector<Case> cases;
  auto variants = [](std::vector<Variant> v) {
    for (auto& x : v) {
      x.host_order = std::string(x.name) == "noswap" || std::string(x.name) == "all";
      x.what = Describe(x.name);
    }
    return v;
  };

  {
    Case c;
    c.name = "pow";
    c.function = "sub_82438EA8";
    c.unit = "one pow(x, y)";
    c.tolerance = 0;
    c.variants = variants({NN08_VARIANTS(NN08_ROW, pow){"native", nn08_pow_native_entry}});
    c.setup = PowSetup;
    c.arg = [](const Mem&, uint32_t i) {
      g_ctx.r1.u64 = kStackTop;
      g_ctx.f1.f64 = g_pow_in[i % kPowInputs].first;
      g_ctx.f2.f64 = g_pow_in[i % kPowInputs].second;
    };
    c.check = [arg = c.arg](const Mem& m, EntryFn fn) {
      return Calls(m, fn, arg, kPowInputs, [](std::vector<double>& o) { o.push_back(g_ctx.f1.f64); });
    };
    c.truth = [](const Mem&, const std::vector<double>& out) {
      double worst = 0;
      for (int i = 0; i < kPowInputs; ++i) {
        const double ref = std::pow(g_pow_in[i].first, g_pow_in[i].second);
        worst = std::max(worst, std::fabs(out[i] - ref) / std::fabs(ref));
      }
      char text[96];
      std::snprintf(text, sizeof(text), "max rel. error vs std::pow %.2e", worst);
      return std::string(text);
    };
    cases.push_back(c);
  }
  {
    Case c;
    c.name = "lzx";
    c.function = "sub_830F2210";
    c.unit = "decode 32 KB";
    c.tolerance = 0;
    c.variants = variants({NN08_VARIANTS(NN08_ROW, lzx){"native", nn08_lzx_native_entry}});
    c.setup = LzxSetup;
    c.arg = LzxArg;
    c.check = [](const Mem& m, EntryFn fn) {
      return Calls(m, fn, LzxArg, 1, [&m](std::vector<double>& o) {
        o.push_back(double(int64_t(g_ctx.r3.u64)));
        for (uint32_t i = 0; i < kLzxBytes; ++i) o.push_back(g_base[kLzxWindow + i]);
        o.push_back(m.r32(kLzxState + 11012));
        o.push_back(m.r32(kLzxState + 11952));
        o.push_back(m.r8(kLzxState + 11956));
        for (uint32_t k = 0; k < 3; ++k) o.push_back(m.r32(kLzxState + 12 + 4 * k));
      });
    };
    c.truth = [](const Mem&, const std::vector<double>& out) {
      uint32_t bad = 0;
      for (uint32_t i = 0; i < kLzxBytes; ++i) bad += out[1 + i] != g_lzx_plain[i];
      char text[96];
      std::snprintf(text, sizeof(text), "%u of %u bytes differ from the encoder's input", bad,
                    kLzxBytes);
      return std::string(text);
    };
    cases.push_back(c);
  }
  {
    Case c;
    c.name = "biquad";
    c.function = "sub_82FC6CC0";
    c.unit = "6 ch x 256 frames";
    c.tolerance = 1e-4;
    c.variants = variants({NN08_VARIANTS(NN08_ROW, biquad){"native", nn08_biquad_native_entry}});
    c.setup = BiquadSetup;
    c.arg = BiquadArg;
    c.check = [](const Mem& m, EntryFn fn) {
      return Calls(m, fn, BiquadArg, 4, [&m](std::vector<double>& o) {
        for (uint32_t i = 0; i < 6 * kBqFrames; ++i) o.push_back(m.rf32(kBqOut + 4 * i));
      });
    };
    c.truth = [](const Mem& m, const std::vector<double>& out) {
      double signal = 0;
      for (double v : out) signal += v * v;
      char text[96];
      std::snprintf(text, sizeof(text), "output RMS %.4f", std::sqrt(signal / out.size()));
      (void)m;
      return std::string(text);
    };
    cases.push_back(c);
  }
  {
    Case c;
    c.name = "walk";
    c.function = "sub_824168B0";
    c.unit = "one dirty mask";
    c.tolerance = 0;
    c.variants = variants({NN08_VARIANTS(NN08_ROW, walk){"native", nn08_walk_native_entry}});
    c.setup = WalkSetup;
    c.arg = WalkArg;
    c.check = [](const Mem& m, EntryFn fn) {
      return Calls(m, fn, WalkArg, kWalkMasks, [&m](std::vector<double>& o) {
        // Parse the packets: NOPs and headers are scalar stores, the payload
        // is copied vector data (big-endian in every layout).
        const uint32_t end = m.r32(kWalkDevice + 48);
        o.push_back(end);
        uint32_t a = kWalkRing + 4 * (g_walk_last & 3);  // first dword written
        while (a <= end) {
          const uint32_t w = m.r32(a);
          o.push_back(w);
          a += 4;
          if (w == 0x80000000u || w == 0) continue;
          const uint32_t dwords = (w >> 16) + 1;
          for (uint32_t k = 0; k < dwords && a <= end; ++k, a += 4) o.push_back(m.rvec(a));
        }
      });
    };
    cases.push_back(c);
  }
  {
    Case c;
    c.name = "physics";
    c.function = "sub_82D21DB0";
    c.unit = "one call";
    c.tolerance = 1e-4;
    c.variants = variants({NN08_VARIANTS(NN08_ROW, physics)});
    c.setup = PhysicsSetup;
    c.arg = PhysicsArg;
    c.check = [](const Mem& m, EntryFn fn) {
      return Calls(m, fn, PhysicsArg, 3, [&m](std::vector<double>& o) {
        for (int k = 0; k < 8; ++k) o.push_back(m.rvec(kPhysForce + 4 * k));
        for (uint32_t off : {6332u, 6336u, 7404u, 8428u, 9452u, 10476u})
          o.push_back(m.rf32(kPhysObject + off));
      });
    };
    cases.push_back(c);
  }
  return cases;
}

//----------------------------------------------------------------------------

struct Sample {
  double ns = 0, cycles = 0, instructions = 0;
};

// Average cost of one call over `iterations`, minus the per-call setup cost.
Sample Measure(const Case& c, const Variant& v, const Mem& m, uint64_t iterations) {
  Sample s;
  uint64_t c0 = 0, i0 = 0, c1 = 0, i1 = 0;
  g_counters.Start();
  uint64_t t0 = NowNs();
  for (uint64_t i = 0; i < iterations; ++i) c.arg(m, uint32_t(i));
  uint64_t t1 = NowNs();
  g_counters.Stop(c0, i0);
  g_counters.Start();
  uint64_t t2 = NowNs();
  for (uint64_t i = 0; i < iterations; ++i) {
    c.arg(m, uint32_t(i));
    v.fn(g_ctx, g_base);
  }
  uint64_t t3 = NowNs();
  g_counters.Stop(c1, i1);
  s.ns = (double(t3 - t2) - double(t1 - t0)) / double(iterations);
  s.cycles = (double(c1) - double(c0)) / double(iterations);
  s.instructions = (double(i1) - double(i0)) / double(iterations);
  return s;
}

void Prepare(const Case& c, const Variant& v, Mem& m) {
  m.host_order = v.host_order;
  ClearArena();
  FreshContext();
  c.setup(m);
}

double Median(std::vector<double> v) {
  if (v.empty()) return 0;
  std::sort(v.begin(), v.end());
  return v[v.size() / 2];
}

struct Options {
  std::string only_case, only_variant;
  int repeats = 7;
  double ms = 40;
  int cpu = -1;
  bool csv = false;
  bool solo = false;  // only --variant, for an external profiler (simpleperf stat, perf stat)
};

int Run(const Options& o) {
  auto cases = MakeCases();
  bool any = false;
  for (auto& c : cases) {
    if (!o.only_case.empty() && c.name != o.only_case) continue;
    any = true;
    std::vector<const Variant*> vs;
    for (auto& v : c.variants)
      if (o.only_variant.empty() || v.name == o.only_variant || (!o.solo && std::string(v.name) == "base"))
        vs.push_back(&v);

    std::printf("\n== %s (%s), unit: %s\n", c.name.c_str(), c.function.c_str(), c.unit.c_str());
    // Correctness: every variant against base on the same inputs.
    std::vector<std::vector<double>> outs(vs.size());
    std::vector<double> err(vs.size(), 0);
    for (size_t k = 0; k < vs.size(); ++k) {
      Mem m;
      Prepare(c, *vs[k], m);
      outs[k] = c.check(m, vs[k]->fn);
      if (k == 0 && c.truth) std::printf("   base check: %s\n", c.truth(m, outs[0]).c_str());
      if (std::string(vs[k]->name) == "native" && c.truth)
        std::printf("   native check: %s\n", c.truth(m, outs[k]).c_str());
      double worst = 0;
      if (outs[k].size() != outs[0].size()) {
        worst = std::numeric_limits<double>::infinity();
      } else {
        for (size_t i = 0; i < outs[k].size(); ++i) {
          const double a = outs[k][i], b = outs[0][i];
          if (a == b || (std::isnan(a) && std::isnan(b))) continue;
          const double scale = std::max(std::fabs(b), 1e-3);
          worst = std::max(worst, std::isfinite(a - b) ? std::fabs(a - b) / scale
                                                       : std::numeric_limits<double>::infinity());
        }
      }
      err[k] = worst;
    }

    // Timing: variants interleaved round-robin across repeats (thermal drift).
    std::vector<uint64_t> iters(vs.size());
    for (size_t k = 0; k < vs.size(); ++k) {
      Mem m;
      Prepare(c, *vs[k], m);
      uint64_t n = 16;
      for (;;) {
        const uint64_t t0 = NowNs();
        for (uint64_t i = 0; i < n; ++i) {
          c.arg(m, uint32_t(i));
          vs[k]->fn(g_ctx, g_base);
        }
        const double elapsed = double(NowNs() - t0) / 1e6;
        if (elapsed > o.ms / 4 || n > (1ull << 30)) {
          n = uint64_t(double(n) * o.ms / std::max(elapsed, 1e-3));
          break;
        }
        n *= 4;
      }
      iters[k] = std::max<uint64_t>(n, 16);
    }
    std::vector<std::vector<double>> ns(vs.size()), cyc(vs.size()), ins(vs.size());
    for (int r = 0; r < o.repeats; ++r) {
      for (size_t k = 0; k < vs.size(); ++k) {
        Mem m;
        Prepare(c, *vs[k], m);
        const Sample s = Measure(c, *vs[k], m, iters[k]);
        ns[k].push_back(s.ns);
        cyc[k].push_back(s.cycles);
        ins[k].push_back(s.instructions);
      }
    }
    const double base_ns = Median(ns[0]);
    const double base_cyc = Median(cyc[0]);
    std::printf("   %-10s %11s %8s %12s %12s %6s %10s  %s\n", "variant", "ns/call", "vs base",
                "instr/call", "cycles/call", "IPC", "max err", "what");
    for (size_t k = 0; k < vs.size(); ++k) {
      const double n = Median(ns[k]), cy = Median(cyc[k]), in = Median(ins[k]);
      const bool ok = err[k] <= c.tolerance;
      char errtext[32];
      if (err[k] == 0) std::snprintf(errtext, sizeof(errtext), "exact");
      else std::snprintf(errtext, sizeof(errtext), "%.1e%s", err[k], ok ? "" : "!");
      if (g_counters.ok) {
        std::printf("   %-10s %11.1f %7.2fx %12.0f %12.0f %6.2f %10s  %s\n", vs[k]->name, n,
                    base_ns / n, in, cy, cy > 0 ? in / cy : 0.0, errtext, vs[k]->what);
      } else {
        std::printf("   %-10s %11.1f %7.2fx %12s %12s %6s %10s  %s\n", vs[k]->name, n, base_ns / n,
                    "-", "-", "-", errtext, vs[k]->what);
      }
      if (o.csv)
        std::printf("csv,%s,%s,%.2f,%.4f,%.1f,%.1f,%.3f,%.3e,%d\n", c.name.c_str(), vs[k]->name, n,
                    base_ns / n, in, cy, cy > 0 ? in / cy : 0.0, err[k], ok ? 1 : 0);
    }
    if (g_counters.ok && base_cyc > 0) {
      std::printf("   cycle share removed vs base:");
      for (size_t k = 1; k < vs.size(); ++k)
        std::printf(" %s %.1f%%", vs[k]->name, 100.0 * (1.0 - Median(cyc[k]) / base_cyc));
      std::printf("\n");
    }
    std::printf("   time share removed vs base:");
    for (size_t k = 1; k < vs.size(); ++k)
      std::printf(" %s %.1f%%", vs[k]->name, 100.0 * (1.0 - Median(ns[k]) / base_ns));
    std::printf("\n");
  }
  if (!any) {
    std::fprintf(stderr, "no case named %s\n", o.only_case.c_str());
    return 2;
  }
  return 0;
}

}  // namespace

int main(int argc, char** argv) {
  Options o;
  for (int i = 1; i < argc; ++i) {
    const std::string a = argv[i];
    auto next = [&]() -> const char* {
      if (i + 1 >= argc) { std::fprintf(stderr, "%s needs a value\n", a.c_str()); std::exit(2); }
      return argv[++i];
    };
    if (a == "--case") o.only_case = next();
    else if (a == "--variant") o.only_variant = next();
    else if (a == "--repeats") o.repeats = std::atoi(next());
    else if (a == "--ms") o.ms = std::atof(next());
    else if (a == "--cpu") o.cpu = std::atoi(next());
    else if (a == "--csv") o.csv = true;
    else if (a == "--solo") o.solo = true;
    else {
      std::fprintf(stderr,
                   "usage: nn08_bench [--case pow|lzx|biquad|walk|physics] [--variant NAME]\n"
                   "                  [--repeats N] [--ms N] [--cpu N] [--csv] [--solo]\n");
      return 2;
    }
  }
  if (o.cpu >= 0) {
#if defined(_WIN32)
    SetThreadAffinityMask(GetCurrentThread(), DWORD_PTR(1) << o.cpu);
#else
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(o.cpu, &set);
    if (sched_setaffinity(0, sizeof(set), &set) != 0) std::perror("sched_setaffinity");
#endif
  }
  if (!InitArena()) {
    std::fprintf(stderr, "could not reserve the 4 GB guest arena\n");
    return 1;
  }
  g_counters.Init();
  std::printf("NN-0.8 calibration bench: %d repeats, %.0f ms each, counters: %s\n", o.repeats,
              o.ms, g_counters.ok ? "user-mode cycles and instructions" : g_counters.why.c_str());
  PowInputs();
  LzxEncode();
  WalkMasks();
  return Run(o);
}
