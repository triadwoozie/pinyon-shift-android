"""Check active Release objects for session/fence probe definitions and references."""
import argparse
import json
from pathlib import Path
import re
import subprocess

PROBES = re.compile(r'Fh1FrameCensus::(?:Ordered|ObserveOrdered)|fh1_frame_census_ordered|session_probe::|cs_scheduler::|cs_schedule_|cs_session_(?:mode|path)|gpu_(?:true_fence_completion|async_fence)|'
                    r'(?:AwaitGuestGpuCompletion|PublishGuestCompletion|StartCompletionWorker|'
                    r'StopCompletionWorker|CompletionWorkerMain)')


def check(build, nm):
    commands = json.loads((build / 'compile_commands.json').read_text())
    objects = []
    for row in commands:
        # Vendored dependency objects cannot contain these SDK probes.
        if '/thirdparty/shiftglue-sdk/thirdparty/' in row['file'].replace('\\', '/'):
            continue
        obj = Path(row['output'])
        if not obj.is_absolute():
            obj = Path(row['directory']) / obj
        if obj.suffix not in ('.obj', '.o'):
            continue
        if obj.exists():
            objects.append(obj)
        if row['file'].replace('\\', '/').endswith(('/session_probe.cpp','/cs_scheduler.cpp','/fh1_frame_order.cpp')):
            raise ValueError('a probe implementation is in the active build')
    found = []
    for start in range(0, len(objects), 40):
        result = subprocess.run([str(nm), '--demangle', *map(str, objects[start:start+40])],
                                check=True, capture_output=True, text=True)
        found.extend(line for line in result.stdout.splitlines() if PROBES.search(line))
    if not objects:
        raise ValueError('no objects checked')
    return {'objects_checked': len(objects), 'probe_symbols': sorted(set(found)),
            'result': 'FAIL' if found else 'PASS'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--build', type=Path, required=True)
    p.add_argument('--nm', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    result = check(a.build, a.nm)
    a.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    raise SystemExit(result['result'] != 'PASS')
