"""Guarded local call-budget instrumentation; never emits game code to stdout."""
import argparse
import hashlib
import json
from pathlib import Path
import re


def digest(data): return hashlib.sha256(data).hexdigest()


def instrument(text):
    if '#include <rex/cs_scheduler.h>' in text:
        raise ValueError('already instrumented')
    count=0
    def entry(m):
        nonlocal count
        name=m[1]
        if name.startswith('ref_'): return m[0]
        count+=1
        address=int(name[4:],16) if re.fullmatch(r'sub_[0-9A-F]{8}',name) else int(digest(name.encode())[:8],16)
        return m[0]+'#if defined(REXGLUE_ENABLE_CS_PROBES)\n\trex::cs_scheduler::Call(0x%08X);\n#endif\n'%address
    text=re.sub(r'^DEFINE_REX_FUNC\((\w+)\) \{\n',entry,text,flags=re.M)
    return '#include <rex/cs_scheduler.h>\n'+text,count


def run(a):
    generated=a.generated.resolve()
    if a.restore:
        meta=json.loads((a.backup/'manifest.json').read_text())
        if meta['generated']!=str(generated): raise ValueError('wrong generated tree')
        for row in meta['files']:
            target=(generated/row['name']).resolve()
            if target.parent!=generated or not re.fullmatch(r'\w+_recomp\.\d+\.cpp',target.name):
                raise ValueError('invalid target path')
            if digest(target.read_bytes()) not in (row['instrumented'],row['original']):
                raise ValueError('generated file changed: '+target.name)
            if digest((a.backup/row['name']).read_bytes())!=row['original']:
                raise ValueError('backup changed: '+target.name)
        for row in meta['files']:
            (generated/row['name']).write_bytes((a.backup/row['name']).read_bytes())
        print('restored',len(meta['files']),'partitions')
        return
    a.backup.mkdir(parents=True,exist_ok=False)
    rows=[]; pending=[]
    for p in sorted(generated.glob('*_recomp.*.cpp')):
        data=p.read_bytes();out,n=instrument(data.decode())
        if not n:continue
        encoded=out.encode();(a.backup/p.name).write_bytes(data)
        rows.append(dict(name=p.name,original=digest(data),instrumented=digest(encoded),functions=n))
        pending.append((p,encoded))
    (a.backup/'manifest.json').write_text(json.dumps(dict(generated=str(generated),files=rows),indent=2)+'\n')
    # Journal every original before touching the tree: a compiler may hold a
    # Windows source handle, or the process may stop partway through the writes.
    for p,encoded in pending:
        p.write_bytes(encoded)
    print('instrumented',sum(r['functions'] for r in rows),'functions in',len(rows),'partitions')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--generated',type=Path);p.add_argument('--backup',type=Path)
    p.add_argument('--restore',action='store_true');p.add_argument('--self-test',action='store_true')
    a=p.parse_args()
    if a.self_test:
        text='DEFINE_REX_FUNC(sub_12345678) {\nreturn;\n}\nDEFINE_REX_FUNC(ref_sub_12345678) {\nreturn;\n}\n'
        out,n=instrument(text);assert n==1 and out.count('::Call(')==1
        try:instrument(out)
        except ValueError:pass
        else:raise AssertionError('accepted repeated instrumentation')
        print('schedule instrumentation self-test passed')
    else:
        if not a.generated or not a.backup:p.error('--generated and --backup required')
        run(a)
