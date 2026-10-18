// Host SDK layout only; no guest data. Both tested ABIs use this layout.
#include <rex/ppc/context.h>
#include <cstdio>
#include <cstddef>
static_assert(offsetof(PPCContext, xer) == 272 && offsetof(PPCContext, reserved) == 280);
static_assert(offsetof(PPCContext, msr) == 288 && offsetof(PPCContext, vscr_sat) == 328);
static_assert(offsetof(PPCContext, last_indirect_target) == 332 &&
              offsetof(PPCContext, dispatch_address) == 336);
int main() {
  std::printf("{\"size\":%zu,\"r3\":%zu,\"r0\":%zu,\"r1\":%zu,\"r2\":%zu,"
              "\"r4\":%zu,\"lr\":%zu,\"ctr\":%zu,\"cr0\":%zu,\"fpscr\":%zu,"
              "\"f0\":%zu,\"v0\":%zu,\"xer\":%zu,\"reserved\":%zu,\"msr\":%zu,"
              "\"vscr_sat\":%zu,\"last_indirect_target\":%zu,\"dispatch_address\":%zu}\n",
              sizeof(PPCContext), offsetof(PPCContext,r3), offsetof(PPCContext,r0),
              offsetof(PPCContext,r1), offsetof(PPCContext,r2), offsetof(PPCContext,r4),
              offsetof(PPCContext,lr), offsetof(PPCContext,ctr), offsetof(PPCContext,cr0),
              offsetof(PPCContext,fpscr), offsetof(PPCContext,f0), offsetof(PPCContext,v0),
              offsetof(PPCContext,xer), offsetof(PPCContext,reserved), offsetof(PPCContext,msr),
              offsetof(PPCContext,vscr_sat), offsetof(PPCContext,last_indirect_target),
              offsetof(PPCContext,dispatch_address));
}
