// Run against the probe runtime in separate record/replay processes.
#include <rex/cs_scheduler.h>
#include <rex/session_probe.h>
#include <cassert>
#include <chrono>
#include <cstdlib>
#include <string>
#include <thread>

int32_t& FLAGS_cs_schedule_mode_storage_();
int32_t& FLAGS_cs_schedule_call_budget_storage_();
std::string& FLAGS_cs_schedule_path_storage_();
namespace rex { void InitLoggingEarly(); }

int main(int argc, char** argv) {
  if (argc < 3) return 2;
  rex::InitLoggingEarly();
  const int mode = std::atoi(argv[1]);
  FLAGS_cs_schedule_mode_storage_() = mode;
  FLAGS_cs_schedule_path_storage_() = argv[2];
  FLAGS_cs_schedule_call_budget_storage_() = 1;
  uint64_t counter = 0, digest = 0;
  bool suspended = false;
  auto lane = [&](uint64_t id) {
    if ((mode == 1 && id == 19) || (mode == 2 && id == 11))
      std::this_thread::sleep_for(std::chrono::milliseconds(5));
    rex::session_probe::SetThreadTag(id);
    rex::cs_scheduler::Actor actor(id);
    if(id==19) {
      while(!suspended) rex::cs_scheduler::Point(0,rex::cs_scheduler::PointKind::kWait,true,100000);
      rex::cs_scheduler::Resume(11);
    }
    uint64_t previous = rex::cs_scheduler::Now();
    for (unsigned i = 0; i < 100; ++i) {
      rex::cs_scheduler::Call(argc > 3 && i == 40 ? 999 : i);
      assert(rex::cs_scheduler::Now() >= previous);
      previous = rex::cs_scheduler::Now();
      if(id==11 && i==40) { suspended=true; rex::cs_scheduler::Suspend(11); }
      // Ordinary shared memory is safe only if the permit serializes both lanes.
      digest = digest * 31 + id * ++counter;
      if (i % 7 == 0) rex::cs_scheduler::Sleep(10000);
    }
  };
  std::thread a(lane, 11), b(lane, 19);
  a.join(); b.join();
  assert(counter == 200);
  rex::cs_scheduler::Flush();
  std::printf("counter=%llu digest=%llu logical_ns=%llu\n",
              (unsigned long long)counter, (unsigned long long)digest,
              (unsigned long long)rex::cs_scheduler::Now());
}
