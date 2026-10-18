#!/usr/bin/env python3
"""Compare the two sides of an in-run A/A or A/B capture window by window.

Frames are classed by the perf capture's `aa_toggle` column, which a route
made with `tools/make-ab-route.py --mark` (or an A/A route) sets to 0 or 1
for each window. The first --settle frames of every window are left out.
Each window gets the mean of every metric over its frames; adjacent windows
of opposite sides form pairs, and the result is the mean of the pairs'
differences (side 1 minus side 0) with their spread, so slow thermal drift
cancels (NEAR_NATIVE_RESEARCH NN-0.3).

    summarize-inrun-ab.py RUN_STATE_OR_CSV [--from-row N] [--json out.json]

Metrics are in ms per frame for `*_ns` columns and per frame otherwise.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from pathlib import Path

DEFAULT_METRICS = [
    "frame_time_us",
    "process_cpu_ns",
    "gpu_decoder_cpu_ns",
    "gpu_recorder_cpu_ns",
    "gpu_submission_busy_ns",
    "fh1_title_thread_cpu_time_ns",
    "injected_cpu_ns",
    "gpu_decoded_draws",
    "fh1_untile_skipped_draws",
    "replay_probe_ibs",
    "replay_probe_repeat_ibs",
    "replay_probe_skipped_ibs",
    "replay_probe_drawn_in_ibs",
    "replay_probe_drawn_in_repeat_ibs",
    "replay_probe_drawn_in_skippable_repeat_ibs",
    "replay_probe_skipped_drawn_draws",
    "replay_probe_hash_ns",
    "replay_probe_apply_ns",
    "replay_probe_exit_registers",
]


def find_capture(path: Path) -> Path:
    if path.is_file():
        return path
    logs = path / "logs" if (path / "logs").is_dir() else path
    captures = sorted(logs.rglob("*.perf.csv"), key=lambda item: item.stat().st_mtime)
    if not captures:
        raise SystemExit(f"no *.perf.csv under {path}")
    return captures[-1]


def number(row: dict, column: str) -> float:
    try:
        return float(row.get(column) or 0)
    except ValueError:
        return 0.0


def scale(column: str) -> float:
    if column.endswith("_ns"):
        return 1e6
    if column.endswith("_us"):
        return 1e3
    return 1.0


def windows(rows: list[dict], settle: int) -> list[tuple[int, int, list[dict]]]:
    """Runs of equal aa_toggle as (side, first row, rows after settling)."""
    result = []
    current = None
    start = 0
    run: list[dict] = []
    for index, row in enumerate(rows):
        side = int(number(row, "aa_toggle"))
        if side != current:
            if current is not None:
                result.append((current, start, run[settle:]))
            current, start, run = side, index, []
        run.append(row)
    if current is not None:
        result.append((current, start, run[settle:]))
    return [window for window in result if window[2]]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("capture", type=Path, help="a run's state directory or its perf CSV")
    parser.add_argument("--from-row", type=int, default=0,
                        help="ignore rows before this one (menus, loading)")
    parser.add_argument("--settle", type=int, default=30)
    parser.add_argument("--min-frames", type=int, default=60,
                        help="ignore windows with fewer frames after settling")
    parser.add_argument("--metrics", nargs="*", default=None)
    parser.add_argument("--json", type=Path)
    arguments = parser.parse_args()

    capture = find_capture(arguments.capture)
    with capture.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))[arguments.from_row:]
    if not rows or "aa_toggle" not in rows[0]:
        raise SystemExit(f"{capture.name}: no aa_toggle column (a build without NN-0.3?)")
    metrics = [column for column in (arguments.metrics or DEFAULT_METRICS) if column in rows[0]]

    found = windows(rows, arguments.settle)
    found = [window for window in found if len(window[2]) >= arguments.min_frames]
    means = []
    for side, start, frames in found:
        means.append({
            "side": side,
            "first_row": start + arguments.from_row,
            "frames": len(frames),
            **{column: statistics.fmean(number(row, column) for row in frames) / scale(column)
               for column in metrics},
        })

    pairs = []
    for left, right in zip(means, means[1:]):
        if left["side"] == right["side"]:
            continue
        one, zero = (left, right) if left["side"] == 1 else (right, left)
        pairs.append({column: one[column] - zero[column] for column in metrics})

    summary = {}
    for column in metrics:
        sides = {side: [window[column] for window in means if window["side"] == side]
                 for side in (0, 1)}
        differences = [pair[column] for pair in pairs]
        entry = {
            "side0": round(statistics.fmean(sides[0]), 4) if sides[0] else None,
            "side1": round(statistics.fmean(sides[1]), 4) if sides[1] else None,
        }
        if differences:
            mean = statistics.fmean(differences)
            deviation = statistics.stdev(differences) if len(differences) > 1 else 0.0
            entry.update({
                "difference": round(mean, 4),
                "pair_sd": round(deviation, 4),
                "standard_error": round(deviation / math.sqrt(len(differences)), 4),
                "pair_min": round(min(differences), 4),
                "pair_max": round(max(differences), 4),
            })
        summary[column] = entry

    result = {
        "capture": capture.name,
        "rows": len(rows),
        "windows": len(means),
        "pairs": len(pairs),
        "units": "ms a frame for *_ns and *_us columns, count a frame otherwise",
        "summary": summary,
        "windows_detail": [{key: (round(value, 4) if isinstance(value, float) else value)
                            for key, value in window.items()} for window in means],
    }
    text = json.dumps(result, indent=2)
    if arguments.json:
        arguments.json.write_text(text + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("capture", "rows", "windows", "pairs",
                                                   "units", "summary")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
