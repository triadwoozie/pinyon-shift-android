"""Build the NN-0.8 calibration bench for one target.

    python -I tools/nn08-bench/build.py win|win-v3|linux|linux-v3|android

Run from the repository root (for `linux`, inside WSL: python3 tools/...).
Expects prepare.py's units in .local/nn08-bench/gen and the native twins in
.local/nn08-bench/native; writes .local/nn08-bench/bin/<target>/nn08_bench[.exe].
The generated units get the game's compile options for the target: -O3,
-fno-strict-aliasing, SSE4.1 (x86-64), -mcmodel=large with --no-relax
(Linux x86-64, as the Deck preset), -march=armv8-a (Android). The -v3
targets add the x86-64-v3 preset's flags (-mfma -ffp-contract=off
-march=x86-64-v3; NN-1.8), so fma is an instruction instead of a libm call.
On Android the NN-1.12 variants ("fixed", "pin", "pinsep") also get
-ffixed-x27 -ffixed-x28; other targets leave them out.
"""

from __future__ import annotations

import concurrent.futures
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BENCH = os.path.join(ROOT, ".local", "nn08-bench")
SDK = os.path.join(ROOT, "thirdparty", "shiftglue-sdk")
NDK_VERSION = "29.0.14206865"
ANDROID_API = 33


V3 = ["-mfma", "-ffp-contract=off", "-march=x86-64-v3"]
# NN-1.12: ctx and the guest memory base in x27/x28 (the "fixed", "pin" and
# "pinsep" variants' units, Android only).
FIXED = ["-ffixed-x27", "-ffixed-x28"]
PIN_UNIT_RE = re.compile(r"^\w+?_(fixed|pin|pinsep)(_callees)?\.cpp$")


def toolchain(target: str) -> tuple[list[str], list[str], list[str], list[str], str]:
    """compiler, common flags, generated-unit flags, link flags, exe name."""
    if target.endswith("-v3"):
        cxx, common, gen, link, exe = toolchain(target[:-3])
        return cxx, common + V3, gen, link, exe
    common = ["-std=c++23", "-O3", "-fno-strict-aliasing", "-Wno-unused-label",
              "-Wno-unused-variable", "-Wno-unused-but-set-variable", "-Wno-missing-braces"]
    if target == "win":
        cxx = [os.path.join(ROOT, ".local", "toolchain", "llvm-20.1.8", "bin", "clang++.exe")]
        return (cxx, common + ["-msse4.1", "-D_CRT_SECURE_NO_WARNINGS"], [],
                ["-fuse-ld=lld"], "nn08_bench.exe")
    if target == "linux":
        return (["clang++"], common + ["-msse4.1"], ["-mcmodel=large"],
                ["-static-libstdc++", "-static-libgcc", "-Wl,--no-relax"], "nn08_bench")
    if target == "android":
        sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk")
        host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
        bin_dir = os.path.join(sdk, "ndk", NDK_VERSION, "toolchains", "llvm", "prebuilt", host, "bin")
        cxx = [os.path.join(bin_dir, "clang++" + (".exe" if os.name == "nt" else "")),
               f"--target=aarch64-linux-android{ANDROID_API}"]
        return (cxx, common + ["-march=armv8-a"], [], ["-static-libstdc++"], "nn08_bench")
    raise SystemExit(f"unknown target {target}")


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    target = sys.argv[1]
    cxx, common, gen_flags, link_flags, exe = toolchain(target)
    includes = ["-I", os.path.join(ROOT, "tools", "nn08-bench"),
                "-I", os.path.join(BENCH, "native", "stub"),
                "-I", os.path.join(SDK, "include"),
                "-I", os.path.join(SDK, "thirdparty", "simde")]
    obj_dir = os.path.join(BENCH, "build", target)
    bin_dir = os.path.join(BENCH, "bin", target)
    os.makedirs(obj_dir, exist_ok=True)
    os.makedirs(bin_dir, exist_ok=True)
    with open(os.path.join(BENCH, "gen", "units.txt"), encoding="utf-8") as handle:
        units = [os.path.join(BENCH, "gen", line.strip()) for line in handle if line.strip()]
    natives = [os.path.join(BENCH, "native", n) for n in
               ("native_pow.cpp", "native_lzx.cpp", "native_biquad.cpp", "native_walk.cpp")]
    harness = [os.path.join(ROOT, "tools", "nn08-bench", "harness.cpp")]

    jobs = []
    for source in units:
        variant = PIN_UNIT_RE.match(os.path.basename(source))
        if variant:
            # NN-1.12 variants: AArch64 only, with the two registers reserved.
            if target != "android":
                continue
            jobs.append((source, gen_flags + FIXED))
        else:
            jobs.append((source, gen_flags))
    for source in natives + harness:
        jobs.append((source, []))

    def compile_one(job):
        source, extra = job
        obj = os.path.join(obj_dir, os.path.splitext(os.path.basename(source))[0] + ".o")
        if os.path.exists(obj) and os.path.getmtime(obj) > max(
                os.path.getmtime(source), os.path.getmtime(os.path.join(ROOT, "tools", "nn08-bench", "bench_pch.h"))):
            return obj, ""
        cmd = cxx + common + extra + includes + ["-c", source, "-o", obj]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            raise RuntimeError(f"{source}:\n{result.stdout}{result.stderr}")
        return obj, result.stderr

    objects = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as pool:
        for obj, warnings in pool.map(compile_one, jobs):
            objects.append(obj)
            if warnings.strip():
                print(warnings.strip()[:2000])
    out = os.path.join(bin_dir, exe)
    cmd = cxx + common + objects + link_flags + ["-o", out]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stdout + result.stderr)
        return 1
    print(f"built {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
