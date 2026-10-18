#!/usr/bin/env python3
"""Write an lld symbol ordering file for the generated game code (NN-1.9).

usage: make-symbol-order.py <probe summary.json> <output.txt>

The summary is tools/re/probe-summary.py's JSON from a probe build's route
(`calls`: guest function address -> entries). Functions the route entered
come first, the most-called first, so the hot code shares as few pages as
possible; lld places every unlisted section after them, which puts the
never-entered functions last. Only symbol names are written.
"""
import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    calls = summary["calls"]
    order = sorted(calls.items(), key=lambda item: (-int(item[1]), item[0]))
    lines = [f"__imp__sub_{address.upper()}" for address, count in order if int(count) > 0]
    Path(sys.argv[2]).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} functions ordered")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
