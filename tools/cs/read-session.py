"""Inspect v1 global or v2 per-thread CS-0.1 traces; never print payloads."""
import argparse
from collections import Counter
import json
from pathlib import Path
import struct

MAGIC = b'CS0LOG1\0'
MAGIC2 = b'CS0LOG2\0'
HEADER = struct.Struct('<IIQQIIQ')
HEADER2 = struct.Struct('<IIQQQQQ')
KINDS = ['invalid','timebase','system_time','uptime','input_result','input_state',
         'sync_request','sync_result','io_read','io_write','timer_fire','pm4_frame',
         'thread_start','signal','io_data','gpu_interrupt','gpu_fence','pm4_words',
         'performance_frequency','vblank_delivery','timestamp_delivery','apc_delivery',
         'host_epoch','input_caps_result','input_caps_data','input_key_result',
         'input_key_data','io_read_request','io_read_data','semaphore_release',
         'object_slot','thread_id','kernel_order','guest_tick_count','io_result','io_metadata',
         'lock_grant','try_lock','interrupt_delivery','callback_registration','mmio_read',
         'allocation_request','allocation_result','thread_cpu','thread_tls','thread_tls_data',
         'guest_memory','host_hook','audio_pump','audio_callback']


def records(stream):
    magic = stream.read(8)
    if magic not in (MAGIC, MAGIC2):
        raise ValueError('invalid trace header')
    v2 = magic == MAGIC2
    tag_data = stream.read(8) if v2 else b''
    if v2 and len(tag_data) != 8:
        raise ValueError('truncated tag')
    tag = int.from_bytes(tag_data, 'little')
    header = HEADER2 if v2 else HEADER
    index = 0
    while raw := stream.read(header.size):
        if len(raw) != header.size:
            raise ValueError(f'truncated record at {index}')
        if v2:
            kind, size, thread, seq, key, value, ns = header.unpack(raw)
            if thread != tag or seq != index:
                raise ValueError(f'thread/sequence mismatch at {index}')
        else:
            kind, thread, key, value, size, reserved, ns = header.unpack(raw)
            if reserved:
                raise ValueError(f'reserved bits at {index}')
        if kind < 1 or kind >= len(KINDS) or size > 4096:
            raise ValueError(f'invalid record at {index}')
        payload = stream.read(size)
        if len(payload) != size:
            raise ValueError(f'truncated payload at {index}')
        yield {'index': index, 'kind': KINDS[kind], 'thread': f'{thread:016X}',
               'key': key, 'value': value, 'size': size, 'host_ns': ns}
        index += 1


def inspect(path):
    kinds, threads = Counter(), Counter()
    frames, first = [], []
    total = bytes_total = 0
    first_ns = last_ns = None
    paths = sorted(path.glob('*.bin')) if path.is_dir() else [path]
    for p in paths:
        bytes_total += p.stat().st_size
        with p.open('rb') as f:
            for i, r in enumerate(records(f)):
                first_ns = min(first_ns, r['host_ns']) if first_ns is not None else r['host_ns']
                last_ns = max(last_ns, r['host_ns']) if last_ns is not None else r['host_ns']
                kinds[r['kind']] += 1
                threads[r['thread']] += 1
                if i < 40:
                    first.append(r)
                if r['kind'] == 'pm4_frame':
                    frames.append({'frame': r['key'], 'hash': f"{r['value']:016X}",
                                   'host_ns': r['host_ns']})
                total += 1
    for frame in frames:
        frame['seconds'] = (frame.pop('host_ns') - first_ns) / 1e9
    return {'file': str(path), 'bytes': bytes_total, 'records': total,
            'streams': len(paths), 'duration_seconds': (last_ns - first_ns) / 1e9 if total else 0,
            'kinds': dict(kinds), 'threads': dict(threads),
            'first_records': sorted(first, key=lambda r: r['host_ns']),
            'frames': sorted(frames, key=lambda r: r['frame'])}


def self_test():
    import io
    good = MAGIC + HEADER.pack(11, 7, 2, 123, 0, 0, 55)
    good2 = MAGIC2 + struct.pack('<Q', 7) + HEADER2.pack(11, 0, 7, 0, 2, 123, 55)
    for trace in (good, good2):
        assert list(records(io.BytesIO(trace)))[0]['kind'] == 'pm4_frame'
    for bad in (b'bad', good[:-1], MAGIC + HEADER.pack(1, 0, 0, 0, 4097, 0, 0),
                good2[:-1], MAGIC2 + struct.pack('<Q', 7) + HEADER2.pack(1, 0, 8, 0, 0, 0, 0)):
        try:
            list(records(io.BytesIO(bad)))
        except ValueError:
            pass
        else:
            raise AssertionError('malformed log accepted')


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('trace', nargs='?', type=Path)
    p.add_argument('--output', type=Path)
    p.add_argument('--self-test', action='store_true')
    a = p.parse_args()
    self_test()
    if a.self_test:
        print('session reader self-test passed')
    else:
        if not a.trace:
            p.error('trace required')
        result = inspect(a.trace)
        if a.output:
            a.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({k:v for k,v in result.items() if k not in ('first_records','frames')}, indent=2))
