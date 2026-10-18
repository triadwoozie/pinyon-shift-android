// NP-3.5: a build with the FMA3 baseline (PINYON_SHIFT_CPU_BASELINE=fma)
// stops with a message on a CPU without FMA3, instead of crashing on the
// first fused multiply-add. The x86-64-v3 baseline (NEXT_LEVEL_RESEARCH.md
// NL-1.8, PINYON_SHIFT_CPU_BASELINE=v3) also needs AVX2, BMI1, BMI2, LZCNT,
// MOVBE and F16C. tools/build-preview.ps1 only picks a baseline the CPU has,
// so this matters when a build is copied elsewhere.
#if defined(PINYON_SHIFT_CPU_BASELINE_FMA) && (defined(__x86_64__) || defined(_M_X64))

#include <cstdint>
#include <cstdio>

#include "platform/host_platform.h"

// After the standard headers: its __cpuid macro would break the MSVC
// runtime's intrin.h declaration of the function of the same name.
#include <cpuid.h>

namespace {

bool CpuRunsFma() {
  unsigned int eax = 0, ebx = 0, ecx = 0, edx = 0;
  if (!__get_cpuid(1, &eax, &ebx, &ecx, &edx)) return false;
  constexpr unsigned int kFma = 1u << 12, kOsXsave = 1u << 27, kAvx = 1u << 28;
  if ((ecx & (kFma | kOsXsave | kAvx)) != (kFma | kOsXsave | kAvx)) return false;
  // FMA3 uses the AVX register state, which the OS must save (XCR0 bits 1, 2).
  uint32_t low = 0, high = 0;
  __asm__ volatile("xgetbv" : "=a"(low), "=d"(high) : "c"(0));
  return (low & 0x6u) == 0x6u;
}

#if defined(PINYON_SHIFT_CPU_BASELINE_V3)
bool CpuRunsV3() {
  unsigned int eax = 0, ebx = 0, ecx = 0, edx = 0;
  if (!__get_cpuid(1, &eax, &ebx, &ecx, &edx)) return false;
  constexpr unsigned int kMovbe = 1u << 22, kF16c = 1u << 29;
  if ((ecx & (kMovbe | kF16c)) != (kMovbe | kF16c)) return false;
  if (!__get_cpuid_count(7, 0, &eax, &ebx, &ecx, &edx)) return false;
  constexpr unsigned int kBmi1 = 1u << 3, kAvx2 = 1u << 5, kBmi2 = 1u << 8;
  if ((ebx & (kBmi1 | kAvx2 | kBmi2)) != (kBmi1 | kAvx2 | kBmi2)) return false;
  if (!__get_cpuid(0x80000001u, &eax, &ebx, &ecx, &edx)) return false;
  constexpr unsigned int kLzcnt = 1u << 5;
  return (ecx & kLzcnt) != 0;
}
#endif

const bool g_cpu_checked = [] {
  const char* missing = nullptr;
  if (!CpuRunsFma()) {
    missing = "FMA3";
  }
#if defined(PINYON_SHIFT_CPU_BASELINE_V3)
  else if (!CpuRunsV3()) {
    missing = "x86-64-v3 (AVX2, BMI2, MOVBE)";
  }
#endif
  if (missing) {
    char message[256];
    std::snprintf(message, sizeof(message),
                  "This build of Pinyon Shift uses %s instructions, which this CPU does not "
                  "have. Build it again on this PC; the build chooses the instructions the CPU "
                  "supports.",
                  missing);
    pinyon_shift::platform::ShowFatalError("Unsupported CPU", message);
    // ERROR_NOT_SUPPORTED, the code this exit has always had.
    pinyon_shift::platform::ExitImmediately(50);
  }
  return true;
}();

}  // namespace

#endif
