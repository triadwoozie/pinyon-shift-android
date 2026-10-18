#!/usr/bin/env python3
"""Summarize a probe build's probe.json (NEXT_LEVEL_RESEARCH.md NL-2.3).

A build with PINYON_SHIFT_RECOMP_PROBE counts every function entry and
records the target of every indirect call; the runtime writes
`<state>/logs/probe.json` at exit. This reads one or more of those files
(several routes add up) and the generated tree's function list, and prints:

- the functions called most, with the recorded names when the knowledge base
  has them (tools/re/kb.py symbols);
- how many generated functions no route entered (the candidates for a cold
  tier), optionally written as a list;
- the indirect call sites by number of distinct targets: a single target is a
  guarded-devirtualization candidate, many targets a vtable dispatch.

    python -I tools/re/probe-summary.py D:/runs/race.state/logs/probe.json \\
        --generated .local/generated/default --top 40 --never-called never.txt
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import sys
from pathlib import Path


def load_functions(generated: Path) -> list[int]:
    init = generated / "pinyon_shift_init.cpp"
    addresses = []
    for line in init.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"\s*\{ 0x([0-9A-Fa-f]+), (sub_[0-9A-F]+) \},", line)
        if m:
            addresses.append(int(m.group(1), 16))
    return addresses


def load_names(symbols: Path | None) -> dict[int, str]:
    if not symbols or not symbols.exists():
        return {}
    data = json.loads(symbols.read_text(encoding="utf-8"))
    names = {}
    for key, name in data.get("names", {}).items():
        m = re.match(r"sub_([0-9A-F]+)", key)
        if m:
            names[int(m.group(1), 16)] = name
    return names


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("probe", nargs="+", type=Path, help="probe.json files; counts add up")
    parser.add_argument("--generated", type=Path, default=Path(".local/generated/default"))
    parser.add_argument("--symbols", type=Path, default=None, help="symbols.json from tools/re/kb.py symbols")
    parser.add_argument("--top", type=int, default=30)
    parser.add_argument("--never-called", type=Path, default=None, help="write the never-entered functions here")
    parser.add_argument("--json", type=Path, default=None, help="write the full summary as JSON")
    args = parser.parse_args()

    calls: collections.Counter[int] = collections.Counter()
    indirect: collections.Counter[tuple[int, int]] = collections.Counter()
    for path in args.probe:
        data = json.loads(path.read_text(encoding="utf-8"))
        for address, count in data["calls"]:
            calls[int(address, 16)] += count
        for site, target, count in data["indirect"]:
            indirect[(int(site, 16), int(target, 16))] += count

    functions = load_functions(args.generated)
    names = load_names(args.symbols)
    entered = set(calls)
    never = sorted(a for a in functions if a not in entered)
    total_calls = sum(calls.values())

    print(f"functions generated {len(functions)}, entered {len(entered)}, never entered {len(never)}; "
          f"{total_calls:,} calls recorded")
    print(f"\ntop {args.top} by calls:")
    for address, count in calls.most_common(args.top):
        print(f"  {address:08X} {count:>14,}  {count / total_calls * 100:5.2f} %  {names.get(address, '')}")

    by_site: dict[int, collections.Counter[int]] = collections.defaultdict(collections.Counter)
    for (site, target), count in indirect.items():
        by_site[site][target] += count
    histogram = collections.Counter(len(targets) for targets in by_site.values())
    print(f"\nindirect call sites {len(by_site)}, calls {sum(indirect.values()):,}; sites by distinct targets:")
    for distinct in sorted(histogram):
        print(f"  {distinct:>4} target(s): {histogram[distinct]} sites")
    hottest = sorted(by_site.items(), key=lambda kv: -sum(kv[1].values()))[: args.top]
    print(f"\nhottest {args.top} indirect sites:")
    for site, targets in hottest:
        total = sum(targets.values())
        best, best_count = targets.most_common(1)[0]
        print(f"  {site:08X} {total:>12,} calls, {len(targets):>3} targets, "
              f"{best_count / total * 100:5.1f} % to {best:08X} {names.get(best, '')}")

    if args.never_called:
        args.never_called.write_text("\n".join(f"{a:08X}" for a in never) + "\n", encoding="utf-8")
    if args.json:
        args.json.write_text(json.dumps({
            "functions": len(functions), "entered": len(entered), "never_entered": [f"{a:08X}" for a in never],
            "calls": {f"{a:08X}": c for a, c in calls.most_common()},
            "indirect": {f"{s:08X}": {f"{t:08X}": c for t, c in targets.most_common()} for s, targets in by_site.items()},
        }, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
