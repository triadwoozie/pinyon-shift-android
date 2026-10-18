"""CS-0.2 conservative observable-state census; emits counts, never game code.

python tools/cs/ctx-publish-sets.py --generated .local/generated/default \
  --output .local/codex-cs/ctx.json [--calls .local/re/probe/race-summary.json]

The source summaries are may-read-before-full-write sets, not ABI live-ins.
Unknown context escapes, native calls, indirect calls and resume dispatch read
and may write every slot. Partial writes do not kill a whole slot. Callees do
not kill slots in their callers: this deliberately over-approximates recursive
and non-returning calls. This is analysis only, never a codegen input.

Optional AArch64 objdump input supplies compiled instruction counts and a
ceiling on ctx traffic. Publish candidates are stores before direct calls
whose callee cannot read the slot. They are *deferrals*, not a measured net
instruction saving: later publishing, reloads and register pressure still cost.
Global call counts are not per-thread counts; reachable closures overlap.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict, deque
from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path
import re
import subprocess


SLOTS = ([f"r{i}" for i in range(32)] + ["lr", "ctr", "xer", "reserved", "msr"]
         + [f"cr{i}" for i in range(8)] + ["fpscr", "vscr_sat", "last_indirect_target", "dispatch_address"]
         + [f"f{i}" for i in range(32)] + [f"v{i}" for i in range(128)])
BITS = {s: 1 << i for i, s in enumerate(SLOTS)}
ALL = (1 << len(SLOTS)) - 1
CTX = re.compile(r"\bctx\.(\w+)")
FULL = re.compile(r"^ctx\.((?:r|f)\d+|ctr)(?:\.[us]64)?\s*=\s*(?!=)|^ctx\.(lr|msr|vscr_sat|last_indirect_target|dispatch_address)\s*=\s*(?!=)")
DIRECT = re.compile(r"\b((?:__imp__)?sub_[0-9A-F]{8})\(ctx, base\)|REX_TAIL_CALL\(((?:__imp__)?sub_[0-9A-F]{8})\)")


@dataclass
class Block:
    read: int = 0
    kill: int = 0
    write: int = 0
    calls: list[str] = field(default_factory=list)
    successors: list[int] = field(default_factory=list)
    targets: list[str] = field(default_factory=list)
    fallthrough: bool = True


@dataclass
class Function:
    blocks: list[Block]
    loads: int
    stores: int
    indirect: int
    opaque: int
    rbw: int = 0
    writes: int = 0


def parse(body: str, escape_read: int = ALL, escape_write: int = ALL,
          escape_kill: int = 0) -> Function:
    blocks = [Block()]
    labels = {}
    loads = stores = indirect = opaque = 0
    for raw in body.splitlines():
        line = raw.split("//", 1)[0].strip()
        if not line or line in ("{", "}") or line.startswith("#"):
            continue
        b = blocks[-1]
        label = re.fullmatch(r"(loc_\w+):", line)
        if label:
            blocks.append(Block())
            labels[label[1]] = len(blocks) - 1
            continue
        slots = CTX.findall(line)
        read = 0
        for s in slots:
            read |= BITS.get(s, ALL)
        write = kill = 0
        escaped = False
        lhs = re.match(r"^ctx\.(\w+)(?:\.[\w\[\]]+)*\s*(?:[+&|^*/-]?=)(?!=)", line)
        if lhs:
            write = BITS.get(lhs[1], ALL)
            stores += 1
            loads += max(0, len(slots) - 1)
            full = FULL.match(line)
            if full:
                kill = write
                # Exclude only the LHS occurrence; keep RHS reads of that slot.
                read = 0
                for s in CTX.findall(line[line.index("=") + 1:]):
                    read |= BITS.get(s, ALL)
        else:
            loads += len(slots)
        call = DIRECT.search(line)
        if call:
            b.calls.append((call[1] or call[2]).removeprefix("__imp__"))
        elif ("REX_CALL_INDIRECT" in line or "REX_TAIL_CALL" in line
              or re.search(r"\bctx\b(?!\.)", line)
              or "switch" in line or "case " in line):
            read, write, kill = escape_read, escape_write, escape_kill
            escaped = True
            indirect += int("REX_CALL_INDIRECT" in line)
            opaque += 1
        b.read |= read & ~b.kill
        b.write |= write
        b.kill |= kill
        goto = re.search(r"goto (loc_\w+)", line)
        if goto:
            b.targets.append(goto[1])
            b.fallthrough = not line.startswith("goto ")
        terminal = line == "return;" or line.startswith("REX_TAIL_CALL")
        if terminal:
            b.fallthrough = False
        if call or goto or terminal or escaped or read == ALL:
            blocks.append(Block())
    for i, b in enumerate(blocks):
        b.successors = [labels[t] for t in b.targets if t in labels]
        if len(b.successors) != len(b.targets):
            b.read, b.write = escape_read, escape_write  # unrecognised control flow
            b.kill |= escape_kill
        if b.fallthrough and i + 1 < len(blocks):
            b.successors.append(i + 1)
    return Function(blocks, loads, stores, indirect, opaque)


def solve(functions: dict[str, Function]) -> None:
    callers = defaultdict(set)
    for name, f in functions.items():
        for b in f.blocks:
            for target in b.calls:
                callers[target].add(name)
    queue = deque(functions)
    pending = set(queue)
    while queue:
        name = queue.popleft()
        pending.remove(name)
        f = functions[name]
        live = [0] * len(f.blocks)
        writes = 0
        changed = True
        while changed:
            changed = False
            for i in range(len(f.blocks) - 1, -1, -1):
                b = f.blocks[i]
                tail = 0
                for j in b.successors:
                    tail |= live[j]
                reads = b.read
                writes |= b.write
                for callee in b.calls:
                    c = functions.get(callee)
                    reads |= (c.rbw if c else ALL) & ~b.kill
                    writes |= c.writes if c else ALL
                value = reads | (tail & ~b.kill)
                if value != live[i]:
                    live[i] = value
                    changed = True
        if (f.rbw, f.writes) != (live[0], writes):
            f.rbw, f.writes = live[0], writes
            for c in callers[name]:
                if c not in pending:
                    queue.append(c)
                    pending.add(c)


def names(mask: int) -> list[str]:
    return [s for s in SLOTS if mask & BITS[s]]


def host_counts(lines, functions: dict[str, Function]) -> dict:
    """Conservative AArch64 context-base tracking; count a pair once.

    Branches clear aliases except the stable callee-saved copy established
    before the first branch. Unknown base offsets and subregister accesses
    cannot become publish candidates. The all-ctx count is an identified
    ceiling, not proof that unrecognised pointer arithmetic touches no ctx.
    """
    offsets = {0: "r3", 8: "r0", 16: "r1", 24: "r2"}
    offsets.update({i * 8: f"r{i}" for i in range(4, 32)})
    offsets.update({256: "lr", 264: "ctr"})
    offsets.update({352 + i * 8: f"f{i}" for i in range(32)})
    # Verify offsets against the compiler's layout before using FPR/VR counts.
    offsets = {k: v for k, v in offsets.items() if k < 272}
    out = {}
    current = None
    aliases = set()
    stable = set()
    stores = []
    for line in lines:
        m = re.match(r"^[0-9a-f]+ <(__imp__)?sub_([0-9A-F]{8})>:", line)
        if m:
            current = "sub_" + m[2]
            if current in out and not m[1]:
                current = None  # public trampoline after its implementation
                continue
            out[current] = Counter()
            aliases, stable, stores = {"x0"}, set(), []
            continue
        if re.match(r"^[0-9a-f]+ <", line):
            current = None
        ins = re.match(r"^\s+[0-9a-f]+:\s+(\S+)\s*(.*)", line)
        if not ins or current is None:
            continue
        op, args = ins[1], ins[2].split("//")[0]
        row = out[current]
        row["instructions"] += 1
        mem = re.search(r"\[(x\d+)(?:, #(-?\d+|-?0x[0-9a-f]+))?\]", args)
        if mem and mem[1] in aliases and op.startswith(("ld", "st")):
            kind = "loads" if op.startswith("ld") else "stores"
            row["ctx_" + kind] += 1
            off = int(mem[2], 0) if mem[2] else 0
            regs = args.split("[")[0].strip(" ,").split(", ")
            width = 8 if regs[0].startswith(("x", "d")) else 16 if regs[0].startswith("q") else 4
            touched = [offsets.get(off + i * width) for i in range(2 if op in ("ldp", "stp") else 1)]
            if kind == "stores" and width == 8 and all(touched):
                stores.append(touched)
        if op in ("bl", "blr"):
            target = re.search(r"<(__imp__)?(sub_[0-9A-F]{8})(?:@plt)?>", args)
            c = functions.get(target[2]) if target else None
            reads = c.rbw if c else ALL
            row["publish_store_candidates"] += sum(
                all(not reads & BITS[s] for s in touched) for touched in stores)
            stores = []
            aliases = {r for r in aliases if int(r[1:]) >= 19}
        elif op.startswith("b") or op in ("ret", "cbz", "cbnz", "tbz", "tbnz"):
            stores = []
            aliases &= stable
        elif op == "mov":
            m = re.fullmatch(r"(x\d+), (x\d+)\s*", args)
            if m:
                aliases.discard(m[1])
                if m[2] in aliases:
                    aliases.add(m[1])
                    if int(m[1][1:]) >= 19:
                        stable.add(m[1])
        elif not op.startswith(("st", "cmp", "tst", "fcmp")):
            dst = args.split(",")[0]
            aliases.discard(dst)
            stable.discard(dst)
    return out


def self_test() -> None:
    fs = {
        "sub_00000001": parse("ctx.r6.u64 = 0;\nsub_00000002(ctx, base);\nreturn;"),
        "sub_00000002": parse("ctx.r3.u64 = ctx.r6.u64;\nreturn;"),
        "sub_00000003": parse("if (ctx.r1.u32) goto loc_x;\nctx.r6.u64 = 0;\nloc_x:\nctx.r3.u64 = ctx.r6.u64;\nreturn;"),
        "sub_00000004": parse("ctx.r6.u32 = 0;\nctx.r3.u64 = ctx.r6.u64;\nreturn;"),
        "sub_00000005": parse("REX_CALL_INDIRECT_FUNC(0);\nreturn;"),
        "sub_00000006": parse("sub_00000006(ctx, base);\nctx.r3.u64 = ctx.r7.u64;\nreturn;"),
    }
    solve(fs)
    assert fs["sub_00000002"].rbw == BITS["r6"]
    assert not fs["sub_00000001"].rbw & BITS["r6"]
    assert fs["sub_00000003"].rbw & BITS["r6"]
    assert fs["sub_00000004"].rbw & BITS["r6"]
    assert fs["sub_00000005"].rbw == ALL
    assert fs["sub_00000006"].rbw & BITS["r7"]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--generated", type=Path)
    p.add_argument("--calls", type=Path)
    p.add_argument("--objdump", type=Path)
    p.add_argument("--binary", type=Path)
    p.add_argument("--objdump-executable", type=Path)
    p.add_argument("--output", type=Path)
    p.add_argument("--self-test", action="store_true")
    a = p.parse_args()
    self_test()
    if a.self_test:
        print("ctx-publish-sets self-test passed")
        return
    if not a.generated or not a.output:
        p.error("--generated and --output required")
    fs = {}
    digest = hashlib.sha256()
    for path in sorted(a.generated.glob("*_recomp.*.cpp")):
        raw = path.read_bytes()
        digest.update(raw)
        text = raw.decode("utf-8")
        # Definitions close at column zero; skip reference copies entirely.
        for m in re.finditer(r"^DEFINE_REX_FUNC\((sub_[0-9A-F]{8})\) \{\n(.*?)^\}", text, re.M | re.S):
            fs[m[1]] = parse(m[2])
    solve(fs)
    calls = json.loads(a.calls.read_text()) if a.calls else {}
    host = {}
    if a.binary:
        if not a.objdump_executable:
            p.error("--binary requires --objdump-executable")
        with subprocess.Popen([str(a.objdump_executable), "-d", "--no-show-raw-insn", str(a.binary)],
                              stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace") as proc:
            host = host_counts(proc.stdout, fs)
        if proc.returncode:
            raise SystemExit(f"objdump failed: {proc.returncode}")
    elif a.objdump:
        with a.objdump.open(encoding="utf-8") as lines:
            host = host_counts(lines, fs)
    threads = {}
    for root in ("825A6320", "8255AE10", "82FB4AF8"):
        seen, queue = set(), ["sub_" + root]
        while queue:
            name = queue.pop()
            if name in seen or name not in fs:
                continue
            seen.add(name)
            queue.extend(c for b in fs[name].blocks for c in b.calls)
        total = Counter()
        weighted = Counter()
        for name in seen:
            total.update(host.get(name, {}))
            n = calls.get("calls", {}).get(name[4:], 0)
            weighted.update({k: v * n for k, v in host.get(name, {}).items()})
        threads[root] = {"direct_closure_functions": len(seen), "host_static": dict(total),
                         "host_global_call_weighted_estimate": dict(weighted),
                         "warning": "overlapping direct closures; no per-thread call counts; not gate evidence"}
    result = {
        "schema": "cs-0.2.v1", "generated_sha256": digest.hexdigest(),
        "functions": len(fs), "slots": len(SLOTS),
        "source_ctx_load_expressions": sum(f.loads for f in fs.values()),
        "source_ctx_store_expressions": sum(f.stores for f in fs.values()),
        "indirect_calls_reading_everything": sum(f.indirect for f in fs.values()),
        "resolved_vtable_sites": None,
        "observed_indirect_sites_not_static_proofs": len(calls.get("indirect", {})),
        "gate": "not established: conservative sets and publication deferrals are not net deletions",
        "threads": threads,
        "sets": {name: {"read_before_write": names(f.rbw), "may_write": names(f.writes),
                         "source_loads": f.loads, "source_stores": f.stores,
                         "opaque_barriers": f.opaque, "host": host.get(name)} for name, f in fs.items()},
    }
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result, separators=(",", ":")) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "sets"}, indent=2))


if __name__ == "__main__":
    main()
