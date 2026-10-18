"""Summarize CS-0.4 perf captures using the NN in-run window method.

Drop the first (boot/loading) A/B window, then discard 30 settling frames
from each window and windows with fewer than 60 retained frames. Report
all retained frames, including hitches; never filter by the measured time.
"""
import argparse
import csv
import importlib.util
import json
import math
from pathlib import Path
import statistics as stat

spec = importlib.util.spec_from_file_location('ab', Path(__file__).resolve().parents[1] / 'summarize-inrun-ab.py')
ab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ab)


def percentile(values, p):
    values = sorted(values)
    index = (len(values) - 1) * p
    lower = math.floor(index)
    upper = math.ceil(index)
    return values[lower] + (values[upper] - values[lower]) * (index - lower)


def summarize(rows):
    windows = [w for w in ab.windows(rows, 30)[1:] if len(w[2]) >= 60]
    if not windows:
        raise ValueError('no complete gameplay A/B windows')
    output = {'method':'drop first window; settle 30; minimum 60; no time outlier rejection', 'sides':{}, 'windows':[]}
    for side in (0, 1):
        frames = [r for s, _, rs in windows if s == side for r in rs]
        if not frames:
            raise ValueError(f'no frames for side {side}')
        ms = [float(r['frame_time_us']) / 1000 for r in frames]
        output['sides'][side] = {'frames':len(ms), 'mean_ms':stat.fmean(ms), 'median_ms':stat.median(ms),
                                'p99_ms':percentile(ms, .99), 'p95_ms':percentile(ms, .95),
                                'over_18ms_percent':100 * sum(t > 18 for t in ms) / len(ms),
                                'over_20ms_percent':100 * sum(t > 20 for t in ms) / len(ms)}
        for field in ('gpu_decoded_draws', 'process_cpu_ns', 'gpu_thread_fence_wait_ns'):
            if field in frames[0]:
                scale = 1e6 if field.endswith('_ns') else 1
                output['sides'][side][field.removesuffix('_ns') + ('_ms' if scale == 1e6 else '')] = stat.fmean(float(r[field] or 0) for r in frames) / scale
    for side, start, frames in windows:
        output['windows'].append({'side':side, 'first_row':start, 'frames':len(frames),
                                  'mean_ms':stat.fmean(float(r['frame_time_us']) / 1000 for r in frames)})
    diffs = []
    for i in range(0, len(output['windows']) - 1, 2):
        pair = output['windows'][i:i+2]
        if pair[0]['side'] != pair[1]['side']:
            values = {w['side']:w['mean_ms'] for w in pair}
            diffs.append(values[1] - values[0])
    output['paired_mean_ms'] = stat.fmean(diffs) if diffs else None
    output['paired_se_ms'] = stat.stdev(diffs) / math.sqrt(len(diffs)) if len(diffs) > 1 else None
    output['pairs'] = len(diffs)
    output['mean_change_percent'] = 100 * (output['sides'][1]['mean_ms'] / output['sides'][0]['mean_ms'] - 1)
    output['p99_change_ms'] = output['sides'][1]['p99_ms'] - output['sides'][0]['p99_ms']
    return output


def self_test():
    rows = []
    for side in (0, 1, 0, 1, 0):
        rows.extend({'aa_toggle':str(side), 'frame_time_us':str(16000 + side * 2000)} for _ in range(120))
    result = summarize(rows)
    assert result['pairs'] == 2 and result['paired_mean_ms'] == 2
    assert result['sides'][0]['p99_ms'] == 16 and result['sides'][1]['p99_ms'] == 18
    assert percentile([0, 10], .99) == 9.9


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('capture', nargs='?', type=Path)
    p.add_argument('--output', type=Path)
    p.add_argument('--self-test', action='store_true')
    a = p.parse_args()
    self_test()
    if a.self_test:
        print('fence summary self-test passed')
        return
    if not a.capture:
        p.error('capture required')
    path = ab.find_capture(a.capture)
    with path.open(newline='', encoding='utf-8') as f:
        result = summarize(list(csv.DictReader(f)))
    result['capture'] = str(path)
    text = json.dumps(result, indent=2)
    if a.output:
        a.output.write_text(text + '\n')
    print(text)


if __name__ == '__main__':
    main()
