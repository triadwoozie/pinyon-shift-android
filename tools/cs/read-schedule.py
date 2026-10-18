#!/usr/bin/env python3
"""Validate a serialized probe schedule without exposing guest data."""
import argparse
import collections
import json
import struct
import tempfile
from pathlib import Path

HEADER = struct.Struct('<8sQQ')
GRANT = struct.Struct('<QQQII')


def read(path):
    counts = collections.Counter()
    actors = collections.Counter()
    previous = 0
    with Path(path).open('rb') as f:
        header = f.read(HEADER.size)
        if len(header) != HEADER.size:
            raise ValueError('truncated header')
        magic, epoch, expected = HEADER.unpack(header)
        if magic != b'CSSCH01\0':
            raise ValueError('unknown schedule version')
        n = 0
        while data := f.read(GRANT.size):
            if len(data) != GRANT.size:
                raise ValueError('truncated grant')
            actor, site, ns, kind, reserved = GRANT.unpack(data)
            if not actor or reserved or kind > 7 or ns < previous:
                raise ValueError(f'invalid grant {n}')
            counts[kind] += 1
            actors[f'{actor:016X}'] += 1
            previous = ns
            n += 1
        if n != expected:
            raise ValueError(f'count {n} differs from committed {expected}')
    return dict(grants=n, logical_ns=previous, epoch=epoch,
                points=dict(counts), actors=dict(actors))


def self_test():
    good = HEADER.pack(b'CSSCH01\0', 1, 2) + GRANT.pack(7, 0, 0, 0, 0) + GRANT.pack(7, 4, 10000, 1, 0)
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'schedule.bin'
        path.write_bytes(good)
        assert read(path)['grants'] == 2
        for bad in (good[:-1], b'bad', good + b'x',
                    HEADER.pack(b'CSSCH01\0', 1, 3) + good[HEADER.size:],
                    good[:-GRANT.size] + GRANT.pack(7, 4, 10000, 8, 0)):
            path.write_bytes(bad)
            try:
                read(path)
            except ValueError:
                pass
            else:
                raise AssertionError('malformed schedule accepted')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('schedule', type=Path, nargs='?')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print('schedule reader self-test passed')
    elif args.schedule:
        print(json.dumps(read(args.schedule), indent=2))
    else:
        parser.error('schedule required')
