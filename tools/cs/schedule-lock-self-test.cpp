#include <rex/cs_scheduler.h>
#include <rex/thread/mutex.h>
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <thread>

int32_t& FLAGS_cs_schedule_mode_storage_();
std::string& FLAGS_cs_schedule_path_storage_();
namespace rex { void InitLoggingEarly(); }

int main(int argc,char** argv) {
  if(argc!=3) return 2;
  rex::InitLoggingEarly();
  const int mode=std::atoi(argv[1]);
  FLAGS_cs_schedule_mode_storage_()=mode;
  FLAGS_cs_schedule_path_storage_()=argv[2];
  using namespace rex;
  std::atomic<bool> native_ready=false, go=false;
  bool held=false;
  unsigned count=0;
  std::thread native([&] {
    auto lock=thread::global_critical_region::AcquireDirect();
    native_ready=true;
    while(!go) std::this_thread::yield();
    std::this_thread::sleep_for(std::chrono::milliseconds(mode==1?5:15));
  });
  while(!native_ready) std::this_thread::yield();
  std::thread a([&] {
    cs_scheduler::Actor actor(11);
    const auto before=cs_scheduler::Now();
    go=true;
    auto lock=thread::global_critical_region::AcquireDirect();
    // Native backend contention consumes wall time, not a guest quantum.
    assert(cs_scheduler::Now()==before);
    held=true; ++count;
    cs_scheduler::Point(77,cs_scheduler::PointKind::kCallBudget);
    lock.unlock();
    cs_scheduler::Point(78,cs_scheduler::PointKind::kCallBudget);
    ++count;
  });
  std::thread b([&] {
    cs_scheduler::Actor actor(19);
    while(!held) cs_scheduler::Point(0,cs_scheduler::PointKind::kWait,true,100000);
    auto lock=thread::global_critical_region::AcquireDirect();
    ++count;
  });
  a.join(); b.join(); native.join();
  assert(count==3);
  cs_scheduler::Flush();
  std::printf("count=%u logical_ns=%llu\n",count,(unsigned long long)cs_scheduler::Now());
}
