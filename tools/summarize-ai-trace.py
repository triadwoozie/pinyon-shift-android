#!/usr/bin/env python3
"""Summarize the AI racing trace of a run (#482).

With --pinyon_shift_ai_trace=true the game records an ai.trace session event
per AI racer twice a second (its gap to the player in metres, positive when
the AI is ahead; whether a rubber-band mode is on and which; catch-up; the
torque scale; its AISkills and AIRubberbands ids) and ai.assign for the path
that gave the AI their skills. This prints, per AI, the gap over time and how
often each behaviour was on, and with --expect-clean fails when any AI
rubber-banded or caught up (the gate of the NO RUBBER-BANDING option).

  summarize-ai-trace.py <run output or state directory> [--expect-clean]
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path


def events(root: Path) -> list[dict]:
    """ai.* events from the session logs below root, in time order."""
    found = []
    for path in sorted(root.rglob("logs/*.jsonl")):
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if '"event":"ai.' not in line:
                continue
            try:
                found.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return sorted(found, key=lambda e: e.get("utc", ""))


def seconds(utc: str) -> float:
    return datetime.fromisoformat(utc.replace("Z", "+00:00")).timestamp()


def summarize(records: list[dict]) -> dict:
    traces = [e for e in records if e["event"] == "ai.trace"]
    by_ai: dict[str, list[dict]] = defaultdict(list)
    for trace in traces:
        by_ai[trace["controller"]].append(trace)
    start = seconds(traces[0]["utc"]) if traces else 0.0
    racers = []
    for controller, samples in sorted(by_ai.items()):
        gaps = [(round(seconds(s["utc"]) - start, 1), float(s["gap"])) for s in samples]
        racers.append({
            "controller": controller,
            "skill_id": samples[-1]["skill_id"],
            "rubber_band_id": samples[-1]["rubber_band_id"],
            "samples": len(samples),
            "gap_first": gaps[0][1],
            "gap_last": gaps[-1][1],
            "gap_max": max(g for _, g in gaps),
            "seconds": gaps[-1][0] - gaps[0][0],
            "rubber_band_samples": sum(s["rubber_band"] == "1" for s in samples),
            "modes": dict(Counter(s["mode"] for s in samples if s["rubber_band"] == "1")),
            "catch_up_samples": sum(s["catch_up"] == "1" or float(s["catch_up_factor"]) > 0
                                    for s in samples),
            "torque_min": min(float(s["torque"]) for s in samples),
            "torque_max": max(float(s["torque"]) for s in samples),
            "gaps": gaps,
        })
    return {
        "assign": dict(Counter(e.get("path", "") for e in records if e["event"] == "ai.assign")),
        "dynamic_updates": sum(e["event"] == "ai.dynamic_update" for e in records),
        "racers": racers,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("root", type=Path)
    parser.add_argument("--expect-clean", action="store_true",
                        help="fail when any AI rubber-banded or caught up")
    parser.add_argument("--json", action="store_true", help="print the whole summary as JSON")
    args = parser.parse_args()
    summary = summarize(events(args.root))
    if args.json:
        print(json.dumps(summary, indent=2))
    else:
        print(f"skills assigned by: {summary['assign'] or 'none seen'}; "
              f"dynamic difficulty updates: {summary['dynamic_updates']}")
        print("ai        skill rb  samples  gap first   last    max  rb-on modes          "
              "catch-up torque")
        for r in summary["racers"]:
            print(f"{r['controller']} {r['skill_id']:>5} {r['rubber_band_id']:>3} "
                  f"{r['samples']:>8} {r['gap_first']:>9.1f} {r['gap_last']:>6.1f} "
                  f"{r['gap_max']:>6.1f} {r['rubber_band_samples']:>6} "
                  f"{json.dumps(r['modes']):<14} {r['catch_up_samples']:>8} "
                  f"{r['torque_min']:.3f}-{r['torque_max']:.3f}")
    if not summary["racers"]:
        print("no ai.trace events: run with --pinyon_shift_ai_trace=true", file=sys.stderr)
        return 1
    if args.expect_clean and any(r["rubber_band_samples"] or r["catch_up_samples"]
                                 for r in summary["racers"]):
        print("FAIL: an AI rubber-banded or caught up", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
