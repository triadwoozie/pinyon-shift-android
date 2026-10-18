"""Add a default-off session read hook to a local generated GetTickCount.

The backup and hashes stay in .local; this tool emits no guest code. It only
edits the selected native definition, leaving its reference copy untouched.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


def instrument(text):
    pattern = r'(DEFINE_REX_FUNC\(sub_823F18B8\) \{\n)(.*?)(^\})'
    matches = list(re.finditer(pattern, text, re.M | re.S))
    if len(matches) != 1:
        raise ValueError('expected one native GetTickCount definition')
    m = matches[0]
    body = m[2]
    if body.count('return;') != 1 or 'session_probe::' in body:
        raise ValueError('unexpected GetTickCount shape')
    hook = ('if (rex::session_probe::Enabled()) ctx.r3.u64 = '
            'rex::session_probe::Scalar(rex::session_probe::Kind::kGuestTickCount, '
            'ctx.lr, ctx.r3.u64, true);\n\t')
    return ('#include <rex/session_probe.h>\n' + text[:m.start(2)] +
            body.replace('return;', hook + 'return;') + text[m.end(2):])


def digest(data):
    return hashlib.sha256(data).hexdigest()


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--generated', type=Path)
    p.add_argument('--backup', type=Path)
    p.add_argument('--restore', action='store_true')
    p.add_argument('--self-test', action='store_true')
    a = p.parse_args()
    if a.self_test:
        source = 'DEFINE_REX_FUNC(sub_823F18B8) {\n ctx.r3.u64 = 7;\n return;\n}\n'
        result = instrument(source)
        assert result.count('Scalar(') == 1 and 'ctx.r3.u64 = 7;' in result
        try:
            instrument(result)
        except ValueError:
            pass
        else:
            raise AssertionError('accepted an instrumented source')
        print('session clock instrumentation self-test passed')
    else:
        if not a.generated or not a.backup:
            p.error('--generated and --backup required')
        if a.restore:
            meta = json.loads((a.backup / 'manifest.json').read_text(encoding='utf-8'))
            target = Path(meta['path'])
            if digest(target.read_bytes()) != meta['instrumented_sha256']:
                raise SystemExit('refusing to overwrite a changed generated partition')
            original = (a.backup / 'original.bin').read_bytes()
            assert digest(original) == meta['original_sha256']
            target.write_bytes(original)
        else:
            found = [x for x in a.generated.glob('*_recomp.*.cpp')
                     if 'DEFINE_REX_FUNC(sub_823F18B8)' in x.read_text(encoding='utf-8')]
            if len(found) != 1:
                raise SystemExit('ambiguous GetTickCount partition')
            target = found[0].resolve()
            original = target.read_bytes()
            output = instrument(original.decode('utf-8')).encode('utf-8')
            a.backup.mkdir(parents=True, exist_ok=False)
            (a.backup / 'original.bin').write_bytes(original)
            (a.backup / 'manifest.json').write_text(json.dumps({
                'path': str(target), 'original_sha256': digest(original),
                'instrumented_sha256': digest(output)}, indent=2), encoding='utf-8')
            target.write_bytes(output)
        print('restored' if a.restore else 'instrumented', target.name)
