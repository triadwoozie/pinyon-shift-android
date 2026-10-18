// NN-0.8 calibration bench: the generated code's support macros, with one
// switch per translation overhead (docs/NEAR_NATIVE_RESEARCH.md NN-0.8).
//
// This is the pch the generator emits (rexglue pch_h.inja), cut down to what a
// single guest function needs, so a generated function body copied into the
// bench (never committed: docs/LEGAL.md) compiles as it does in the game. Each
// overhead can be turned off on its own:
//
//   NN08_NOVOL    guest loads and stores (heap and stack) are not volatile
//   NN08_NOSWAP   scalar guest loads and stores do not byte swap; vector
//                 loads keep their lane-reversing shuffle (removing it would
//                 change lane semantics), so data read both ways is wrong
//   NN08_NOFLUSH  no MXCSR/FPCR writes for the guest's flush-to-zero mode
//   NN08_INLINE   helpers (save/restore, callees) in the caller's unit, no
//                 musttail, so the compiler may inline them
//   NN08_NOLR     link-register stores (ctx.lr = ...) dropped (prepare.py)
//   NN08_LOCALS   guest registers in host locals inside a function, written
//                 back to ctx only at calls and returns (prepare.py)
//   NN08_FIXED    (AArch64) base compiled with -ffixed-x27 -ffixed-x28: the
//                 cost of taking two callee-saved registers from clang
//   NN08_PIN      (AArch64, NN-1.12) ctx in x27 and the guest memory base in
//                 x28 for the whole guest call tree, read through global
//                 named register variables; guest functions take no
//                 arguments (prepare.py rewrites the calls), and the entry
//                 saves and restores the caller's x27/x28
//   NN08_PIN_SEP  with NN08_PIN: ctx as a plain reference plus a
//                 separate-storage assumption instead of a restrict body
//   NN08_NORESTRICT  base with ctx not __restrict: what the noalias ctx
//                 argument is worth (the AArch64 load/store pair optimizer
//                 uses it; it ignores the scoped metadata a pinned body has)
#pragma once

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <climits>
#include <cmath>

#include <rex/ppc/context.h>
#include <rex/ppc/intrinsics.h>

using rex::ppc::VectorMaskL;
using rex::ppc::VectorMaskR;
using rex::ppc::VectorShiftTableL;
using rex::ppc::VectorShiftTableR;

using u8 = uint8_t;
using u16 = uint16_t;
using u32 = uint32_t;
using u64 = uint64_t;

// Function definitions: plain C++ functions inside the variant's namespace.
#ifdef NN08_INLINE
#define NN08_FN_ATTR static inline
#else
#define NN08_FN_ATTR __attribute__((noinline))
#endif
#ifdef NN08_PIN
#if !defined(__aarch64__)
#error "NN08_PIN is AArch64 only"
#endif
// Global named register variables need the registers reserved in every unit
// that names them (-ffixed-x27 -ffixed-x28, build.py). Code compiled without
// the flag (the harness, libc) may use x27/x28 but preserves them, as AAPCS64
// makes them callee-saved.
register PPCContext* nn08_ctx_reg asm("x27");
register uint8_t* nn08_base_reg asm("x28");
#define REX_FUNC_DECL(name) void name()
#define DECLARE_REX_FUNC(name) NN08_FN_ATTR REX_FUNC_DECL(name)
#ifdef NN08_PIN_SEP
// "pinsep": ctx is a plain reference to *x27, with only "guest memory never
// aliases ctx" stated (__builtin_assume_separate_storage). Clang uses that
// assumption only in queries that carry a context instruction, so it recovers
// less than the shipped __restrict parameter does.
#define DEFINE_REX_FUNC(name) NN08_FN_ATTR REX_FUNC_DECL(name)
#define REX_TAIL_CALL(name) [[clang::musttail]] return name()
#define REX_FUNC_PROLOGUE()                      \
  PPCContext& ctx = *nn08_ctx_reg;               \
  uint8_t* base = nn08_base_reg;                 \
  __builtin_assume_separate_storage(&ctx, base); \
  __builtin_assume(((size_t)base & 0x1F) == 0)
#else
// "pin": the body keeps the shipped __restrict ctx parameter by being an
// always-inline function that a no-argument wrapper calls with *x27 and x28.
// Restrict alone would be unsound here: callees reach ctx through x27, not
// through an argument, so clang could keep ctx fields in registers across
// calls. The empty asm captures &ctx at entry, after which LLVM's inliner
// gives the calls no noalias scope (they may read and write ctx), while guest
// memory and stack accesses keep it. Tail calls become sibling calls after
// inlining (musttail needs matching signatures, which the body lacks).
#define DEFINE_REX_FUNC(name)                                                                  \
  static inline __attribute__((always_inline)) void name##_nn08_body(PPCContext& __restrict, \
                                                                      uint8_t*);               \
  NN08_FN_ATTR void name() { name##_nn08_body(*nn08_ctx_reg, nn08_base_reg); }                \
  static inline __attribute__((always_inline)) void name##_nn08_body(                         \
      PPCContext& __restrict ctx, uint8_t* base)
#define REX_TAIL_CALL(name) \
  do {                      \
    name();                 \
    return;                 \
  } while (false)
#define REX_FUNC_PROLOGUE()             \
  __asm__ volatile("" : : "r"(&ctx));   \
  __builtin_assume(((size_t)base & 0x1F) == 0)
#endif
// The host-to-guest transition: the caller (compiled without -ffixed) may
// hold its own values in x27/x28.
#define NN08_PIN_ENTER(c, b)                       \
  PPCContext* const nn08_saved_ctx = nn08_ctx_reg; \
  uint8_t* const nn08_saved_base = nn08_base_reg;  \
  nn08_ctx_reg = &(c);                             \
  nn08_base_reg = (b)
#define NN08_PIN_LEAVE()               \
  do {                                 \
    nn08_ctx_reg = nn08_saved_ctx;     \
    nn08_base_reg = nn08_saved_base;   \
  } while (0)
#define REX_CALL_FUNC(x) x()
#else
#ifdef NN08_NORESTRICT
#define REX_FUNC_DECL(name) void name(PPCContext& ctx, uint8_t* base)
#else
#define REX_FUNC_DECL(name) void name(PPCContext& __restrict ctx, uint8_t* base)
#endif
#define DECLARE_REX_FUNC(name) NN08_FN_ATTR REX_FUNC_DECL(name)
#define DEFINE_REX_FUNC(name) NN08_FN_ATTR REX_FUNC_DECL(name)
#define REX_CALL_FUNC(x) x(ctx, base)

#if defined(__clang__) && !defined(NN08_INLINE)
#define REX_TAIL_CALL(name) [[clang::musttail]] return name(ctx, base)
#else
#define REX_TAIL_CALL(name) \
  do {                      \
    name(ctx, base);        \
    return;                 \
  } while (false)
#endif

#define REX_FUNC_PROLOGUE() __builtin_assume(((size_t)base & 0x1F) == 0)
#endif

// The physical-view offset: 0 on Windows and on 4 KB-page Linux/Android
// builds (REX_PHYSICAL_HEAP_4K_PAGES, the shipped default).
#define REX_PHYS_HOST_OFFSET(addr) 0u
#define REX_RAW_ADDR(x) (base + (u32)(x) + REX_PHYS_HOST_OFFSET(x))

#ifdef NN08_NOVOL
#define NN08_QUAL
#else
#define NN08_QUAL volatile
#endif

#ifdef NN08_NOSWAP
#define NN08_BS16(v) ((u16)(v))
#define NN08_BS32(v) ((u32)(v))
#define NN08_BS64(v) ((u64)(v))
#else
#define NN08_BS16(v) __builtin_bswap16(v)
#define NN08_BS32(v) __builtin_bswap32(v)
#define NN08_BS64(v) __builtin_bswap64(v)
#endif

#ifdef NN08_NOVOL
// Plain accesses through memcpy so the compiler is free to combine them; the
// guest memory may be unaligned.
template <typename T>
static inline T nn08_ld(const uint8_t* p) {
  T v;
  std::memcpy(&v, p, sizeof(T));
  return v;
}
template <typename T>
static inline void nn08_st(uint8_t* p, T v) {
  std::memcpy(p, &v, sizeof(T));
}
#define NN08_LD(T, p) nn08_ld<T>(p)
#define NN08_ST(T, p, v) nn08_st<T>((p), (T)(v))
#else
#define NN08_LD(T, p) (*(volatile T*)(p))
#define NN08_ST(T, p, v) (*(volatile T*)(p) = (T)(v))
#endif

#define REX_LOAD_U8(x) NN08_LD(u8, base + (u32)(x))
#define REX_LOAD_U16(x) NN08_BS16(NN08_LD(u16, base + (u32)(x)))
#define REX_LOAD_U32(x) NN08_BS32(NN08_LD(u32, base + (u32)(x)))
#define REX_LOAD_U64(x) NN08_BS64(NN08_LD(u64, base + (u32)(x)))
#define REX_STORE_U8(x, y) NN08_ST(u8, base + (u32)(x), (y))
#define REX_STORE_U16(x, y) NN08_ST(u16, base + (u32)(x), NN08_BS16((u16)(y)))
#define REX_STORE_U32(x, y) NN08_ST(u32, base + (u32)(x), NN08_BS32((u32)(y)))
#define REX_STORE_U64(x, y) NN08_ST(u64, base + (u32)(x), NN08_BS64((u64)(y)))

#define REX_STACK_LOAD_U8(x) REX_LOAD_U8(x)
#define REX_STACK_LOAD_U16(x) REX_LOAD_U16(x)
#define REX_STACK_LOAD_U32(x) REX_LOAD_U32(x)
#define REX_STACK_LOAD_U64(x) REX_LOAD_U64(x)
#define REX_STACK_STORE_U8(x, y) REX_STORE_U8(x, y)
#define REX_STACK_STORE_U16(x, y) REX_STORE_U16(x, y)
#define REX_STACK_STORE_U32(x, y) REX_STORE_U32(x, y)
#define REX_STACK_STORE_U64(x, y) REX_STORE_U64(x, y)

#define REX_MM_LOAD_U8(x) REX_LOAD_U8(x)
#define REX_MM_LOAD_U16(x) REX_LOAD_U16(x)
#define REX_MM_LOAD_U32(x) REX_LOAD_U32(x)
#define REX_MM_LOAD_U64(x) REX_LOAD_U64(x)
#define REX_MM_STORE_U8(x, y) REX_STORE_U8(x, y)
#define REX_MM_STORE_U16(x, y) REX_STORE_U16(x, y)
#define REX_MM_STORE_U32(x, y) REX_STORE_U32(x, y)
#define REX_MM_STORE_U64(x, y) REX_STORE_U64(x, y)

#if defined(__aarch64__)
#define REX_PPC_SYNC() __atomic_thread_fence(__ATOMIC_SEQ_CST)
#define REX_PPC_LWSYNC() __atomic_thread_fence(__ATOMIC_ACQ_REL)
#define REX_PPC_EIEIO() __atomic_thread_fence(__ATOMIC_SEQ_CST)
#define REX_PPC_ISYNC() __atomic_thread_fence(__ATOMIC_ACQUIRE)
#else
#define REX_PPC_SYNC() ((void)0)
#define REX_PPC_LWSYNC() ((void)0)
#define REX_PPC_EIEIO() ((void)0)
#define REX_PPC_ISYNC() ((void)0)
#endif

// The bench never takes an indirect call; reaching one is a setup error.
#define REX_CALL_INDIRECT_FUNC(x) std::abort()
#define REX_UNIMPLEMENTED(addr, opcode) std::abort()

// Flush-mode writes. prepare.py rewrites ctx.fpscr.<op>() into NN08_FPSCR(<op>).
#ifdef NN08_NOFLUSH
#define NN08_FPSCR(op) ((void)0)
#define REX_SET_FLUSH_MODE(enable) ((void)0)
#else
#define NN08_FPSCR(op) ctx.fpscr.op()
#define REX_SET_FLUSH_MODE(enable) \
  do { if (enable) ctx.fpscr.enableFlushMode(); else ctx.fpscr.disableFlushMode(); } while (0)
#endif
