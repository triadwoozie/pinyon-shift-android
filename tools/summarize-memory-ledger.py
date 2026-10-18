#!/usr/bin/env python3
"""Break a Linux or Android memory ledger down by class (NEAR_NATIVE_RESEARCH NN-0.4).

A route run with `--fh1_render_test_memory_ledger=true` copies, at every
capture, the process's /proc/self/smaps, smaps_rollup and status, and on
Adreno its KGSL per-process counters, into `memory-<capture>/` beside the
route's output. This sums Rss, Pss, private dirty and swap per class:

  guest arena       the memfd that backs guest memory (all its views);
  game code         libpinyon_shift.so (recompiled code and the runtime);
  gpu driver        the Vulkan driver and its KGSL mappings;
  other code        other shared objects and the APK;
  heap              malloc arenas (scudo, jemalloc, libc_malloc);
  stacks            thread stacks;
  java              the ART heap and its code caches;
  other anonymous   other anonymous memory;
  other mapped      other mapped files.

    summarize-memory-ledger.py OUTPUT_DIR_OR_LEDGER [--json out.json]

With a directory holding several `memory-*` ledgers, each is summarized.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

HEADER = re.compile(r"^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+\S+\s+\S+\s+\d+\s*(.*)$")
FIELDS = ("Rss", "Pss", "Private_Dirty", "Swap", "SwapPss")


def classify(name: str, permissions: str) -> str:
    if name.startswith("/memfd:"):
        # The runtime's only memfd is the guest arena; ART's are its JIT caches.
        return "java" if name.startswith("/memfd:jit") else "guest arena"
    if "libpinyon_shift" in name or "libmain" in name:
        return "game code"
    if "kgsl" in name or "vulkan" in name.lower() or "adreno" in name.lower() or \
            "libgsl" in name or "freedreno" in name.lower() or "turnip" in name.lower():
        return "gpu driver"
    if name.startswith("[anon:scudo") or name.startswith("[anon:libc_malloc") or \
            name.startswith("[anon:jemalloc") or name == "[heap]":
        return "heap"
    if "stack" in name:
        return "stacks"
    if name.startswith("[anon:dalvik") or name.startswith("/dev/ashmem/dalvik") or \
            ".art" in name or ".oat" in name or ".vdex" in name or "jit-cache" in name:
        return "java"
    if name.endswith(".so") or ".so " in name or name.endswith(".apk"):
        return "other code"
    if not name or name.startswith("[anon"):
        return "other anonymous"
    return "other mapped"


def parse_smaps(path: Path) -> tuple[dict, dict]:
    classes: dict = defaultdict(lambda: defaultdict(int))
    names: dict = defaultdict(lambda: defaultdict(int))
    current = None
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        match = HEADER.match(line)
        if match:
            name = match.group(4).strip()
            current = (classify(name, match.group(3)), name or "[anonymous]")
            classes[current[0]]["mappings"] += 1
            continue
        if current is None or ":" not in line:
            continue
        key, _, value = line.partition(":")
        if key in FIELDS:
            kilobytes = int(value.split()[0])
            classes[current[0]][key] += kilobytes
            names[current[1]][key] += kilobytes
    return classes, names


def parse_key_values(path: Path) -> dict:
    result = {}
    if not path.exists():
        return result
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        key, _, value = line.partition(":")
        parts = value.split()
        if parts and parts[0].isdigit():
            result[key.strip()] = int(parts[0])
    return result


def summarize(ledger: Path, top: int) -> dict:
    classes, names = parse_smaps(ledger / "smaps.txt")
    mb = lambda kilobytes: round(kilobytes / 1024, 1)
    result = {
        "ledger": ledger.name,
        "rollup_mb": {key: mb(value) for key, value in
                      parse_key_values(ledger / "smaps_rollup.txt").items() if key in FIELDS},
        "status_mb": {key: mb(value) for key, value in
                      parse_key_values(ledger / "status.txt").items()
                      if key in ("VmRSS", "RssAnon", "RssFile", "RssShmem", "VmSwap", "VmHWM")},
        "classes_mb": {
            name: {"mappings": values["mappings"],
                   **{key: mb(values[key]) for key in FIELDS}}
            for name, values in sorted(classes.items(), key=lambda item: -item[1]["Pss"])
        },
        "top_mappings_pss_mb": {
            name: mb(values["Pss"])
            for name, values in sorted(names.items(), key=lambda item: -item[1]["Pss"])[:top]
        },
    }
    kgsl = {}
    for path in sorted(ledger.glob("kgsl-*.txt")):
        text = path.read_text(encoding="utf-8", errors="replace").strip()
        if text.isdigit():
            kgsl[path.stem[len("kgsl-"):]] = round(int(text) / (1 << 20), 1)
    if kgsl:
        result["kgsl_mb"] = kgsl
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("path", type=Path)
    parser.add_argument("--top", type=int, default=15)
    parser.add_argument("--json", type=Path)
    arguments = parser.parse_args()
    if (arguments.path / "smaps.txt").exists():
        ledgers = [arguments.path]
    else:
        ledgers = sorted(path.parent for path in arguments.path.rglob("smaps.txt"))
    if not ledgers:
        raise SystemExit(f"no smaps.txt under {arguments.path}")
    result = [summarize(ledger, arguments.top) for ledger in ledgers]
    text = json.dumps(result, indent=2)
    if arguments.json:
        arguments.json.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
