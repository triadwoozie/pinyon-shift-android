#include "guest_native/guest_native.h"

#include <array>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <fmt/format.h>
#include <rex/cvar.h>
#include <rex/system/xmemory.h>

#include "pinyon_shift_diagnostics.h"

REXCVAR_DEFINE_BOOL(pinyon_shift_guest_native, true, "Pinyon Shift",
                    "Run hand-written native replacements of hot guest functions (#431); "
                    "each also has its own switch")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_UINT32(pinyon_shift_guest_native_verify, 0, "Pinyon Shift",
                      "Every Nth call of a native guest replacement also runs the original "
                      "and compares results, keeping the original's (0 = off, 1 = every call)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

namespace pinyon_shift::guest_native {
namespace {

constexpr uint64_t kMaxLoggedMismatches = 16;

std::mutex registry_mutex;
std::vector<Replacement*> registry;

std::vector<Replacement*>& DeclaredReplacements() {
  static std::vector<Replacement*> declared;
  return declared;
}

void Register(Replacement& replacement) {
  std::lock_guard lock(registry_mutex);
  registry.push_back(&replacement);
}

using Clock = std::chrono::steady_clock;

uint64_t Nanoseconds(Clock::duration duration) {
  return static_cast<uint64_t>(
      std::chrono::duration_cast<std::chrono::nanoseconds>(duration).count());
}

std::string Hex64(uint64_t value) { return fmt::format("{:016X}", value); }

bool CheckGuestCode(Replacement& replacement, uint8_t* base) {
  int state = replacement.code_state.load(std::memory_order_acquire);
  if (state != 0) return state > 0;
  const uint64_t hash =
      HashGuestCode(base + replacement.guest_address, replacement.guest_code_size);
  const int checked = hash == replacement.guest_code_fnv1a ? 1 : -1;
  if (replacement.code_state.compare_exchange_strong(state, checked)) {
    Register(replacement);
    diagnostics::RecordEvent(
        checked > 0 ? "guest_native.installed" : "guest_native.code_mismatch",
        {{"name", replacement.name},
         {"address", fmt::format("{:08X}", replacement.guest_address)},
         {"expected", Hex64(replacement.guest_code_fnv1a)},
         {"found", Hex64(hash)}});
  }
  return replacement.code_state.load(std::memory_order_acquire) > 0;
}

uint8_t* Host(uint8_t* base, uint32_t address) {
  return base + address + rex::memory::detail::PhysicalHostOffset(address);
}

struct SavedRanges {
  std::array<GuestRange, kMaxWrittenRanges> ranges{};
  size_t count = 0;
  std::vector<uint8_t> bytes;

  void Capture(uint8_t* base) {
    bytes.clear();
    for (size_t i = 0; i < count; ++i) {
      for (uint32_t j = 0; j < ranges[i].size; ++j) {
        bytes.push_back(*Host(base, ranges[i].address + j));
      }
    }
  }
  void Restore(uint8_t* base) const {
    size_t offset = 0;
    for (size_t i = 0; i < count; ++i) {
      for (uint32_t j = 0; j < ranges[i].size; ++j) {
        *Host(base, ranges[i].address + j) = bytes[offset++];
      }
    }
  }
};

template <typename T>
bool Same(const T& a, const T& b) {
  return std::memcmp(&a, &b, sizeof(T)) == 0;
}

// Returns a short description of the first difference, empty when equal.
std::string CompareRegisters(const Replacement& replacement, const PPCContext& native,
                             const PPCContext& original) {
  if ((replacement.returns & kReturnR3) && !Same(native.r3.u64, original.r3.u64)) {
    return fmt::format("r3 native={:016X} original={:016X}", native.r3.u64, original.r3.u64);
  }
  if ((replacement.returns & kReturnF1) && !Same(native.f1.u64, original.f1.u64)) {
    return fmt::format("f1 native={:016X} original={:016X}", native.f1.u64, original.f1.u64);
  }
  if ((replacement.returns & kReturnV3) && !Same(native.v3, original.v3)) return "v3";
  if (!Same(native.r1, original.r1)) return "r1";
  if (!Same(native.r2, original.r2)) return "r2";
  if (!Same(native.r13, original.r13)) return "r13";
  if (std::memcmp(&native.r14, &original.r14, 18 * sizeof(PPCRegister)) != 0) return "r14-r31";
  if (std::memcmp(&native.f14, &original.f14, 18 * sizeof(PPCRegister)) != 0) return "f14-f31";
  if (std::memcmp(&native.v14, &original.v14, 18 * sizeof(PPCVRegister)) != 0) return "v14-v31";
  if (std::memcmp(&native.v64, &original.v64, 64 * sizeof(PPCVRegister)) != 0) return "v64-v127";
  if (!Same(native.cr2, original.cr2) || !Same(native.cr3, original.cr3) ||
      !Same(native.cr4, original.cr4)) {
    return "cr2-cr4";
  }
  return {};
}

void Verify(Replacement& replacement, PPCFunc* original, PPCContext& ctx, uint8_t* base) {
  if (replacement.verify) {
    replacement.verify(replacement, original, ctx, base);
    return;
  }
  SavedRanges before;
  if (replacement.written) {
    before.count = replacement.written(ctx, base, before.ranges.data());
  }
  before.Capture(base);
  const PPCContext input = ctx;

  // Timed too, alternating which runs first so that neither always meets
  // warmer caches. The original always runs on the real registers, and its
  // memory writes are what remain.
  PPCContext native = input;
  SavedRanges native_written = before;
  SavedRanges original_written = before;
  bool handled = false;
  Clock::duration native_time{};
  Clock::duration original_time{};
  auto run_native = [&] {
    const auto start = Clock::now();
    handled = replacement.native(native, base);
    native_time = Clock::now() - start;
    native_written.Capture(base);
  };
  auto run_original = [&] {
    const auto start = Clock::now();
    original(ctx, base);
    original_time = Clock::now() - start;
    original_written.Capture(base);
  };
  if (replacement.stats.native.load(std::memory_order_relaxed) & 1) {
    run_original();
    before.Restore(base);
    run_native();
    original_written.Restore(base);
  } else {
    run_native();
    before.Restore(base);
    run_original();
  }
  if (!handled) {
    replacement.stats.declined.fetch_add(1, std::memory_order_relaxed);
    return;
  }
  replacement.stats.verified.fetch_add(1, std::memory_order_relaxed);
  replacement.stats.native_ns.fetch_add(Nanoseconds(native_time), std::memory_order_relaxed);
  replacement.stats.original_ns.fetch_add(Nanoseconds(original_time),
                                          std::memory_order_relaxed);

  std::string difference = CompareRegisters(replacement, native, ctx);
  if (difference.empty() && original_written.bytes != native_written.bytes) {
    difference = "memory";
  }
  if (difference.empty()) return;
  const uint64_t count = replacement.stats.mismatched.fetch_add(1, std::memory_order_relaxed);
  if (count >= kMaxLoggedMismatches) return;
  diagnostics::RecordEvent(
      "guest_native.mismatch",
      {{"name", replacement.name},
       {"difference", difference},
       {"r3", Hex64(input.r3.u64)},
       {"r4", Hex64(input.r4.u64)},
       {"r5", Hex64(input.r5.u64)},
       {"r6", Hex64(input.r6.u64)},
       {"f1", Hex64(input.f1.u64)},
       {"f2", Hex64(input.f2.u64)},
       {"f3", Hex64(input.f3.u64)},
       {"lr", fmt::format("{:08X}", static_cast<uint32_t>(input.lr))}});
}

}  // namespace

std::string CompareReferenceRegisters(const PPCContext& reference, const PPCContext& optimized) {
  // Everything a caller may read after a return: the three return registers and
  // the non-volatile set the ABI preserves.
  Replacement all{};
  all.returns = kReturnR3 | kReturnF1 | kReturnV3;
  return CompareRegisters(all, reference, optimized);
}

uint64_t HashGuestCode(const uint8_t* code, uint32_t size) {
  uint64_t hash = 0xCBF29CE484222325ull;
  for (uint32_t i = 0; i < size; ++i) {
    hash ^= code[i];
    hash *= 0x100000001B3ull;
  }
  return hash;
}

Declared::Declared(Replacement& replacement) { DeclaredReplacements().push_back(&replacement); }

size_t CheckAllGuestCode(uint8_t* base) {
  size_t matched = 0;
  for (Replacement* replacement : DeclaredReplacements()) {
    matched += CheckGuestCode(*replacement, base) ? 1 : 0;
  }
  return matched;
}

void Dispatch(Replacement& replacement, PPCFunc* original, PPCContext& ctx, uint8_t* base) {
  if (!REXCVAR_GET(pinyon_shift_guest_native) || !replacement.enabled() ||
      !CheckGuestCode(replacement, base)) {
    replacement.stats.original.fetch_add(1, std::memory_order_relaxed);
    original(ctx, base);
    return;
  }
  const uint32_t verify_every = REXCVAR_GET(pinyon_shift_guest_native_verify);
  if (verify_every) {
    const uint64_t call = replacement.stats.native.fetch_add(1, std::memory_order_relaxed);
    if (call % verify_every == 0) {
      Verify(replacement, original, ctx, base);
      return;
    }
    if (!replacement.native(ctx, base)) {
      replacement.stats.declined.fetch_add(1, std::memory_order_relaxed);
      original(ctx, base);
    }
    return;
  }
  if (replacement.native(ctx, base)) {
    replacement.stats.native.fetch_add(1, std::memory_order_relaxed);
    return;
  }
  replacement.stats.declined.fetch_add(1, std::memory_order_relaxed);
  original(ctx, base);
}

void RecordSummary() {
  std::lock_guard lock(registry_mutex);
  for (const Replacement* replacement : registry) {
    const Stats& stats = replacement->stats;
    diagnostics::RecordEvent(
        "guest_native.summary",
        {{"name", replacement->name},
         {"native", std::to_string(stats.native.load(std::memory_order_relaxed))},
         {"declined", std::to_string(stats.declined.load(std::memory_order_relaxed))},
         {"original", std::to_string(stats.original.load(std::memory_order_relaxed))},
         {"verified", std::to_string(stats.verified.load(std::memory_order_relaxed))},
         {"mismatched", std::to_string(stats.mismatched.load(std::memory_order_relaxed))},
         {"verified_native_ns", std::to_string(stats.native_ns.load(std::memory_order_relaxed))},
         {"verified_original_ns",
          std::to_string(stats.original_ns.load(std::memory_order_relaxed))}});
  }
}

}  // namespace pinyon_shift::guest_native
