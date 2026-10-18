// A diagnostic for hitches: while a title frame overruns
// pinyon_shift_hitch_sample_ms, sample the title thread's host stack every
// millisecond (and the threads named in pinyon_shift_hitch_sample_threads)
// and, when the frame ends, log where the time went by guest function.
// Generated functions are named from the recompiler's function table (no PDB
// needed); other host code as module+RVA.
#include "hitch_sampler.h"

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <tlhelp32.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <map>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <fmt/format.h>

#include <rex/cvar.h>
#include <rex/logging.h>
#include <rex/ppc/func.h>

REXCVAR_DEFINE_INT32(pinyon_shift_hitch_sample_ms, 0, "Pinyon Shift",
                     "Diagnostics: when a title frame runs longer than this many milliseconds, "
                     "sample the title thread's stack every millisecond until the frame ends and "
                     "log where the time went, by guest function; 0 turns it off")
    .range(0, 10000)
    .lifecycle(rex::cvar::Lifecycle::kHotReload);
REXCVAR_DEFINE_STRING(pinyon_shift_hitch_sample_threads, "", "Pinyon Shift",
                      "Diagnostics: with pinyon_shift_hitch_sample_ms, also sample the host "
                      "threads whose names contain one of these comma-separated words (for "
                      "example \"GPU Recorder,GPU Commands\") during a long title frame")
    .lifecycle(rex::cvar::Lifecycle::kRequiresRestart);

namespace {

constexpr int kMaxDepth = 48;
constexpr size_t kMaxSamples = 4096;

std::atomic<int64_t> g_frame_start_ns{0};
std::atomic<uint64_t> g_frame_seq{0};
std::atomic<DWORD> g_title_thread_id{0};

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// A generated function's body: the mapping's host pointer, or the target of
// the jump thunk it points to.
uintptr_t BodyStart(uintptr_t host) {
  const auto* code = reinterpret_cast<const uint8_t*>(host);
  for (int hops = 0; hops < 4; ++hops) {
    if (code[0] == 0xE9) {
      int32_t rel;
      std::memcpy(&rel, code + 1, 4);
      code = code + 5 + rel;
    } else if (code[0] == 0xFF && code[1] == 0x25) {
      int32_t rel;
      std::memcpy(&rel, code + 2, 4);
      code = *reinterpret_cast<const uint8_t* const*>(code + 6 + rel);
    } else {
      break;
    }
  }
  return reinterpret_cast<uintptr_t>(code);
}

class Symbols {
 public:
  Symbols() {
    for (size_t i = 0; PPCFuncMappings[i].guest != 0; ++i) {
      if (!PPCFuncMappings[i].host) continue;
      bodies_.emplace(BodyStart(reinterpret_cast<uintptr_t>(PPCFuncMappings[i].host)),
                      uint32_t(PPCFuncMappings[i].guest));
    }
  }

  // The guest function whose body contains pc, or 0.
  uint32_t Guest(uintptr_t pc) const {
    DWORD64 image_base = 0;
    const RUNTIME_FUNCTION* function = RtlLookupFunctionEntry(pc, &image_base, nullptr);
    if (!function) return 0;
    // Chained entries (split cold parts) point back to the primary one.
    while (function->UnwindData & RUNTIME_FUNCTION_INDIRECT) {
      function = reinterpret_cast<const RUNTIME_FUNCTION*>(
          image_base + (function->UnwindData & ~RUNTIME_FUNCTION_INDIRECT));
    }
    const auto it = bodies_.find(uintptr_t(image_base + function->BeginAddress));
    return it == bodies_.end() ? 0 : it->second;
  }

  std::string Label(uintptr_t pc) const {
    if (const uint32_t guest = Guest(pc)) return fmt::format("sub_{:08X}", guest);
    HMODULE module = nullptr;
    if (GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                               GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           reinterpret_cast<LPCWSTR>(pc), &module) &&
        module) {
      char path[MAX_PATH] = {};
      GetModuleFileNameA(module, path, MAX_PATH);
      const char* name = std::max(std::strrchr(path, '\\'), std::strrchr(path, '/'));
      return fmt::format("{}+{:X}", name ? name + 1 : path, pc - uintptr_t(module));
    }
    return fmt::format("{:X}", pc);
  }

 private:
  std::unordered_map<uintptr_t, uint32_t> bodies_;
};

struct Sample {
  std::array<uintptr_t, kMaxDepth> pcs;
  int depth;
};

// A sampled thread and what it did during the current long frame.
struct Target {
  std::string name;
  DWORD id = 0;
  HANDLE handle = nullptr;
  std::vector<Sample> samples;
};

// The suspended thread's stack, innermost first. Nothing here allocates: the
// thread may hold the heap lock.
int Walk(HANDLE thread, std::array<uintptr_t, kMaxDepth>& pcs) {
  CONTEXT context = {};
  context.ContextFlags = CONTEXT_CONTROL | CONTEXT_INTEGER;
  if (!GetThreadContext(thread, &context)) return 0;
  int depth = 0;
  while (depth < kMaxDepth && context.Rip) {
    pcs[depth++] = uintptr_t(context.Rip);
    DWORD64 image_base = 0;
    const RUNTIME_FUNCTION* function = RtlLookupFunctionEntry(context.Rip, &image_base, nullptr);
    if (!function) {
      // A leaf function: the return address is on top of the stack.
      context.Rip = *reinterpret_cast<const DWORD64*>(context.Rsp);
      context.Rsp += 8;
    } else {
      void* handler_data = nullptr;
      DWORD64 establisher = 0;
      RtlVirtualUnwind(UNW_FLAG_NHANDLER, image_base, context.Rip,
                       const_cast<PRUNTIME_FUNCTION>(function), &context,
                       &handler_data, &establisher, nullptr);
    }
  }
  return depth;
}

void SampleOnce(Target& target) {
  if (!target.handle || target.samples.size() >= kMaxSamples) return;
  Sample sample;
  if (SuspendThread(target.handle) == DWORD(-1)) return;
  sample.depth = Walk(target.handle, sample.pcs);
  ResumeThread(target.handle);
  target.samples.push_back(sample);
}

template <typename Map>
std::string Top(const Map& counts, size_t samples, size_t n) {
  std::vector<std::pair<size_t, std::string>> sorted;
  for (const auto& [name, count] : counts) sorted.emplace_back(count, name);
  std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
  std::string text;
  for (size_t i = 0; i < std::min(n, sorted.size()); ++i) {
    text += fmt::format("{}{} {}%", i ? ", " : "", sorted[i].second,
                        sorted[i].first * 100 / std::max<size_t>(samples, 1));
  }
  return text;
}

void Report(const Symbols& symbols, const Target& target, double frame_ms) {
  std::map<std::string, size_t> leaf, guest_leaf, inclusive, paths;
  for (const Sample& sample : target.samples) {
    if (!sample.depth) continue;
    ++leaf[symbols.Label(sample.pcs[0])];
    std::string path;
    bool found_guest = false;
    std::vector<uint32_t> seen;
    for (int i = 0; i < sample.depth; ++i) {
      const uint32_t guest = symbols.Guest(sample.pcs[i]);
      if (!guest) continue;
      if (!found_guest) {
        ++guest_leaf[fmt::format("sub_{:08X}", guest)];
        found_guest = true;
      }
      if (std::find(seen.begin(), seen.end(), guest) == seen.end()) {
        seen.push_back(guest);
        ++inclusive[fmt::format("sub_{:08X}", guest)];
        if (seen.size() <= 8) path += fmt::format("{}{:08X}", seen.size() > 1 ? "<" : "", guest);
      }
    }
    // The host code under the innermost guest function names the service
    // (a kernel call, a file read, a wait). Host-only threads get their
    // host frames instead.
    if (path.empty()) {
      for (int i = 1; i < std::min(sample.depth, 6); ++i)
        path += (i > 1 ? "<" : "") + symbols.Label(sample.pcs[i]);
    }
    ++paths[symbols.Label(sample.pcs[0]) + " @ " + path];
  }
  const size_t n = target.samples.size();
  const std::string who = target.name.empty() ? "title" : target.name;
  REXLOG_WARN("Hitch: title frame {:.1f} ms, {} samples, {} thread {:X}", frame_ms, n, who,
              target.id);
  REXLOG_WARN("Hitch:   leaf: {}", Top(leaf, n, 8));
  if (!guest_leaf.empty()) {
    REXLOG_WARN("Hitch:   innermost guest: {}", Top(guest_leaf, n, 8));
    REXLOG_WARN("Hitch:   inclusive guest: {}", Top(inclusive, n, 12));
  }
  REXLOG_WARN("Hitch:   paths: {}", Top(paths, n, 4));
}

// The process's threads whose description contains one of the words.
std::vector<Target> NamedThreads(const std::string& words) {
  std::vector<std::string> wanted;
  for (size_t start = 0; start <= words.size();) {
    const size_t end = std::min(words.find(',', start), words.size());
    if (end > start) wanted.push_back(words.substr(start, end - start));
    start = end + 1;
  }
  std::vector<Target> found;
  if (wanted.empty()) return found;
  HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
  if (snapshot == INVALID_HANDLE_VALUE) return found;
  THREADENTRY32 entry = {sizeof(entry)};
  for (BOOL more = Thread32First(snapshot, &entry); more; more = Thread32Next(snapshot, &entry)) {
    if (entry.th32OwnerProcessID != GetCurrentProcessId()) continue;
    HANDLE thread = OpenThread(THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT |
                                   THREAD_QUERY_LIMITED_INFORMATION,
                               FALSE, entry.th32ThreadID);
    if (!thread) continue;
    PWSTR description = nullptr;
    std::string name;
    if (SUCCEEDED(GetThreadDescription(thread, &description)) && description) {
      for (const wchar_t* c = description; *c; ++c) name.push_back(char(*c < 128 ? *c : '?'));
      LocalFree(description);
    }
    const bool match = std::any_of(wanted.begin(), wanted.end(), [&](const std::string& word) {
      return name.find(word) != std::string::npos;
    });
    if (match) {
      found.push_back({name, entry.th32ThreadID, thread, {}});
    } else {
      CloseHandle(thread);
    }
  }
  CloseHandle(snapshot);
  return found;
}

void Sampler() {
  const Symbols symbols;
  Target title;
  title.samples.reserve(kMaxSamples);
  const std::string words = REXCVAR_GET(pinyon_shift_hitch_sample_threads);
  std::vector<Target> named;
  auto last_scan = std::chrono::steady_clock::time_point{};
  uint64_t sampled_seq = 0;
  int64_t sampled_start = 0;
  bool sampling = false;
  // A high-resolution timer: Sleep(1) can round up to the 15.6 ms tick.
  HANDLE timer = CreateWaitableTimerExW(nullptr, nullptr, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION,
                                        TIMER_ALL_ACCESS);
  while (true) {
    if (timer) {
      LARGE_INTEGER due;
      due.QuadPart = -10000;  // 1 ms
      SetWaitableTimer(timer, &due, 0, nullptr, nullptr, FALSE);
      WaitForSingleObject(timer, INFINITE);
    } else {
      Sleep(1);
    }
    const int32_t threshold_ms = REXCVAR_GET(pinyon_shift_hitch_sample_ms);
    const uint64_t seq = g_frame_seq.load(std::memory_order_acquire);
    const int64_t start = g_frame_start_ns.load(std::memory_order_acquire);
    if (sampling && seq != sampled_seq) {
      const double frame_ms = double(start - sampled_start) / 1e6;
      Report(symbols, title, frame_ms);
      title.samples.clear();
      for (Target& target : named) {
        Report(symbols, target, frame_ms);
        target.samples.clear();
      }
      sampling = false;
    }
    if (threshold_ms <= 0) continue;
    if (NowNs() - start < int64_t(threshold_ms) * 1000000) continue;
    const DWORD id = g_title_thread_id.load(std::memory_order_acquire);
    if (id != title.id) {
      if (title.handle) CloseHandle(title.handle);
      title.handle = OpenThread(THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT |
                                    THREAD_QUERY_INFORMATION,
                                FALSE, id);
      title.id = id;
    }
    if (!sampling) {
      // Threads come and go (the recorder starts after the title's first
      // frames); look for the named ones again at the start of a long
      // frame, at most every 10 seconds.
      const auto now = std::chrono::steady_clock::now();
      if (!words.empty() && now - last_scan > std::chrono::seconds(10)) {
        for (Target& target : named) CloseHandle(target.handle);
        named = NamedThreads(words);
        for (Target& target : named) target.samples.reserve(kMaxSamples);
        last_scan = now;
      }
      sampling = true;
      sampled_seq = seq;
      sampled_start = start;
    }
    SampleOnce(title);
    for (Target& target : named) SampleOnce(target);
  }
}

}  // namespace

namespace pinyon_shift::hitch {

void NoteFrame() {
  g_frame_start_ns.store(NowNs(), std::memory_order_release);
  g_frame_seq.fetch_add(1, std::memory_order_acq_rel);
  static bool started = [] {
    g_title_thread_id.store(GetCurrentThreadId(), std::memory_order_release);
    std::thread(Sampler).detach();
    return true;
  }();
  (void)started;
}

}  // namespace pinyon_shift::hitch
