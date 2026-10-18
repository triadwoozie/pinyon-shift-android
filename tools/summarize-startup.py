#!/usr/bin/env python3
"""Startup milestones of a run from its runtime.log (NEXT_LEVEL_RESEARCH.md NL-0.3).

Prints the time from the first log line to each milestone the runtime logs on
the way to guest code:

  setup begin        runtime.setup.begin
  arena mapped       the guest memory arena is reserved and its views mapped
  function table     the indirect-call dispatch table is zeroed and filled
  xex loaded         xex.loaded (the executables are decrypted and mapped)
  setup complete     runtime.setup.complete
  main thread        the guest main thread is created
  first guest call   the first kernel import the guest code called
  simulation thread  the title's simulation thread is created
  opening movie      the title opened its first movie (first visible frame)

With several logs, one row per run and a median column.
"""

from __future__ import annotations

import argparse
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

MILESTONES = [
    ("setup begin", re.compile(r'"event":"runtime\.setup\.begin"')),
    ("arena mapped", re.compile(r"Guest memory arena mapped")),
    ("function table", re.compile(r"Function table initialized for module")),
    ("xex loaded", re.compile(r'"event":"xex\.loaded"')),
    ("setup complete", re.compile(r'"event":"runtime\.setup\.complete"')),
    ("main thread", re.compile(r"Guest thread Main XThread")),
    ("first guest call", re.compile(r"M2_TRACE import\.first")),
    ("simulation thread", re.compile(r"Guest thread Guest 8255AE10|Guest thread Guest 825CEC88")),
    ("opening movie", re.compile(r'"event":"opening_movie\.opened"')),
]
TIMESTAMP = re.compile(r"^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\]")


def milestones(log: Path) -> dict[str, float]:
    """Seconds from the first log line to the first line matching each milestone."""
    start = None
    found: dict[str, float] = {}
    with log.open(encoding="utf-8", errors="replace") as handle:
        for line in handle:
            match = TIMESTAMP.match(line)
            if not match:
                continue
            stamp = datetime.strptime(match.group(1), "%Y-%m-%d %H:%M:%S.%f")
            if start is None:
                start = stamp
            for name, pattern in MILESTONES:
                if name not in found and pattern.search(line):
                    found[name] = (stamp - start).total_seconds()
            if len(found) == len(MILESTONES):
                break
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("logs", nargs="+", type=Path)
    args = parser.parse_args()
    runs = [(log, milestones(log)) for log in args.logs]
    names = [name for name, _ in MILESTONES]
    width = max(len(name) for name in names)
    header = f"{'milestone':{width}}" + "".join(f" {log.parent.parent.name[-14:]:>14}" for log, _ in runs)
    if len(runs) > 1:
        header += f" {'median':>10}"
    print(header + "  (seconds from the first log line)")
    for name in names:
        values = [found.get(name) for _, found in runs]
        row = f"{name:{width}}" + "".join(f" {v:>14.3f}" if v is not None else f" {'-':>14}" for v in values)
        present = [v for v in values if v is not None]
        if len(runs) > 1:
            row += f" {statistics.median(present):>10.3f}" if present else f" {'-':>10}"
        print(row)
    return 0


if __name__ == "__main__":
    sys.exit(main())
