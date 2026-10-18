"""NN-0.8 calibration bench: copy generated guest functions into variant units.

Reads the generated tree (never modified), finds each case's root function
and everything it calls, and writes one set of C++ units per overhead variant
into the bench build directory under .local (docs/LEGAL.md: generated code is
never committed). Nothing here contains game code; it only rewrites text.

    python -I tools/nn08-bench/prepare.py <generated/default> <out dir>
"""

from __future__ import annotations

import glob
import os
import re
import sys

# case -> (root function, functions replaced by an abort stub). The stubs are
# paths the bench inputs never take (coefficient recompute, bypass copy,
# command-buffer wrap), kept out so no unrelated code is pulled in.
CASES = {
    "pow": ("sub_82438EA8", []),
    "lzx": ("sub_830F2210", []),
    "biquad": ("sub_82FC6CC0", ["sub_823F3138", "sub_82FC6BD0"]),
    "walk": ("sub_824168B0", ["sub_824679E8"]),
    "physics": ("sub_82D21DB0", []),
}

# variant -> macro switches (see bench_pch.h); "locals" and "nolr" are text
# rewrites done here.
VARIANTS = {
    "base": [],
    "novol": ["NN08_NOVOL"],
    "noswap": ["NN08_NOSWAP"],
    "noflush": ["NN08_NOFLUSH"],
    "nolr": ["NN08_NOLR"],
    "inline": ["NN08_INLINE"],
    "locals": ["NN08_LOCALS"],
    "all": ["NN08_NOVOL", "NN08_NOSWAP", "NN08_NOFLUSH", "NN08_NOLR", "NN08_INLINE",
            "NN08_LOCALS"],
    # Everything except the byte swap: the safe ceiling of these ablations.
    "allswap": ["NN08_NOVOL", "NN08_NOFLUSH", "NN08_NOLR", "NN08_INLINE", "NN08_LOCALS"],
    # NN-1.12 control, any target: base without __restrict on ctx.
    "norestrict": ["NN08_NORESTRICT"],
    # NN-1.12 (AArch64 only; build.py compiles these with -ffixed-x27
    # -ffixed-x28): "fixed" is base with the two registers taken from the
    # allocator, "pin" also keeps ctx in x27 and base in x28 for the whole
    # guest call tree, so guest functions take no arguments (a text rewrite
    # done here); "pinsep" pins with a separate-storage assumption instead of
    # a restrict body (bench_pch.h). build.py and harness.cpp skip these on
    # other targets.
    "fixed": ["NN08_FIXED"],
    "pin": ["NN08_PIN"],
    "pinsep": ["NN08_PIN", "NN08_PIN_SEP"],
}

CALL_RE = re.compile(r"\b((?:sub_[0-9A-F]+)|__(?:save|rest)\w+?_\d+|rexcrt_\w+)\(ctx, base\)")
TAIL_RE = re.compile(r"REX_TAIL_CALL\((\w+)\)")
REG_RE = re.compile(r"\bctx\.((?:r|f|v)\d+)\b")

# Registers a caller may read after a return (PowerPC ABI as the poison build
# checks it): r1, r3 (and r4 for 64-bit pairs), f1, v3, and the non-volatiles.
LIVE_OUT = ({"r1", "r3", "r4", "f1", "v3"} | {f"r{i}" for i in range(13, 32)}
            | {f"f{i}" for i in range(14, 32)} | {f"v{i}" for i in range(14, 32)}
            | {f"v{i}" for i in range(64, 128)})


NON_VOLATILE = ({f"r{i}" for i in range(14, 32)} | {f"f{i}" for i in range(14, 32)}
                | {f"v{i}" for i in range(14, 32)} | {f"v{i}" for i in range(64, 128)})
# What a callee may read: everything volatile plus the stack, TOC and TLS
# pointers (non-volatiles only to save them, which the locals do not need).
CALL_IN = ({f"r{i}" for i in range(0, 14)} | {f"f{i}" for i in range(0, 14)}
           | {f"v{i}" for i in range(0, 14)} | {f"v{i}" for i in range(32, 64)})


def load_bodies(gen: str) -> dict[str, str]:
    bodies: dict[str, str] = {}
    for path in glob.glob(os.path.join(gen, "pinyon_shift_recomp.*.cpp")):
        with open(path, encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        for match in re.finditer(r"^DEFINE_REX_FUNC\((\w+)\) \{", text, re.M):
            end = text.find("\nDEFINE_REX_FUNC(", match.end())
            body = text[match.start(): end if end > 0 else len(text)].rstrip() + "\n"
            bodies[match.group(1)] = body
    return bodies


def normalize(body: str) -> str:
    body = body.replace("__imp__", "")
    body = re.sub(r"ctx\.fpscr\.(\w+)\(\)", r"NN08_FPSCR(\1)", body)
    # Comments carry the guest instruction text; drop them from the copies.
    body = "\n".join(line for line in body.split("\n") if not line.lstrip().startswith("//"))
    return body


def closure(bodies: dict[str, str], root: str, stubs: list[str]) -> list[str]:
    order: list[str] = []
    todo = [root]
    while todo:
        name = todo.pop(0)
        if name in order:
            continue
        order.append(name)
        if name in stubs:
            continue
        body = normalize(bodies[name])
        for match in CALL_RE.finditer(body):
            todo.append(match.group(1))
        for match in TAIL_RE.finditer(body):
            todo.append(match.group(1))
    return order


def drop_lr(body: str) -> str:
    return re.sub(r"^\s*ctx\.lr = 0x[0-9A-Fa-f]+;\n", "", body, flags=re.M)


def localize(body: str) -> str:
    regs = sorted(set(REG_RE.findall(body)), key=lambda r: (r[0], int(r[1:])))
    if not regs:
        return body

    def kind(reg: str) -> str:
        return "PPCVRegister" if reg[0] == "v" else "PPCRegister"

    decl = "".join(f"\t{kind(r)} L_{r} = ctx.{r};\n" for r in regs)
    vol = [r for r in regs if r not in NON_VOLATILE]

    def out(rs):
        return " ".join(f"ctx.{r} = L_{r};" for r in rs)

    def into(rs):
        return " ".join(f"L_{r} = ctx.{r};" for r in rs)

    out_ret = out([r for r in regs if r in LIVE_OUT])
    body = REG_RE.sub(lambda m: f"L_{m.group(1)}", body)
    lines = body.split("\n")
    result = []
    for line in lines:
        stripped = line.strip()
        indent = line[: len(line) - len(line.lstrip())]
        if stripped == "REX_FUNC_PROLOGUE();":
            result.append(line)
            result.append(decl.rstrip("\n"))
            continue
        call = re.fullmatch(r"(\w+)\(ctx, base\);", stripped)
        if call:
            # ABI-aware sync: a callee reads volatiles (arguments) and r1/r2/r13
            # and preserves the non-volatiles; the save helpers read the
            # non-volatiles, the restore helpers write them.
            name = call.group(1)
            if name.startswith("__save"):
                before, after = out(regs), into(vol)
            elif name.startswith("__rest"):
                before, after = out(regs), into(regs)
            else:
                before, after = out([r for r in regs if r in CALL_IN]), into(vol)
            result.append(f"{indent}{{ {before} {stripped} {after} }}")
            continue
        if stripped.startswith("REX_TAIL_CALL("):
            # The tail callee returns to our caller, which reads our
            # non-volatiles, so everything goes back.
            result.append(f"{indent}{{ {out(regs)} {stripped} }}")
            continue
        if stripped == "return;":
            result.append(f"{indent}{{ {out_ret} return; }}")
            continue
        if "REX_CALL_INDIRECT_FUNC" in stripped or "(ctx, base)" in stripped:
            raise SystemExit(f"unhandled call form in locals rewrite: {stripped}")
        result.append(line)
    text = "\n".join(result)
    # A body that falls off its end (no return) still has to write back.
    closing = text.rstrip().rfind("}")
    text = text[:closing] + f"\t{{ {out_ret} }}\n}}\n"
    return text


def pin_calls(body: str) -> str:
    """Guest-to-guest calls with ctx and base in their fixed registers."""
    body = re.sub(r"\b(\w+)\(ctx, base\);", r"\1();", body)
    if "(ctx, base)" in body:
        raise SystemExit("unhandled call form in pin rewrite")
    return body


def stub(name: str, pinned: bool = False) -> str:
    if pinned:
        return f"DEFINE_REX_FUNC({name}) {{ std::abort(); }}\n"
    return f"DEFINE_REX_FUNC({name}) {{ (void)ctx; (void)base; std::abort(); }}\n"


def header(flags: list[str], namespace: str) -> str:
    lines = ["// Generated by tools/nn08-bench/prepare.py from the generated tree. Never commit."]
    lines += [f"#define {flag} 1" for flag in flags]
    lines += ['#include "bench_pch.h"', f"namespace {namespace} {{"]
    return "\n".join(lines) + "\n"


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    gen, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    bodies = load_bodies(gen)
    written = []
    for case, (root, stubs) in CASES.items():
        names = closure(bodies, root, stubs)
        for variant, flags in VARIANTS.items():
            namespace = f"nn08_{case}_{variant}"
            texts = {}
            for name in names:
                if name in stubs:
                    texts[name] = stub(name, "NN08_PIN" in flags)
                    continue
                body = normalize(bodies[name])
                if "NN08_NOLR" in flags:
                    body = drop_lr(body)
                if "NN08_LOCALS" in flags:
                    body = localize(body)
                if "NN08_PIN" in flags:
                    body = pin_calls(body)
                texts[name] = body
            decls = "".join(f"DECLARE_REX_FUNC({n});\n" for n in names)
            # The pinned entry is the host-to-guest transition every runtime
            # entry would need: save the caller's x27/x28, load ctx and base,
            # call, restore.
            call = (f"NN08_PIN_ENTER(ctx, base); {namespace}::{root}(); NN08_PIN_LEAVE();"
                    if "NN08_PIN" in flags else f"{namespace}::{root}(ctx, base);")
            entry = (f"}}  // namespace {namespace}\n"
                     f"void {namespace}_entry(PPCContext& ctx, uint8_t* base) "
                     f"{{ {call} }}\n")
            if "NN08_INLINE" in flags:
                # One unit: the compiler sees every callee.
                order = [n for n in names if n != root] + [root]
                text = header(flags, namespace) + decls + "".join(texts[n] for n in order) + entry
                files = {f"{case}_{variant}.cpp": text}
            else:
                # The root alone in its unit, the callees in another, as in the game
                # build (no LTO across partitions).
                main_text = header(flags, namespace) + decls + texts[root] + entry
                rest = [n for n in names if n != root]
                rest_text = (header(flags, namespace) + decls
                             + "".join(texts[n] for n in rest) + f"}}  // namespace {namespace}\n")
                files = {f"{case}_{variant}.cpp": main_text,
                         f"{case}_{variant}_callees.cpp": rest_text}
            for filename, text in files.items():
                path = os.path.join(out, filename)
                tmp = path + ".tmp"
                with open(tmp, "w", encoding="utf-8", newline="\n") as handle:
                    handle.write(text)
                os.replace(tmp, path)
                written.append(filename)
        print(f"{case}: {root}, {len(names)} functions: {' '.join(names)}")
    with open(os.path.join(out, "units.txt"), "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(written) + "\n")
    print(f"{len(written)} units in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
