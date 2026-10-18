// Lockstep verify of the generator's codegen options (NEXT_LEVEL_RESEARCH.md
// NL-0.2). The generator emits every function listed in reference_functions,
// and every Nth leaf function with reference_sample_every, a second time as
// `ref_sub_X`: the reference options (every register in ctx, no folding, calls
// through the thunk) and every store routed through a log (rex/ppc/reference.h).
// The generated pinyon_shift_reference.h names them, and this file defines the
// strong `sub_X` for each, which with pinyon_shift_reference_verify=N runs the
// reference copy on a copy of the registers every Nth call, rolls its stores
// back, runs the optimized function for real, and compares the registers a
// caller may read and the bytes both wrote. Mismatches are logged with their
// inputs; the game always keeps the optimized function's results.

#include "guest_native/guest_native.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <fmt/format.h>
#include <rex/cvar.h>
#include <rex/ppc/reference.h>

#include "pinyon_shift_diagnostics.h"

REXCVAR_DEFINE_UINT32(pinyon_shift_reference_verify, 0, "Pinyon Shift",
                      "Every Nth call of a function with a reference copy also runs the copy and "
                      "compares registers and written memory (0 = off, 1 = every call)")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_UINT32(pinyon_shift_reference_verify_ulps, 0, "Pinyon Shift",
                      "Lockstep verify tolerance: floating-point registers and stored floats may "
                      "differ by up to this many single-precision ulps (0 = bit-exact; NaNs of "
                      "either sign or payload always match). Counted apart as within tolerance")
    .lifecycle(rex::cvar::Lifecycle::kHotReload);

namespace pinyon_shift::guest_native::reference {
namespace {

constexpr uint64_t kMaxLoggedMismatches = 64;

struct Entry {
  const char* name;
  uint32_t address;
  std::atomic<uint64_t> calls{0};
  std::atomic<uint64_t> verified{0};
  std::atomic<uint64_t> mismatched{0};
  std::atomic<uint64_t> within_tolerance{0};  // differed, but only by FP values within the cvar
  std::atomic<uint64_t> max_ulps{0};          // the largest FP distance seen, mismatch or not
};

std::mutex registry_mutex;
std::vector<Entry*> registry;

struct Registration {
  explicit Registration(Entry& entry) {
    std::lock_guard lock(registry_mutex);
    registry.push_back(&entry);
  }
};

std::string Hex64(uint64_t value) { return fmt::format("{:016X}", value); }

// The registers a caller may read after the return (as CompareReferenceRegisters),
// with floating-point ones measured in single-precision ulps instead of
// compared bit for bit. `structural` names the first non-FP difference.
struct RegisterDiff {
  std::string structural;
  double max_ulps = 0;
  std::string worst;  // "f1 3F800000... 3F800001..." for the largest FP distance
  bool any = false;   // some FP register bits differ (a NaN sign counts)
};

double FprUlps(uint64_t a, uint64_t b) {
  // A single-precision value (most FH1 math) is measured as a float.
  double da, db;
  std::memcpy(&da, &a, 8);
  std::memcpy(&db, &b, 8);
  if (double(float(da)) == da && double(float(db)) == db) {
    const float fa = float(da), fb = float(db);
    uint32_t ia, ib;
    std::memcpy(&ia, &fa, 4);
    std::memcpy(&ib, &fb, 4);
    return rex::ppc::RefFloatUlps(ia, ib);
  }
  return rex::ppc::RefDoubleUlps(a, b);
}

RegisterDiff CompareRegistersWithUlps(const PPCContext& reference, const PPCContext& optimized) {
  RegisterDiff diff;
  auto fpr = [&](const char* name, const PPCRegister& r, const PPCRegister& o) {
    if (r.u64 == o.u64)
      return;
    diff.any = true;
    const double ulps = FprUlps(r.u64, o.u64);
    if (ulps >= diff.max_ulps) {
      diff.max_ulps = ulps;
      diff.worst = fmt::format("{} {:016X} {:016X}", name, r.u64, o.u64);
    }
  };
  auto vr = [&](const char* name, int index, const PPCVRegister& r, const PPCVRegister& o) {
    for (int lane = 0; lane < 4; ++lane) {
      if (r.u32[lane] == o.u32[lane])
        continue;
      diff.any = true;
      const double ulps = rex::ppc::RefFloatUlps(r.u32[lane], o.u32[lane]);
      if (ulps >= diff.max_ulps) {
        diff.max_ulps = ulps;
        diff.worst = fmt::format("{}{}[{}] {:08X} {:08X}", name, index, lane, r.u32[lane],
                                 o.u32[lane]);
      }
    }
  };
  auto gpr = [&](const char* name, const PPCRegister& r, const PPCRegister& o) {
    if (diff.structural.empty() && r.u64 != o.u64)
      diff.structural = fmt::format("{} reference={:016X} optimized={:016X}", name, r.u64, o.u64);
  };
  gpr("r1", reference.r1, optimized.r1);
  gpr("r2", reference.r2, optimized.r2);
  gpr("r3", reference.r3, optimized.r3);
  gpr("r13", reference.r13, optimized.r13);
  const PPCRegister* rr = &reference.r14;
  const PPCRegister* ro = &optimized.r14;
  for (int i = 0; i < 18; ++i)
    gpr(fmt::format("r{}", 14 + i).c_str(), rr[i], ro[i]);
  fpr("f1", reference.f1, optimized.f1);
  const PPCRegister* fr = &reference.f14;
  const PPCRegister* fo = &optimized.f14;
  for (int i = 0; i < 18; ++i)
    fpr(fmt::format("f{}", 14 + i).c_str(), fr[i], fo[i]);
  vr("v", 3, reference.v3, optimized.v3);
  const PPCVRegister* vr14 = &reference.v14;
  const PPCVRegister* vo14 = &optimized.v14;
  for (int i = 0; i < 18; ++i)
    vr("v", 14 + i, vr14[i], vo14[i]);
  const PPCVRegister* vr64 = &reference.v64;
  const PPCVRegister* vo64 = &optimized.v64;
  for (int i = 0; i < 64; ++i)
    vr("v", 64 + i, vr64[i], vo64[i]);
  if (diff.structural.empty() &&
      (std::memcmp(&reference.cr2, &optimized.cr2, sizeof(reference.cr2)) != 0 ||
       std::memcmp(&reference.cr3, &optimized.cr3, sizeof(reference.cr3)) != 0 ||
       std::memcmp(&reference.cr4, &optimized.cr4, sizeof(reference.cr4)) != 0))
    diff.structural = "cr2-cr4";
  return diff;
}

void NoteMaxUlps(std::atomic<uint64_t>& slot, double ulps) {
  const uint64_t value = ulps > 1e18 ? UINT64_MAX : uint64_t(ulps);
  uint64_t seen = slot.load(std::memory_order_relaxed);
  while (value > seen && !slot.compare_exchange_weak(seen, value, std::memory_order_relaxed)) {
  }
}

void Dispatch(Entry& entry, PPCFunc* original, PPCFunc* reference, PPCContext& ctx,
              uint8_t* base) {
  const uint32_t every = REXCVAR_GET(pinyon_shift_reference_verify);
  if (!every) {
    original(ctx, base);
    return;
  }
  const uint64_t call = entry.calls.fetch_add(1, std::memory_order_relaxed);
  if (call % every != 0) {
    original(ctx, base);
    return;
  }

  const PPCContext input = ctx;
  PPCContext reference_ctx = input;
  rex::ppc::RefLogBegin();
  reference(reference_ctx, base);
  rex::ppc::RefRollback();
  original(ctx, base);

  // Registers first (the first non-FP difference wins), then every checked
  // store; FP values are measured in single-precision ulps either way.
  const RegisterDiff registers = CompareRegistersWithUlps(reference_ctx, ctx);
  const rex::ppc::RefMemoryDiff memory = rex::ppc::RefCompareMemory();
  entry.verified.fetch_add(1, std::memory_order_relaxed);
  const bool fp_differs = registers.any || memory.differing != 0;
  if (registers.structural.empty() && !fp_differs)
    return;
  const double max_ulps = std::max(registers.max_ulps, memory.max_ulps);
  NoteMaxUlps(entry.max_ulps, max_ulps);
  const uint32_t tolerance = REXCVAR_GET(pinyon_shift_reference_verify_ulps);
  const bool structural = !registers.structural.empty() || memory.structural;
  if (!structural && tolerance && max_ulps <= tolerance) {
    entry.within_tolerance.fetch_add(1, std::memory_order_relaxed);
    return;
  }
  std::string difference = registers.structural;
  if (difference.empty() && memory.first)
    difference = fmt::format("memory {:08X}+{}", memory.first->guest, unsigned(memory.first->size));
  if (difference.empty())
    difference = registers.worst.substr(0, registers.worst.find(' '));
  std::string worst = registers.worst;
  if (memory.differing && memory.max_ulps >= registers.max_ulps)
    worst = fmt::format("memory {:08X} {:X} {:X}", memory.worst_guest, memory.worst_reference,
                        memory.worst_optimized);
  const uint64_t count = entry.mismatched.fetch_add(1, std::memory_order_relaxed);
  if (count >= kMaxLoggedMismatches)
    return;
  diagnostics::RecordEvent("reference.mismatch",
                           {{"name", entry.name},
                            {"difference", difference},
                            {"max_ulps", max_ulps > 1e18 ? std::string("inf")
                                                         : fmt::format("{:.0f}", max_ulps)},
                            {"worst", worst},
                            {"stores_differing", std::to_string(memory.differing)},
                            {"r3", Hex64(input.r3.u64)},
                            {"r4", Hex64(input.r4.u64)},
                            {"r5", Hex64(input.r5.u64)},
                            {"r6", Hex64(input.r6.u64)},
                            {"f1", Hex64(input.f1.u64)},
                            {"f2", Hex64(input.f2.u64)},
                            {"lr", fmt::format("{:08X}", static_cast<uint32_t>(input.lr))}});
}

}  // namespace

void RecordSummary() {
  std::lock_guard lock(registry_mutex);
  uint64_t calls = 0, verified = 0, mismatched = 0, functions_mismatched = 0, tolerated = 0;
  for (const Entry* entry : registry) {
    calls += entry->calls.load(std::memory_order_relaxed);
    verified += entry->verified.load(std::memory_order_relaxed);
    const uint64_t m = entry->mismatched.load(std::memory_order_relaxed);
    const uint64_t t = entry->within_tolerance.load(std::memory_order_relaxed);
    mismatched += m;
    tolerated += t;
    functions_mismatched += m ? 1 : 0;
    if (m || t) {
      const uint64_t ulps = entry->max_ulps.load(std::memory_order_relaxed);
      diagnostics::RecordEvent(
          "reference.function",
          {{"name", entry->name},
           {"verified", std::to_string(entry->verified.load(std::memory_order_relaxed))},
           {"mismatched", std::to_string(m)},
           {"within_tolerance", std::to_string(t)},
           {"max_ulps", ulps == UINT64_MAX ? std::string("inf") : std::to_string(ulps)}});
    }
  }
  diagnostics::RecordEvent("reference.summary",
                           {{"functions", std::to_string(registry.size())},
                            {"calls", std::to_string(calls)},
                            {"verified", std::to_string(verified)},
                            {"mismatched", std::to_string(mismatched)},
                            {"within_tolerance", std::to_string(tolerated)},
                            {"tolerance_ulps",
                             std::to_string(REXCVAR_GET(pinyon_shift_reference_verify_ulps))},
                            {"functions_mismatched", std::to_string(functions_mismatched)}});
}

}  // namespace pinyon_shift::guest_native::reference

// One strong `sub_X` per reference function, routing through Dispatch.
#define REX_REFERENCE_FUNCTION(address, name)                                                  \
  extern "C" REX_FUNC(__imp__##name);                                                          \
  extern "C" REX_FUNC(ref_##name);                                                             \
  namespace {                                                                                  \
  ::pinyon_shift::guest_native::reference::Entry entry_##name{#name, address};                 \
  ::pinyon_shift::guest_native::reference::Registration registration_##name{entry_##name};     \
  }                                                                                            \
  extern "C" REX_FUNC(name) {                                                                  \
    ::pinyon_shift::guest_native::reference::Dispatch(entry_##name, &__imp__##name, &ref_##name, \
                                                      ctx, base);                              \
  }

#include "pinyon_shift_reference.h"  // generated beside the recompiled code
