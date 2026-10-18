"""Count pipeline changes and contiguous draw runs inside host rendering instances."""
import argparse
import collections
import json
from pathlib import Path
import re
import statistics


def distribution(values):
    values = sorted(values)
    if not values:
        return dict(count=0)
    return dict(count=len(values), mean=statistics.mean(values),
                median=statistics.median(values), p95=values[int(.95*(len(values)-1))],
                minimum=values[0], maximum=values[-1])


def analyze(records, start, end):
    if end <= start:
        raise ValueError("empty or inverted output window")
    switches, draws_per_frame, runs_per_frame, runs = [], [], [], []
    seen = set()
    previous_frame = -1
    passes = 0
    pipelines = set()
    for row in records:
        if row['schema'] != 'pinyon-shift.fh1-frame-order.v1' or row['backend'] != 'vulkan':
            raise ValueError('unknown census schema/backend')
        if row['frame'] <= previous_frame:
            raise ValueError('duplicate/out-of-order consumed frame')
        previous_frame = row['frame']
        output = row['output_frame']
        if not start < output <= end:
            continue
        if output in seen or row['overflow']:
            raise ValueError('duplicate output mapping or overflow in race window')
        seen.add(output)
        last_pass, last_pipeline, length, changes, frame_runs = -1, None, 0, 0, 0
        for render_pass, pipeline in row['draws']:
            if render_pass <= 0 or render_pass < last_pass or not re.fullmatch(r'[0-9A-F]{16}', pipeline) or int(pipeline, 16) == 0:
                raise ValueError('invalid/reopened render pass or pipeline identity')
            pipelines.add(pipeline)
            if render_pass != last_pass or pipeline != last_pipeline:
                if length:
                    runs.append(length)
                if render_pass == last_pass:
                    changes += 1
                else:
                    passes += 1
                frame_runs += 1
                length = 0
            length += 1
            last_pass, last_pipeline = render_pass, pipeline
        if length:
            runs.append(length)
        switches.append(changes)
        draws_per_frame.append(len(row['draws']))
        runs_per_frame.append(frame_runs)
    if seen != set(range(start+1, end+1)):
        raise ValueError('missing presented-output mappings in race window')
    return dict(output_window_exclusive_inclusive=[start, end], frames=len(seen),
                draws=sum(draws_per_frame), nonempty_rendering_instances=passes,
                distinct_process_local_pipelines=len(pipelines),
                switches_per_frame=distribution(switches),
                draws_per_frame=distribution(draws_per_frame),
                runs_per_frame=distribution(runs_per_frame),
                draws_per_run=distribution(runs),
                single_draw_runs=sum(x == 1 for x in runs), overflow=0)


def self_test():
    def row(frame, draws, overflow=0):
        return dict(schema='pinyon-shift.fh1-frame-order.v1', backend='vulkan',
                    frame=frame, output_frame=frame, overflow=overflow, draws=draws)
    a, b = '000000000000000A', '000000000000000B'
    rows = [row(1, [[1,a],[1,a],[1,b],[1,a],[2,a],[2,a]]), row(2, [])]
    result = analyze(rows, 0, 2)
    assert result['switches_per_frame']['mean'] == 1
    assert result['draws_per_run']['count'] == 4
    assert result['draws_per_run']['mean'] == 1.5
    for bad in (rows[:1], [rows[0], rows[0]], [row(1, [], 1)],
                [row(1, [[2,a],[1,a]])], [row(1, [[1,'0'*16]])]):
        try:
            analyze(bad, 0, 2)
        except ValueError:
            pass
        else:
            raise AssertionError('invalid census accepted')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('census', type=Path, nargs='?')
    parser.add_argument('--events', type=Path)
    parser.add_argument('--start-capture', default='gauntlet-driving')
    parser.add_argument('--end-capture', default='gauntlet-end')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print('ordered census analysis self-test passed')
    else:
        if not args.census or not args.events or not args.output:
            parser.error('census, --events and --output required')
        events = [json.loads(line) for line in args.events.read_text(encoding='utf-8').splitlines()]
        captures = {e['name']: e for e in events if e['event'] == 'fh1.render_test.capture'}
        first, last = [captures[n] for n in (args.start_capture, args.end_capture)]
        with args.census.open(encoding='utf-8') as stream:
            result = analyze((json.loads(line) for line in stream),
                             int(first['trigger_output_frame']), int(last['trigger_output_frame']))
        result['capture_host_seconds'] = (int(last['trigger_elapsed_us'])-int(first['trigger_elapsed_us']))/1e6
        args.output.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
        print(json.dumps(result, indent=2))
