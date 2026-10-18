# Clean-slate research: rebuilding the recompiler, runtime and renderer

Written 2026-10-17 at `dev` `4e26743` (ShiftGlue `79d3e6f`). This page
answers one question: if ReXGlue and Pinyon Shift were rebuilt from
nothing, with what the project knows today and no limit on time, what
would be built differently to run as much of the game as possible as
native CPU and GPU work?

It follows [NEAR_NATIVE_RESEARCH.md](NEAR_NATIVE_RESEARCH.md) (NN),
[D3D_RENDERER_RESEARCH.md](D3D_RENDERER_RESEARCH.md) (DL) and
[NEXT_LEVEL_RESEARCH.md](NEXT_LEVEL_RESEARCH.md) (NL), and cites their
measurements instead of repeating them. Nothing was built or run for this
page. Every saving it names is an estimate, and every proposal has a
cheap probe that decides it before any large work.

The terms are the NN ones: the yardstick is CPU milliseconds per frame
over all threads plus resident memory on the Odin 2 Portal and the Steam
Deck LCD; fidelity is "good enough and playable", checked as in
[NN Verification](NEAR_NATIVE_RESEARCH.md#verification); the desktop is
secondary.

Current Stage 0 decisions are in [Progress](#progress): Round 3 rejects
CS-2 at its conservative context gate; the serialized CS-1 prototype still
fails replay. CS-3 graphics DGC stays on hold and CS-4 remains rejected.
The estimates below preserve the original proposal, not accepted gains.

## Summary

- **Stage 0 closed the plan (2026-10-18).** All four structural changes
  failed their gates; detail in
  [CLEAN_SLATE_STAGE0.md](CLEAN_SLATE_STAGE0.md) and
  [Progress](#progress):
  - CS-1: replay diverged even with guest threads serialised under a
    logical clock (11.7 s into a race, and a deadlock on an untaped
    native completion), at 8.7x the frame time.
  - CS-2: the exact, sound count of removable `ctx` traffic is 0.9 % of
    hot-thread instructions against 15 %. The 24-30 % ceiling needs
    proven targets for 48,101 indirect calls, which no current tooling
    gives; that is the only reason to reopen it.
  - CS-3: the Deck's DGC binds compute pipelines only, the Odin has no
    DGC, and Deck draws run 2.5 to a pipeline (72 % singletons), so
    per-pipeline indirect batching has nothing to batch.
  - CS-4: fixed-address mapping works on Turnip and RADV, but the game
    waits on its own fences: real completion cost +26-31 % frame time
    on the Odin and +122-127 % on the Deck, even fully asynchronous.

  The current architecture stays. The probes are compiled only with
  `REXGLUE_ENABLE_CS_PROBES` and `REXGPU_FH1_ORDERED_CENSUS`.
- The rest of this page is the plan as written before Stage 0.

- **A rewrite only pays where the current architecture blocks a gain.**
  NL, NN and DL already measured most single techniques on the current
  base, and many lost: PGO (NN-1.4, -0.13 to -0.72 %), devirtualisation
  (NN-1.5, 0.035 ms), interprocedural register allocation (NN-1.10,
  bound 1.4-2.0 %), non-volatile registers as locals (NL-1.4, 20-27 %
  slower), reversed guest memory, an engine-level scene renderer, and the
  D3D-level renderer (DL-3, -0.9 ms against -3 ms). A rebuild that
  repeats those ideas with cleaner code would land on the same numbers.
  This page keeps only the changes a rebuild makes possible and the
  current base cannot do.
- **Four structural changes are left unmeasured:**
  1. **CS-1, a session replay oracle.** Deterministic whole-session
     record and replay, with a reference CPU interpreter. None exists
     today: the lockstep verifier compares two compiled versions of a
     function, and the save hash is not deterministic (AP-0.5). Every
     other item is gated on it.
  2. **CS-2, a recompiler that lowers through an IR.** It would carry
     whole-program facts (which `ctx` slots each function can observe,
     alias classes of guest memory, FP mode as a static property), lower
     them straight to LLVM IR, and stop generating C++ text. It targets
     the 43-46 % of host instructions that are bookkeeping (NL Summary)
     where register locals failed: the title is not ABI-clean, and C++
     locals spill.
  3. **CS-3, a GPU-driven front end.** The CPU copies raw register and
     constant writes into a GPU ring. A compute pass derives each draw's
     state and writes indirect commands. This targets the per-draw floor
     the CPU-side work reached: about 980 ns derivation (NL-3.2) and
     6.97 µs process CPU per draw on the Odin. DL found that parsing is
     not the cost; the backend draw path and register writes are.
  4. **CS-4, one memory for guest and GPU.** The guest arena is allocated
     as device memory and mapped at its fixed host address
     (`VK_EXT_map_memory_placed` or the platform equivalent), so the
     512 MB mirror and its uploads go. Importing host memory was refuted
     (NN Considered and rejected); exporting device memory to a fixed
     address was not tried.
- **Where the time is.** On the Odin, guest code is 48 % of process
  cycles and the GPU front end (decode, record, submit) is about 12 ms of
  38.8 ms. On the Deck the front end is most of a 59-74 ms frame
  (recorder 11.8-15.7, decoder 12-12.9, submission 8.6-9.6 ms), and the
  APU sits at its 15 W limit, so CPU saved there goes to the GPU as
  power. CS-3 is aimed at the largest pool. CS-2 is aimed at the second.
- **End state (estimate, wide).** If CS-2 removes a third of the
  remaining bookkeeping instructions and CS-3 brings recording near the
  0.6-1.0 µs per draw a native engine spends (NN "What native means"),
  the Odin falls from 38.8 ms toward 22-28 ms. The Deck gains more,
  because its front end is larger. CS-4 would return most of the
  mirror's 512 MB of device memory where it works. Each figure rests on
  an unproven premise that the matching probe tests first.

## What the measurements already settle

A rebuild inherits these results. They are the constraints the design
below respects.

| Finding | Source | Consequence for a rebuild |
| --- | --- | --- |
| Guest threads are flat: no function above 3 % | [lessons](https://arcanite24.github.io/pinyon-shift/lessons/#find-where-the-frame-actually-goes) | No single function rewrite moves the frame; codegen must improve everything at once, or not at all |
| Static bookkeeping is 43-46 % of host instructions (`ctx` moves 24 %, select 14 %, `bswap` 4.7 %), before NL-1.1 to NL-1.3 removed 28.4 % | NL Summary, Progress | `ctx` traffic is the remaining large codegen pool |
| The title reads a caller's volatile register through its callee (`sub_823F3048`, `r6`) and reads registers through `ctx` from indirectly reached code | NL-0.1, NL-1.4 | Liveness must come from callee bodies across the whole program, not from the calling convention |
| Non-volatile locals: `.text` +66 %, guest code 20-27 % slower | NL-1.4 | Publishing at every call and spilling 128-bit locals costs more than it saves; locals need exact publish sets |
| IPA register passing bounded at 1.4-2.0 % after NN-1.2 | NN-1.10 | Register passing alone is not the gain; deleting `ctx` stores is |
| FP-mode writes are 65-70 % of physics cycles; native twins remove 47-74 % | NN-0.8 | FP mode must be a compile-time property |
| Instruction-to-CPU elasticity is 0.52-0.6 | #467 | A 10 % instruction cut is worth about 5-6 % CPU |
| Per-draw reuse on the CPU has a floor (about 20 small steps) | NL-3.2, [lessons](https://arcanite24.github.io/pinyon-shift/lessons/#find-where-the-frame-actually-goes) | Only moving the work off the CPU goes below it |
| Replaying repeating command buffers saved 2.54 ms against a 6 ms bar; 42 % of drawn draws sit in skippable buffers | G0 / NN-0.10 | Skipping whole buffers on the CPU is not enough; constants churn |
| Parsing is about a fifth of decoder instructions; the decoder is stall-bound; the backend draw path is 22 %, register writes 14 % | DL-3 | A D3D-level front end that still records per draw on the CPU cannot pass |
| Recorder templates: 1,205 against 1,115 ns per draw | NN Considered and rejected | CPU-side templates do not help |
| Emulation structure is about 3 ms of the Odin's 15.7 ms GPU frame; uploads split the main scene into 17-23 render passes | NN-0.11 | Upload-driven pass splits are a GPU cost that one memory (CS-4) would remove |
| Importing host memory into Vulkan: no `external_memory_host` on Turnip; fences written at decode tear with three frames in flight | NN Considered and rejected | CS-4 must export device memory, and the GPU must read guest data at a guest-consistent time |
| Endian overhead is about 5 % statically, bounded 5-14 % | NN Considered and rejected | Native-endian memory is not worth its cost |
| Engine-level scene renderer: 91-95 ms frames | NN Considered and rejected | Keep a PM4-level, census-closed renderer |
| Waits, not translated code, gave the largest CPU wins outside the renderer | [lessons](https://arcanite24.github.io/pinyon-shift/lessons/#waits-are-performance) | Wait handling is designed in from the start |
| The global critical region costs 0.55-1.28 ms blocked per frame | NL-4.3 | No global lock in a new kernel layer |

## The design, layer by layer

### Verification first (CS-1)

Every aggressive transform has a premise about the title that might be
false. The `r6` bug and the flush-to-zero reordering (NL) were each found
late by differential checks. A rebuild starts from:

- **Deterministic session record and replay.** The recording holds
  inputs, timebase and timer reads, the order of kernel
  synchronisation, file and network results and GPU fence completions.
  A replay runs a race from a seed and produces the same guest memory
  and the same PM4 stream.
- **A reference interpreter for PowerPC and VMX128.** It is slow and
  simple and checked against the existing 2,785 test cases and 167,636
  hardware-recorded ones ([Recompiler](https://arcanite24.github.io/pinyon-shift/recompiler/)).
  It is the oracle, not a fallback.
- **Comparison at three levels.** Per function, the generalised lockstep
  verifier compares registers and every byte written. Per frame, the PM4
  stream and its hash are compared. Per session, the result is compared
  with the save hash and the existing frame and audio thresholds.
- **Poison modes** for every assumption a transform makes: dead
  registers, stack padding and scheduling order.

The frame dump and replay tools (`tools/replay-fh1-frame.py`) already do
this for the GPU. CS-1 extends it to the CPU and the kernel.

### CPU: an IR-based recompiler with whole-program facts (CS-2)

The generator stops emitting C++ text and lowers to its own SSA IR. Guest
semantics are operations in that IR:

- big-endian loads and stores, each with an alias class;
- condition fields as `i1` values;
- FPSCR and VMX non-Java mode as attributes;
- VMX128 as vector operations with the exact lane order.

It is lowered straight to LLVM IR, so nothing has to survive a C++
front end: no `volatile`, no `-fno-strict-aliasing` and no
`-ffp-contract=off` on code where the IR proves them unnecessary.

Whole-program passes run before lowering:

- **Observable-state sets.** For every function, compute which `ctx`
  slots it, or anything it can reach, reads before writing, including
  through indirect calls resolved by the vtable recovery the RE tooling
  already does, with "unknown" for the rest. A call publishes only the
  callee's set, and a return publishes only what callers observe. This
  is the step NL-1.4 lacked: it published everything because C++ could
  not know more.
- **Alias classes.** Each guest access is classified as stack, image
  data, heap, GPU-visible or MMIO. Stack slots that never escape become
  SSA values. Stack symbolisation found only 29-39 % promotable (NN
  Considered and rejected), so this is measured per class, not assumed.
- **FP mode as a static property.** A dataflow pass over FP mode gives
  each function a known mode at entry, with a cloned variant where
  callers differ. No host MXCSR or FPCR write is left in a function
  whose mode is known. #466 removed 29 % of toggles with a local
  version of this pass; NN-0.8 says the rest are most of the physics
  cycles.
- **Spin-wait detection.** Loops that only load, compare and branch on
  one address, with or without `db16cyc`, are reported at build time
  for a decision, instead of being found one profile at a time.

What it does not do, because it was measured: ship PGO by default,
devirtualise, or pass registers interprocedurally. Each stays an option.

The ceiling is bounded. Guest code is 48 % of the Odin process. At the
measured elasticity, removing a third of the remaining bookkeeping
instructions is worth roughly 2.5-4 ms on the Odin (estimate).

### Native code above recompilation (CS-5)

The recompiled program stays the complete baseline and the oracle.
Native replacements follow the existing `src/guest_native` policy and
[LEGAL.md](LEGAL.md#native-replacements-of-game-and-library-code):

- library code found by signature (CRT, XDK math, the D3D runtime's
  leaf helpers) is replaced and verified through CS-1;
- physics functions get hand-vectorised native twins, chosen by the
  NN-0.8 calibration, since NN-5.1 showed generic specialisation helps
  only 19 of 81.

This is a long tail. A pilot port was estimated at 0.2-0.65 ms on top of
NN-1.x, and nothing here changes that estimate until CS-2 lands.

### GPU: a census-closed, GPU-driven front end (CS-3)

Keep what worked: a game-specific renderer scoped by the frame census,
with native render targets and resolves, and refusal of anything outside
the census. Change where per-draw work runs:

- **The CPU stops deriving draws.** The decoder walks PM4 only to find
  draw boundaries, register and constant writes, and synchronisation.
  It appends the raw writes, in host order, to a GPU-visible ring.
  Constants come from static tables (about 5,130 `LOAD_ALU_CONSTANT` a
  frame, NN-0.10), so this is a copy, not a derivation.
- **A compute pass derives state.** For each draw it reads the register
  file the ring implies and selects a pipeline from a table built
  offline from the census. It writes descriptors, constant offsets and
  the draw arguments, and emits indirect commands. On Vulkan that means
  `VK_EXT_device_generated_commands` with indirect execution sets for
  pipeline changes; on D3D12 `ExecuteIndirect`, which cannot change
  pipelines, so draws are bucketed by pipeline where order allows; on
  Metal, indirect command buffers.
- **Pipelines and shaders are compiled offline.** All 12,846 disc
  shaders, or the 11,726 vertex-declaration variants, are translated at
  build time. Pipelines are built from census tuples (shader plus the
  render-target, blend and depth state a list inherits), since NL-3.6
  showed building them from lists alone is impossible.
- **Textures are transcoded at install time** into untiled, native
  block formats in a content-addressed pack, invalidated by the write
  generations of CS-4.

Draw order is the main risk: blending and equal-depth ties depend on
it, and a pipeline-bucketed path must keep submission order inside each
render pass. Driver support is the other risk. RADV implements
`VK_EXT_device_generated_commands`; Turnip support is unknown and
probe CS-0.3 checks it first. Where it is missing, the fallback is the
current recorder, so the Odin may get none of CS-3.

### Memory: one arena for the guest and the GPU (CS-4)

- **Allocate the guest arena from device memory** that is host-visible
  and coherent, and map it at the arena's fixed address with
  `VK_EXT_map_memory_placed` (or `MTLHeap` placement on Metal, or a
  `MapViewOfFile3` placeholder on D3D12). The GPU then reads vertex,
  index and constant data in place, with the byte swap in the shader,
  and the mirror and its uploads go away. This would also remove the
  upload-driven pass splits.
- **Fence timing is the blocker NN found.** The emulator runs the GPU
  up to three frames behind the guest, so a guest overwrite can reach
  the GPU before the draw that read the old data. The console had one
  memory and the game synchronises with real GPU fences. CS-4 therefore
  depends on reporting fence completion when the GPU really completes,
  not at decode. That costs latency and is measured by CS-0.4 before
  anything else.
- **Track writes statically.** The recompiler knows every store.
  Stores whose alias class can reach GPU-visible data bump a generation
  inline, which replaces `mprotect` write watches (0.7 % of the Odin
  process in NN-0.1) and drives the texture pack's invalidation.

### Runtime and kernel (CS-6)

- No global critical region. Kernel objects are atomics and futexes
  from the start, and `mtmsrd` sections are lock-free where they guard
  a single reservation, as NN-3.1 showed 1,001 of 1,062 do.
- Every wait blocks or spins for a bounded time, tuned per OS. The
  Windows rounding and macOS overshoot lessons go into the design.
- Guest threads are placed on cores deliberately: audio and decode on
  efficiency cores, and the NN-3.14 Deck pinning made general.
- Audio keeps the NN-4 results: XMA decode on its worker, FMOD in
  stereo, the native output path.

## Proposed projects

| ID | Project | Premise to prove | Gate | Size |
| --- | --- | --- | --- | --- |
| CS-0.1 | Record and replay a 60 s race with the existing build: log the inputs, timebase reads and sync order, replay, and compare PM4 hashes per frame | The title's nondeterminism is limited to what can be logged | Same PM4 stream for 60 s on desktop and Odin | M |
| CS-1 | **Round 3 FAIL: zero racing seconds replayed identically.** One permit, 128-call budgets, logical clocks and external-input capture record a qualified race; unobserved replay takes another guest boot path at grant 850,145 / 11.67161 s. A sampled repeat identifies an unrecorded native shader-prewarm completion deadlock. The upstream guest-path state change remains unlocalized. Trace-free prototype: 144.567 vs 16.666 ms mean frame time (8.67x); 157.381 vs 22.007 CPU ms/output frame, with different draw counts. No oracle accepted. | [Scheduler, gate and cost method](CLEAN_SLATE_STAGE0.md#cs-1-deterministic-scheduling-probe); SDK `c44fe44`; `.local/codex-cs/round3/schedule-{race14,cost}-*` |
| CS-0.2 | Count, from the generated tree, the `ctx` loads and stores an exact observable-state set would delete | The bookkeeping is removable once the publish sets are exact | 15 % or more of host instructions on the hot threads, static | S |
| CS-0.3 | Check DGC support (`VK_EXT_device_generated_commands`, indirect execution sets) on Turnip V37, RADV on the Deck, NVIDIA and the Mac's Metal ICBs **Round 3 ordered follow-up complete:** Deck 60 s / 3,560 frames: 1,147.42 within-pass pipeline changes/frame; 2.5165 draws/run, median 1, p95 6. Zero overflow/missing mappings. No batching or graphics-DGC implementation; architecture stays on hold. | The yardstick devices can run CS-3 [Ordered census](CLEAN_SLATE_STAGE0.md#cs-03-ordered-pipeline-runs-on-the-deck); `.local/codex-cs/round3/deck-order-race2/` | Supported on the Deck at least; recorded per device | S |
| CS-0.4 | Report fence completion at real GPU completion instead of at decode, and measure frame time and frame pacing | The game tolerates true fence latency | Frame time within 3 % and p99 unchanged on Odin and Deck | S |
| CS-0.5 | Map a host-visible device allocation at a fixed address with `VK_EXT_map_memory_placed` on each device | CS-4 is possible on the yardstick drivers | Works on Turnip and RADV | S |
| CS-1 | Session replay oracle and reference interpreter | CS-0.1 | Replays three routes bit-exactly; interpreter passes the PPC test suite | L |
| CS-2.1 | IR recompiler with conservative semantics (everything published) | It can reproduce today's output | Same CS-1 replays; host instructions within 5 % of today | L |
| CS-2.2 | Observable-state sets and exact publishing | CS-0.2 | Replays pass under poison; -10 % guest-thread instructions or better | L |
| CS-2.3 | Alias classes, stack promotion and dropping `volatile` per class | Classes are provable for most accesses | Replays pass; per-class instruction and memory-access counts improve on arm64 | M |
| CS-2.4 | FP mode as a static property with cloned variants | NN-0.8 | No mode write left in physics functions; -0.3 ms Odin or better | M |
| CS-3.1 | Raw-write ring and offline pipeline table, CPU still recording | The census closes the pipeline set | 0 pipelines created in a race after install; decoder -30 % | L |
| CS-3.2 | GPU-driven derivation and indirect draws | CS-0.3, CS-3.1 | Process CPU -4 ms on the Deck at equal GPU time | L |
| CS-3.3 | Install-time texture pack | Texture requests and untile CPU leave the frame | Odin texture-path CPU under 0.3 ms | M |
| CS-4 | One arena for guest and GPU | CS-0.4, CS-0.5 | Mirror retired; KGSL or VRAM -400 MB; no render-pass splits from uploads | L |
| CS-5 | Library and physics native twins on the CS-2 base | CS-2 lands | Each twin verified by CS-1; -0.2 ms per twin set | M |
| CS-6 | Kernel layer without a global lock | NL-4.3 contention | 0 global-lock contention; -0.5 ms Odin | M |

### Considered and rejected

| Idea | Why |
| --- | --- |
| PGO as the default build | Measured at -0.13 to -0.72 % against a -2 % gate (NN-1.4) |
| Devirtualising indirect calls | 0.035 ms (NN-1.5) |
| Native-endian heaps proven by alias analysis | Endian overhead is about 5 % statically (NN); every host component would need both orders |
| Intercepting D3D calls instead of PM4 | DL-3 measured -0.9 ms against -3 ms; the remaining cost is recording, which CS-3 moves instead |
| An engine-level scene renderer | 91-95 ms frames against 23.5 ms (NN) |
| CPU-side recorder templates or replay of whole buffers | 1,205 against 1,115 ns per draw; G0 saved 2.54 ms against 6 ms |
| A JIT tier | The title has no self-modifying code; static recompilation already covers all 76,499 functions |

## Order

1. **Stage 0, the probes.** CS-0.1 to CS-0.5 together are about two
   weeks of work and decide which large projects go ahead. If CS-0.1
   fails, CS-1 needs a different design. If CS-0.3 fails on the Deck,
   CS-3 falls back to CS-3.1 alone.
2. **CS-1.** Nothing below lands without it.
3. **CS-2.1, then CS-3.1, in parallel.** They touch separate code.
4. **CS-2.2 to CS-2.4 and CS-3.2**, each behind its gate.
5. **CS-4**, if CS-0.4 and CS-0.5 pass.
6. **CS-5 and CS-6.**

## Verification

The NN methods apply unchanged: in-run A/B for hot cvars, cross-build
A/A for build-level changes, judged by per-thread instruction counts,
and the memory ledger. CS-1 adds a stricter level: every transform
replays the recorded sessions with no difference in guest memory or the
PM4 stream, not only within the frame and audio thresholds.

## Risks and open questions

- **Determinism.** CS-0.1 may find nondeterminism that cannot be logged
  (thread races the console scheduler hid). The fallback is a replay
  that is deterministic per thread at sync points only, which is weaker
  as an oracle.
- **The CS-2 ceiling is not large.** Guest code is half the Odin process,
  and the measured elasticity halves an instruction cut. Round 3 rejects
  CS-2 at the conservative context gate (0.903% net versus 15%); a new
  target/escape proof must reopen the census before implementation.
- **Driver support decides CS-3 and CS-4 per device.** The Odin may get
  neither. CS-2 also fails its current gate; CS-6 remains unmeasured.
- **True fence latency** may cost frame time on a GPU-bound device such
  as the Odin, where the GPU already takes 15.3 of 16.7 ms.
- **Scale.** CS-1, CS-2 and CS-3 are each larger than any NN project.
  The current base stays the shipping build until each one beats it at
  its gate.

## Progress

| Item | Outcome | Evidence |
| --- | --- | --- |
| Research | **Plan recorded 2026-10-17.** Executed probe outcomes are below. | Cites NN, NL, DL and the site's lessons and recompiler pages |
| Probe isolation | **Round 3 complete.** Session and fence experiments require `REXGLUE_ENABLE_CS_PROBES=ON` (default OFF). Default Release: zero probe symbols in 785 active project/SDK objects. Short desktop pinned free-roam A/A: 16.755 → 16.666 ms mean (−0.53%), p99 18.500 → 17.336 ms; no observed regression. | [Build flag and A/A method](CLEAN_SLATE_STAGE0.md#probe-build-isolation); `tools/cs/check-probes-disabled.py`; `.local/codex-cs/round3/default-aa.json` |
| CS-0.2 | **Round 3 FAIL under conservative unknown-call contracts; CS-2 rejected at this gate.** Caller continuation demand, opaque publication sets and native-to-original callbacks are included. Matched Odin net context-instruction census: 0.850% render, 0.979% simulation, 0.753% mixer; pooled 0.903%, against 15%. Unknown indirects read everything; no complete site-specific target set is established. Required publications remain at original stores; conditional-write charges are included. This is a static semantic census weighted by samples, not an optimized backend or a limit on designs with new proofs. | [Caller-demand method and gate](CLEAN_SLATE_STAGE0.md#cs-02-caller-demand-and-net-publications); `tools/cs/ctx-exact-census.py`; `.local/codex-cs/round3/ctx-net-{source,census,summary}.json` |
| CS-0.3 | **Extension support gate passes, graphics switching blocked 2026-10-17.** RTX 4080/NVIDIA 581.08: EXT and NV DGC with graphics pipeline execution sets. Deck/RADV 24.3-devel: EXT DGC, but pipeline binding is compute-only (`0x20`), shader binding absent. Odin/installed Turnip V37: neither DGC extension. M4 Pro: native Metal render ICB allocation/per-command pipeline binding works; game's MoltenVK 1.4.1 exposes neither extension. Astra absent. CS-3.2's graphics design is not authorized on these yardstick drivers. The optional pipeline-run count is unavailable from the existing unordered census; no batching claim is made. **Round 3 ordered follow-up complete:** Deck 60 s / 3,560 frames: 1,147.42 within-pass pipeline changes/frame; 2.5165 draws/run, median 1, p95 6. Zero overflow/missing mappings. No batching or graphics-DGC implementation; architecture stays on hold. | [Device table, method and limits](CLEAN_SLATE_STAGE0.md#cs-03-driver-capabilities); `tools/cs/{vulkan-probe.cpp,metal-probe.mm}`; `.local/codex-cs/{vulkan-*,metal-mac}.json` [Ordered census](CLEAN_SLATE_STAGE0.md#cs-03-ordered-pipeline-runs-on-the-deck); `.local/codex-cs/round3/deck-order-race2/` |
| CS-0.5 | **Pass 2026-10-17.** RADV on the Deck and the Odin's installed Turnip each mapped a coherent 512 MiB allocation at reserved address `0x200000000`; compute checked all 134,217,728 CPU-written words, 0 mismatches. Negative-control shaders returned exactly 1 mismatch and exit 2 on both devices. RADV memory flags `0x6` (system/GTT), Turnip `0x7` (device local). NVIDIA and MoltenVK lack the extension; Astra absent. This proves placement, not guest aliases, performance or fence correctness. | [Allocation/check method and D3D12 caveat](CLEAN_SLATE_STAGE0.md#cs-05-placed-device-memory-mapping); `tools/cs/{placed-map.h,placed-check.comp}`; `.local/codex-cs/placed-*.json` |
| CS-0.4 | **Round 2 FAIL; CS-4 rejected.** Default-off asynchronous timeline completion ends work at each guest fence and publishes results from a completion thread; its producer never waits on that semaphore. Race/free means A→B: Odin 16.666→20.942 /16.692→21.885 ms (+25.66/+31.11%); Deck 16.800→38.162 /16.875→37.462 (+127.16/+122.00%). All four p99s worsen by 10.35–28.58 ms. A/A mean shifts −0.011..+0.561%; five race/six free pairs. The synchronous drain above was not the deciding test. | Parent `e98117f4`, ShiftGlue `200bfdb`; [async method, controls and limits](CLEAN_SLATE_STAGE0.md#cs-04-asynchronous-timeline-completion); `.local/codex-cs/{odin,deck}/r2-async-{race,free}-{ab,aa}1`; `round2/cs04a-summary.json` |
| CS-0.1 | **Round 2 FAIL for strict external-event replay; schedule control required.** Per-thread capture fixes the global-order pilot. The final desktop 60 s race repeat, with all memory observers removed, records 9,546 PM4 frames including startup; replay expects the job lock but makes another timebase read from the polling/work-selection path (render sequence 8,511), zero PM4 frames equal. Watched repeats identify job +40 crossing worker publication (3 versus 4, and inverse). Earlier iterations matched at most one frame. CS-1 needs execution/shared-memory ordering or a deliberately weaker replay contract; later causes remain untested. Odin retry was conditional on desktop success and was not attempted. | Parent `ce7580fb`, ShiftGlue `200bfdb`; [capture fixes, final repeat and limitations](CLEAN_SLATE_STAGE0.md#cs-01-per-thread-replay-and-shared-memory-order); `.local/codex-cs/round2/r2-{race1,boot21,race-no-watch}-*`; `tools/cs/{read-session.py,instrument-session-clock.py,instrument-session-observation.py}` |
| CS-1 | **Round 3 FAIL: zero racing seconds replayed identically.** One permit, 128-call budgets, logical clocks and external-input capture record a qualified race; unobserved replay takes another guest boot path at grant 850,145 / 11.67161 s. A sampled repeat identifies an unrecorded native shader-prewarm completion deadlock. The upstream guest-path state change remains unlocalized. Trace-free prototype: 144.567 vs 16.666 ms mean frame time (8.67x); 157.381 vs 22.007 CPU ms/output frame, with different draw counts. No oracle accepted. | [Scheduler, gate and cost method](CLEAN_SLATE_STAGE0.md#cs-1-deterministic-scheduling-probe); SDK `c44fe44`; `.local/codex-cs/round3/schedule-{race14,cost}-*` |
