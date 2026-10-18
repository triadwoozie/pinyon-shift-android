# Optimization survey: other static recompilers and ReXGlue projects

Issue #456. Researched 2026-10-11 at `dev` `08ecc8b` (ShiftGlue `a0f2b58`,
with the #431 work on `perf/guest-native-431`, not merged yet). It follows
the 2026-09-30 pass in
[PERFORMANCE_BACKLOG.md](PERFORMANCE_BACKLOG.md#final-pass-lessons-from-other-recomps-pb-6-to-pb-10)
(PB-6 to PB-10, mostly GPU) and repeats none of it. This pass is mostly
about the guest CPU and the runtime.

**Sources.** Public material only:

- READMEs, wikis, issues, pull requests and release notes;
- local read-only checkouts under `.local/`, cited as `project:path:line`.

Nothing was copied. Each project's licence decides what we may reuse:

| Project | Base | Licence | What we may take |
| --- | --- | --- | --- |
| [UnleashedRecomp](https://github.com/hedge-dev/UnleashedRecomp) (U) | XenonRecomp | GPL-3.0 | ideas only |
| [XenosRecomp](https://github.com/hedge-dev/XenosRecomp) | — | MIT | code, with attribution |
| [XenonRecomp](https://github.com/hedge-dev/XenonRecomp) | — | MIT | code, with attribution |
| re:Blue (Blue Dragon, RB) | ReXGlue 0.10.0 | BSD-3-Clause | code, with attribution |
| RaymanOriginsRecomp (RO) | XenonRecomp fork, then ReXGlue 0.10.0 | GPL-3.0 | ideas only |
| skate3recomp (S3) | ReXGlue 0.8 fork | none for the project (its SDK is BSD-style) | ideas only |
| svr07-recomp (SV) | ReXGlue 0.7.0, defaults | BSD-3-Clause | baseline only |
| [rexglue-sdk-yukes](https://github.com/HollywoodAkeem/rexglue-sdk-yukes) (YK) | upstream ReXGlue 0.7.3 | BSD-style | nothing ahead of us |
| [ReXGlue](https://github.com/rexglue/rexglue-sdk) upstream | — | BSD-style | code, with attribution |
| [Xenia](https://github.com/xenia-project/xenia) and [Canary](https://github.com/xenia-canary/xenia-canary/wiki/Options) | — | BSD-3-Clause | code, with attribution |
| [N64Recomp](https://github.com/N64Recomp/N64Recomp) and [RT64](https://github.com/rt64/rt64) | — | MIT | ideas |

Most Xenia Canary option descriptions come from a third-party config copy
of unknown age, and are marked *unverified*.

## Summary

1. **Hardly anyone measures CPU-side techniques.** Most published gains
   come from replacing GPU emulation with a native renderer.
   - The one CPU-side claim is XenonRecomp's: about 20 MB smaller and
     "several ms" faster frames with register locals, method unstated.
   - The few measured runtime gains are ReXGlue PR #498 (write-watch fault
     path) and PR #368 (`WaitMultiple` backoff).
2. **ShiftGlue already matches or leads the field on almost every runtime
   item:**
   - atomics;
   - critical-section spinning;
   - lock-free clocks;
   - the write-watch fault path;
   - stack accesses without the physical select (#431);
   - validation tooling. No project publishes golden images, differential
     CPU tests or an A/B method.
3. **The large lever we don't use is register locality.** UnleashedRecomp
   and re:Blue enable it; neither has measured it. It is blocked for us by
   interior-PC re-entry (PB-3.2), and #431 showed that naive CR
   synchronization does not pay. #467 made the CR part pay by following
   the PowerPC ABI at calls (see [Shortlist results](#shortlist-results)).
4. **The cheap, measurable gaps:**
   - POSIX `WaitMultiple` polls in fixed 1 ms sleeps (the Odin, the Deck,
     macOS);
   - a wider `[rexcrt]` table;
   - LTO of the generated code;
   - MXCSR state propagation;
   - per-guest-function Tracy zones as a profiling build.

## Techniques

Column notes:
- **Have it** refers to ShiftGlue or Pinyon Shift.
- **Effort**: S is days, M is a week or two, L is longer.
- **Risk** is the correctness risk: low, medium or high.

### 1. Code generation

| Technique | Projects and source | What it changes | Reported gain and method | Have it | Effort | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| Registers as C++ locals (`cr_`, `ctr_`, `xer_`, `reserved_`, `non_argument_`, `non_volatile_as_local`, `skip_lr`, `skip_msr`) | XenonRecomp [README](https://github.com/hedge-dev/XenonRecomp); U all eight on (`U:UnleashedRecompLib/config/SWA.toml:8-15`); RB six on (`RB:reblue_manifest.toml:32-37`); ReXGlue [config wiki](https://github.com/rexglue/rexglue-sdk/wiki/rexglue-CLI-Configuration-File); RO, SV, S3 off | Removes write-through `ctx` stores so Clang can forward values and drop dead ones | U: about 20 MB smaller and "several ms" faster frames, no method. Nobody else measured. | The SDK supports all eight; all are off. PB-3.2 explains why: `KeSetCurrentStackPointers` re-entry at `ctx.lr` and hooks that take `ctx`. #431 measured CR locals synchronized at every call: Odin guest instructions unchanged, binaries 13-26 % larger, not kept (LS-5.2). | L | high |
| Native setjmp/longjmp | XenonRecomp README, U `SWA.toml:17-27`, RB `reblue_manifest.toml:29-30`, ReXGlue config | Maps the guest's setjmp/longjmp to host code, which is what makes register locals safe across them | none | No addresses given in our manifest; the guest's longjmp goes through recompiled code | S (find addresses) | medium |
| Liveness-aware register sync | none found; the XenonRecomp README only warns to enable locals after the port runs | Spills only the fields live across each call | — | Not started. Next step after #431's CR result (shortlist E5). | M-L | medium |
| Guest-level inlining of tiny leaves | none found | Removes call overhead and the resume `switch` on entry | — | PB-3.6, deferred | M | medium |
| Byte-swap elision and CR/XER elision | none found; SV still passes XER into CR compares | — | — | No | — | — |
| Stack accesses without the physical-view select | none found; YK and SV branch on every access (`YK:include/rex/ppc/memory.h:37-52`) | r1-based accesses skip the 0xE0000000 offset select | — | **Yes**, our #431 work (`a0f2b58`): Odin guest instructions per second −17 % simulation, −18 % render, binaries −5.4-5.7 % | done | low |
| `stwcx.` as `std::atomic` compare-exchange, no fences around `mfmsr` | ReXGlue [0.10.0.16 nightly](https://github.com/rexglue/rexglue-sdk/releases) | Cheaper reservations | none | **Yes** (`f882e05`, PB-8.2) | done | — |
| Fences for `sync`/`lwsync`/`eieio` | ReXGlue 0.10.0.24 nightly | Correctness on ARM64; possible cost on x64 | none | aarch64 fences in `e19a22c`; x64 cost tracked by AP-7.1 | done | — |
| Hand-written AVX/NEON for VMX128 | none; every project uses SIMDe | — | — | Same SIMDe lowering; candidates in PB-3.7 and PB-3.10 | S each | low |
| Indirect calls through a perfect-hash table | XenonRecomp README; ReXGlue fills its table at startup ([interview](https://readonlymemo.com/rexglue-xbox-360-recompilation-interview/)) | O(1) lookup | none | Same table | — | — |
| Devirtualizing hot indirect calls | none found | Direct calls at known vtable sites | — | No. Candidate once a profile names a hot `bctrl` site. | M | medium |
| `u32` switch for jump tables | RO `docs/PROGRESS.md:56` (Clang dropped a bounds check on a `u64` switch) | Correctness, smaller tables | — | Ours switch on `u32` | done | — |
| `mftb` from `rdtsc` | RO `docs/PROGRESS.md:58` | Cheaper timebase | none | Lock-free guest clock (`4c77124`) | done | — |

### 2. Floating point and build flags

| Technique | Projects and source | What it changes | Reported gain and method | Have it | Effort | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| Flush-mode toggles only on scalar/VMX class change | XenonRecomp README (per-instruction toggling); SV emits 21,479 `disableFlushMode()` calls | Fewer `ldmxcsr`/FPCR writes | none | **Yes** (#466): each toggle skips the write when the mode already matches (`include/rex/ppc/context.h:180-197`), and the mode now carries across labels (ShiftGlue `99b7966`). Calls still reset it. | S-M | low |
| FTZ/DAZ permanently on in scalar mode | S3 (`SDK/include/rex/ppc/context.h:202-230`); comment blames denormal stalls for reverb crackle | Removes toggles; changes double-denormal results | none | No. Reverb is 12 % of the 8 Elite audio thread (#431), so the denormal question is worth a measurement. | S | medium |
| Fused multiply-add for `vmaddfp` | Canary `x64_extension_mask` (*unverified*) | One rounding as VMX defines it | none | PB-3.4, deferred | S | medium |
| Wider CPU baseline (`-march=sandybridge`, `x86-64-v3`) | U `CMakeLists.txt:37-40`; S3 optional `SKATE3_ENABLE_X86_64_V3`; SDK presets `x86-64-v2` | AVX/BMI2 code in generated units | none | Pinyon presets `-msse4.1`; an AVX2 GPU plugin was measured and not kept (PB-3.9). An `out/build/win-amd64-fma` tree exists but has no recorded result. | S | low |
| LTO of the generated code | U Release presets (`CMakePresets.json:58,114,166`) | Inlining across the 263 generated units and the runtime | none | Opt-in `PINYON_SHIFT_RECOMP_IPO`; neutral on Odin guest threads (#464), left off | S (build time is the cost) | low |
| PGO | none outside ShiftGlue | — | — | Opt-in `PINYON_SHIFT_RECOMP_PGO`; PGO trained on `fh1-race-retire` and measured on `fh1-race-sustained` over CR locals and the flush pass (4 runs each, medians): simulation 0.775 to 0.732 G (-5.5 %), audio 1.781 to 1.701 G (-4.5 %), render 0.650 to 0.641 G (-1.4 %), process 5.85 to 5.72 G. It pays, but shipping it needs a profile kept in step with the generated code, so it stays opt-in. | S | low |
| `-ffp-model=strict`, `-fno-strict-aliasing` | U, YK, S3, RO | Keeps results bit-exact | — | Same | — | — |
| `-mcmodel=small` on AArch64 | SDK default | Shorter address materialization | — | Yes (AP-0.3); x86 Linux uses `large` | done | — |

### 3. Memory

| Technique | Projects and source | What it changes | Reported gain and method | Have it | Effort | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| No `/proc/self/maps` parse on write-watch faults | ReXGlue [#441](https://github.com/rexglue/rexglue-sdk/issues/441), [PR #498](https://github.com/rexglue/rexglue-sdk/pull/498) (open, `PROCMAP_QUERY` ioctl) | No 1,800-line parse under the global lock per fault | Forza Horizon race, about 28 s runs, `frame_rate=60`: 41.4 and 42.4 → 49.3 fps, p99 32.8 → 26.8 ms; GPU command processor about 17 % waiting on the global lock | **Yes**, a different fix: the maps are read only for unhandled faults (`abdc0b2`, `38dc8c9`, `04252ca`) | done | — |
| Heap mutex separate from the global critical region | ReXGlue [#214](https://github.com/rexglue/rexglue-sdk/issues/214) (0.7.0 lock decomposition) | Less global-lock contention | none | Inherited from upstream | done | — |
| Coarser write-watch granularity | none; Canary records MMIO-faulting stores and emits non-trapping ones later (*unverified*) | Fewer faults and TLB shootdowns | none | No. Not worth it on FH1 (#470): about 7 write-watch triggers a race frame on the Odin | M | medium |
| Native allocator (o1heap) | U `kernel/heap.cpp:11-48`; ReXGlue `[rexcrt]` heap ([Memory wiki](https://github.com/rexglue/rexglue-sdk/wiki/Memory)); RB maps the Rtl heap functions | O(1) host allocation in place of guest heap code | none | No. The guest allocator `sub_82470CB0` is 0.05 % of the Odin race inclusive (#468), so it is not ported. | M | medium (heap layout visible to save/load) |
| Executable locked in RAM | U `preload_executable.cpp:84,93` (Windows) | No page-ins of the 100 MB executable on first use | none | No. Useful only if hitches correlate with hard faults. | S | low |
| Fastmem through fault patching | none found | — | — | — | — | — |

### 4. Threading and waits

| Technique | Projects and source | What it changes | Reported gain and method | Have it | Effort | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| Event-driven multi-object waits | U global generation counter for events (`U:kernel/imports.cpp:1003,1502-1537`); ReXGlue [PR #368](https://github.com/rexglue/rexglue-sdk/pull/368) (closed) backs `WaitMultiple` off from 1 to 16 ms | Wakes on signal instead of polling | PR #368, `perf` on Lollipop Chainsaw: `WaitMultiple` self time 6.43 → 2.73 %, audio thread about 68 → 21 % CPU | **No.** POSIX `WaitMultiple` (`src/core/threading_posix.cpp`) try-locks and sleeps `min(remaining, 1 ms)`; `0d85db4` only fixed the remainder. Windows uses the native multi-wait. | M | medium |
| Critical-section spin cap and read-first pause | RO (spin 64, yield 1024, sleep 50 µs); U atomic wait/notify; stock Xenia spins 255×256 | Less spinning | none | **Yes** (`4c77124`): process CPU 155 → 146 s, timer thread 4 → 0.4 s, VSync thread 1.4 → 0.03 s, 2 interleaved pairs | done | — |
| Guest priorities applied to host threads | U (`imports.cpp:634-647`); S3 maps +15 to Highest for audio (`xthread.cpp:826-840`) | Audio not starved | none | Partly; MMCSS "Games" measured worse (PB-8.4) | S | low |
| High-resolution sleep with a spin tail | RB `src/core/threading.cpp:83-106` (wake 1.5 ms early, pause to the deadline) | Precise short sleeps | none | High-resolution waitable timers on Windows (`threading_win.cpp:166`); the #431 render idle sleep uses a timer | done | — |
| Global-lock decomposition | ReXGlue 0.7.0; ShiftGlue `b13a1b5`, `6808abc`, `3f6ad5f` | Fewer contentions | — | Partly. PB-3.11 counts about 260 contentions per frame; #416 stutters on Android. | M | medium |
| Guest fibers on several host threads | [xenia-edge](https://github.com/has207/xenia-edge) commit titles | Parallel fibers | none | Not applicable: FH1's hot threads are plain threads | — | — |

### 5. Native replacements of guest functions

| Technique | Projects and source | What it changes | Reported gain and method | Have it | Effort | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| CRT memory functions as host code | U (`misc_impl.cpp:35-56`); RB `[rexcrt]` (`config/hooks/crt.toml`); ReXGlue [Function Overrides wiki](https://github.com/rexglue/rexglue-sdk/wiki/Function-Overrides) | memcpy/memmove/memset at host speed | none | **Yes**, PB-8.1: with PB-8.2, simulation thread 11-14 % fewer cycles a second | done | — |
| Wider `[rexcrt]` table: XMemCpy, XMemSet128, memset_vmx, memchr, str*, lstr* | RB `config/hooks/crt.toml`, `src/core/guest_crt.cpp:30-36` (atan2, atof) | More CRT at host speed | none | No. Our SDK supplies the natives (plus `wcs*`). The FH1 candidates are under 0.05 % each in the race (#465), so they stay unmapped. | S | low (`guest_native` verify covers it) |
| Hand-ported hot functions with verification | none outside ShiftGlue; U hand-replaces about 45 D3D device functions | Native bodies for profiled leaves | — | **Yes**, #431 `guest_native`: crt `pow` 2.1x, LZX 1.8x, 0 mismatches in verify runs | done | low |
| Native decompression or XAudio | none found | — | — | LZX native in #431; inflate (`sub_83108C08`) profiled and not ported | M | low |
| Pipelines precompiled from the game's asset load path | U README (streaming workers); RB `config/hooks/pso_predictor.toml` | No first-use pipeline stutter | U: "no shader stutter", qualitative | No. Our pipelines come from the driver cache (`1b278fd`) and a worker (`d7bdbba`, `fea02aa`). | M | low |

### 6. GPU (only items PB-6 to PB-10 do not cover)

| Technique | Projects and source | What it changes | Reported gain and method | Have it | Effort | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| Async depth-only pipelines | ReXGlue [PR #368](https://github.com/rexglue/rexglue-sdk/pull/368) | Depth-only pipelines (about 50 % of new ones) no longer compile synchronously | none for this part | **Yes** on Vulkan: the commands thread never builds or waits for pipelines (`fea02aa`) | done | — |
| Render-target cache that copies only on format, width or MSAA change | Xenia [2021 rewrite](https://xenia.jp/updates/2021/04/27/leaving-no-pixel-behind-new-render-target-cache-3x3-resolution-scaling.html) | Fewer EDRAM transfers | PIX on a GTX 1070: Halo 3 lobby 79.8 → 23.5 ms, Sonic '06 19.5 → 9.2 ms | Inherited | done | — |
| Selective resolve readback | ReXGlue [#447](https://github.com/rexglue/rexglue-sdk/issues/447) (full readback halved Tekken's speed) | Read back only the resolves the CPU consumes | qualitative | **Yes** (#399) | done | — |
| D3D-level occlusion culling of static items | S3 `73c19c8`, `2aef122`, `7b0d690` | Skips the game's draw dispatch for hidden items | S3 cvar comment: "roughly half" of per-item CPU in dense areas, no method | No. Needs FH1 scene hooks; native-renderer track. | L | medium |
| Fixed-rate logic with interpolated presentation | RB `engine/frame_clock.cpp:19-21`; RT64 dual renderer | High-rate presentation without changing the game's timestep | none | PB-9 and PB-10 | L | high |
| Ubershaders | RT64 | No compile stutter | — | No; not a fit for the Xenos translator | L | — |

### 7. Platform

| Technique | Projects and source | What it changes | Reported gain and method | Have it | Effort | Risk |
| --- | --- | --- | --- | --- | --- | --- |
| Steam Deck: whole-renderer pass | Project8Recomp v0.3.0 ([release notes mirror](https://newreleases.io/project/github/theokyr/Project8Recomp/release/v0.3.0)) | command processing, vertex/index handling, residency, texture/sampler reuse, bounded waits | Deck LCD, uncapped, fixed scene of about 2,665 draws: mean 25.00 → 21.94 ms, CPU 257.6 → 223.1 %, six cool-start runs | Our Deck work is LINUX_PORT_BACKLOG; the bounded waits are E1 | — | — |
| Steam Deck GPU clock pinned | U README:194-200 (anecdotal) | Avoids clock ramp | none | User setting, not code | — | — |
| ARM64 builds | RO Android (4 SDK patches), U and RB macOS | — | RO: Galaxy S23 60 fps, worst frame about 18 ms, own Present-hook log | **Yes** (Android, macOS) | done | — |
| `armv8.2-a+fp16+dotprod` | none | — | — | AP-7.1 proposes measuring it | S | low |

### 8. Tooling

| Technique | Projects and source | What it changes | Have it |
| --- | --- | --- | --- |
| Tracy zone in every guest function | YK `include/rex/ppc/context.h:86-100` (`REXGLUE_PROFILE_GUEST_FUNCTIONS`); ReXGlue PR [#261](https://github.com/rexglue/rexglue-sdk/pull/261), [#271](https://github.com/rexglue/rexglue-sdk/pull/271) | Inclusive times per guest function | Kept in the SDK template (`pch_h.inja`, `REXGLUE_PROFILE_GUEST_FUNCTIONS`), but Pinyon forces `REXGLUE_ENABLE_TRACY` off. Not needed: `simpleperf record --call-graph fp` gives inclusive times on the Odin (#469). |
| Missed-pipeline recorder and replay | RB (CSV of misses) | Builds a precompile list per route | No |
| In-game profiler overlay | U (F1) | Live GPU, present, fence and pipeline counts | Partly (thread sampler, JSON events) |
| Frame dump and offline replay | RO (`98f614b`), S3 (`2ad56d2`) | Renderer regression tests | **Yes** (`replay-fh1-frame.py`, `test-fh1-frame-replays.py`) |
| PPC instruction tests | XenonRecomp tests, S3 (179 tests), YK | Codegen correctness | **Yes** (`ppc_tests`) |
| Golden images, differential CPU tests, pinned-seed A/B | none found | — | **Yes**: seeded routes, image compare, `guest_native` verify, in-run cvar A/B |

## Ranked shortlist for FH1

Ranked by expected gain per unit of effort, given what #431 measured.
Every experiment must:
- keep the save hash, `expect-simulation-time` and route images unchanged;
- use the race route `fh1-race-sustained` from a pinned seed, unless the row
  says otherwise.

"Thread sampler share" means the guest thread's share in the thread sampler
(desktop) or in `simpleperf` per thread (Android, Deck). Odin numbers are
medians of per-interval guest instructions at the 30 fps limit (the #431
method).

| Rank | Experiment | Why | Route and metric | Platforms | Effort |
| --- | --- | --- | --- | --- | --- |
| E1 | **Done (ShiftGlue `4b8df95`, #463).** Odin in-run A/B, two runs: process CPU 1703 to 1606 and 1909 to 1744 ms a second, a polling guest thread 30 to 2 ms, the pipeline worker halved, audio thread 9-12 % less; frame time unchanged at the 30 fps limit. **Event-driven POSIX `WaitMultiple`** (a per-thread wait object signalled by any member; alertable waits too) | Fixed 1 ms polling costs CPU and adds up to 1 ms latency on every multi-wait. PR #368 measured a 68 → 21 % audio thread on Linux. | `fh1-race-sustained`: race median and p95 frame time; audio and render thread CPU ms a second; process ms a second | Odin, 8 Elite, Deck, macOS (desktop Windows unaffected) | M |
| E2 | **LTO of the generated code** (`PINYON_SHIFT_RECOMP_IPO`), then PGO measured on guest-thread CPU | Both are wired and off. U ships LTO. #431's stack change showed codegen size moves Odin instructions. | Odin and 8 Elite instructions a second per thread; desktop low-spec run (`-SimulateLowSpec`) thread sampler share; race median and p95; build time | all | S |
| E3 | **Wider `[rexcrt]` map**: XMemCpy, XMemSet128, memset_vmx, memchr, str* | RB maps all of them; the natives exist. Our profiles show the CRT printf engine and string work on the simulation thread. | First a census of call counts with `guest_native` verify; then per-thread instructions on the Odin and the simulation thread sampler share | all | S |
| E4 | **MXCSR/FPCR propagation** (PB-3.5) and a measurement of FTZ in scalar mode on the audio thread | Reverb is 12 % of the 8 Elite audio thread; S3 reports denormal stalls in reverb. | Audio thread instructions a second and median frame; FTZ only if `expect-simulation-time` and the save hash hold | all; x86 most | S-M |
| E5 | **Register locals with liveness, hot functions only** (LS-5.2 rescoped): `ctr_as_local` and `cr_as_local` with spills limited to live fields, plus native setjmp/longjmp addresses | The largest lever by U's and RB's choice. #431's call-site sync cost what it saved, so liveness is the hypothesis. | Odin per-thread instructions over 3 runs each; binary size; routes and replays | all | L |
| E6 | **Native guest allocator** (`sub_82470CB0`) or o1heap behind `[rexcrt]` | Profiled in #431 and on the render thread; U and RB replace their heaps | `guest_native` verify for equivalence of returned blocks, then per-thread instructions | all | M |
| E7 | **Profiling build with Tracy zones per guest function** | Inclusive times would rank callers, not only leaves | Tooling, no frame metric; overhead budget under 2x | desktop | S |
| E8 | **Write-watch at 64 KiB** (PB-3.11) | Faults and the global lock are the measured cost in PR #498 and #416 | Faults per frame, global-lock contentions per frame (wait-log cvar), p95 and p99 frame time on the Odin and the Deck | all; Android most | M |

### Shortlist results

Measured 2026-10-11 on the Odin, 30 fps limit, medians of per-interval
guest instructions a second, alternating builds.

| Rank | Issue | Result | Outcome |
| --- | --- | --- | --- |
| E1 | #463 | See the table above | Kept, ShiftGlue `4b8df95` |
| E2 | #464 | ThinLTO: `libmain.so` 0.3 % smaller; render 0.72-0.82 against 0.73-0.74 G, simulation 0.84-0.87 against 0.83-0.86 G, audio 1.85-1.93 against 1.84-1.86 G, 4 runs each. PGO trained on `fh1-race-retire` and measured on `fh1-race-sustained` over CR locals and the flush pass (4 runs each, medians): simulation 0.775 to 0.732 G (-5.5 %), audio 1.781 to 1.701 G (-4.5 %), render 0.650 to 0.641 G (-1.4 %), process 5.85 to 5.72 G. It pays, but shipping it needs a profile kept in step with the generated code, so it stays opt-in. | Neither kept; both stay opt-in |
| E3 | #465 | Census from call-graph profiles: the CRT candidates (`_stricmp`, `strstr`) are 0.02 % each, and 2,207 small byte-loop leaves add up to 1.98 % with none above 0.23 % (`site/findings/executable.toml`) | Not mapped |
| E4 | #466 | Bound: making every toggle a no-op saves about 5-8 % of guest instructions. Carrying the mode across labels drops 12,233 of 42,445 conditional disables and 207 of 2,153 enables: audio thread 1.87-2.00 to 1.76-1.86 G, render and simulation unchanged (3 runs each). FTZ in scalar mode not tried. | Kept, ShiftGlue `99b7966` |
| E5 | #467 | CR locals synchronized by the PowerPC ABI: calls, returns and tail calls write back only cr2-cr4 and reload nothing; hooks, setjmp, longjmp and SEH keep a full sync. Render 0.73-0.80 to 0.64-0.68 G (-11 %), simulation 0.85-0.90 to 0.74-0.79 G (-12 %), audio -3 %, process -7 %; desktop executable 9 % smaller. Combined with the flush pass (4 runs each): render 0.747 to 0.678 G, simulation 0.849 to 0.786 G, audio 1.926 to 1.796 G, process 6.27 to 6.02 G (medians). `ctr_as_local` not tried. | Kept, ShiftGlue `da39f62` |
| E6 | #468 | `sub_82470CB0` is 0.05 % inclusive in the race (its neighbour `sub_8246F268` 0.52 %) | Not ported |
| E7 | #469 | `simpleperf record --call-graph fp` works on the Odin with 1.2 % broken chains, so no Tracy build is needed. Audio thread: `MMIOHandler::CheckStore` 22 % inclusive, of which `XmaDecoder::WriteRegister` 15.6 % and inline `XmaContext::Work` 14.6 %. Commands thread: `ExecutePacket` 96 %, `IssueDrawImpl` 45 %. | Method recorded (PB-3.11) |
| E8 | #470 | Per race frame on the Odin: 7 write-watch triggers, 25 protect calls, 524 pages; arming the callbacks 0.59 % and the fault path under 0.5 % of the process; about 150 global-lock contentions with a median 0.18-0.84 ms blocked | Not changed |

Below the line (not scheduled): devirtualizing hot indirect calls (no hot
`bctrl` site in any profile yet), `armv8.2-a` (AP-7.1 owns it), executable
locking (no hard-fault evidence), pipeline prediction from asset loads
(stutter, not throughput; native-renderer track).

## Negative findings

- **Byte-swap elision, CR/XER elision, fastmem through fault patching and
  hand-written VMX128 lowerings:** no project has them, and nobody reports a
  measurement. Nothing to adopt; any attempt is original work.
- **Register locals all on, as U does:** not directly applicable.
  - Our runtime re-enters functions at an interior PC (`ctx.lr`), so
    `skip_lr` must stay off.
  - Hooks that take `ctx` would see stale fields.
  - CR locals synchronized at every call were measured in #431 and did not
    pay.
  - RB's configuration works because its SDK has no stack-switch re-entry.
- **Xenia-style host/guest stack synchronization** (Canary
  `enable_host_guest_stack_synchronization`, *unverified*): a JIT concern.
  A static recompiler with native setjmp/longjmp doesn't need it.
- **Guest fibers on several host threads** (xenia-edge): FH1's hot work runs
  on plain threads, so there is no fiber bottleneck to spread.
- **MMCSS registration and a 1 ms timer request:** measured worse in
  PB-8.4. RB's and U's priority changes are not a reason to retry them.
- **Replacing the GPU layer entirely** (U, RB, RO, S3): the only route to
  the large measured gains in this survey, such as RO's 30-55 → 59 fps on
  an M1. It is a separate long-term track (PB-10, native renderer), not an
  optimization.
- **Ubershaders** (RT64): they solve stutter for a fixed-function N64
  pipeline. For translated Xenos shaders the equivalent is the driver cache
  and worker compiles we already have.
- **ReXGlue PR #498:** not needed. ShiftGlue avoids the maps read on handled
  faults with a different fix, though PR #498's Forza Horizon numbers show
  how much the old path cost. PR #498 is by an outside contributor
  (`Dyotson`), so its numbers are independent evidence.
- **Async depth-only pipelines** (PR #368): already the case on Vulkan,
  where the commands thread never builds pipelines.

## Follow-up issues

Filed 2026-10-11. ShiftGlue has no issue tracker, so its items are filed
here with a `ShiftGlue:` prefix.

- **Pinyon Shift:**
  - E2: LTO and PGO of the generated code on guest threads, #464.
  - E3: more FH1 CRT functions through `[rexcrt]`, #465.
  - E5: register locals with liveness for the hot set (extends LS-5.2), #467.
  - E6: native guest allocator, #468.
- **ShiftGlue:**
  - E1: event-driven POSIX `WaitMultiple`, #463.
  - E4: carry the flush mode across labels and calls, #466.
  - E7: optional per-guest-function Tracy zones, #469.
  - E8: 64 KiB write-watch granularity, #470.
