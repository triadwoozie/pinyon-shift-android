"""CS-0.2 deliberately overgenerous sampled context-traffic ceiling.

Requires capstone==5.0.6 in an analysis-only environment. Decode sampled PCs
with their captured registers and count *all* memory operands intersecting
any logged PPCContext, even ABI arguments, returns and callee-saved slots.
Unknown decodes, binaries or effective addresses also count as removable.
No disassembly, memory contents or register values are emitted.
"""
import argparse
from bisect import bisect_right
from collections import Counter, defaultdict
import csv
from datetime import datetime
from functools import lru_cache
import json
from pathlib import Path
import re
import struct

import capstone as cs
from capstone import arm64, x86


def contexts(path):
    ranges, threads = [], {}
    for line in path.read_text(encoding='utf-8').splitlines():
        m = re.search(r'CS_CTX tid=(\d+) guest=([0-9A-F]+) ctx=([0-9A-F]+) size=(\d+)', line)
        if m:
            tid, guest, pointer, size = m.groups()
            # spdlog records the native TID. The first probe build's explicit
            # tid field used Thread::system_id(), a pthread value on Android.
            native = re.search(r'\[t(\d+)\]', line)
            if native: tid = native[1]
            ranges.append((int(pointer, 16), int(pointer, 16) + int(size)))
            threads[int(tid)] = guest
    assert ranges, 'no context ranges'
    return sorted(ranges), threads


class Decoder:
    def __init__(self, directories, ranges, arch):
        self.directories = directories
        self.ranges = sorted((a & 0x00ffffffffffffff, b & 0x00ffffffffffffff) for a,b in ranges) if arch == 'arm64' else ranges
        self.starts = [a for a, b in self.ranges]
        self.arch = arch
        self.decoder = cs.Cs(cs.CS_ARCH_X86, cs.CS_MODE_64) if arch == 'x64' else cs.Cs(cs.CS_ARCH_ARM64, cs.CS_MODE_ARM)
        self.decoder.detail = True
        self.files = {}

    def intersects(self, address):
        if self.arch == 'arm64': address &= 0x00ffffffffffffff
        # Count operands within 64 bytes of a context too, covering wide/pair
        # accesses and deliberately granting adjacent traffic as a bonus.
        i = bisect_right(self.starts, address + 63) - 1
        return i >= 0 and address < self.ranges[i][1]

    def binary(self, name):
        name = Path(name).name
        if name not in self.files:
            candidates = [d / name for d in self.directories]
            if self.arch == 'x64':
                candidates += [d / (name + '.dll') for d in self.directories]
                candidates += [d / (name + '.exe') for d in self.directories]
            p = next((p for p in candidates if p.is_file()), None)
            self.files[name] = p.read_bytes() if p else None
        return self.files[name]

    @lru_cache(maxsize=200000)
    def instruction(self, name, offset):
        data = self.binary(name)
        if data is None:
            return None
        if self.arch == 'x64':
            pe = struct.unpack_from('<I', data, 0x3c)[0]
            sections = struct.unpack_from('<H', data, pe + 6)[0]
            optional = struct.unpack_from('<H', data, pe + 20)[0]
            table = pe + 24 + optional
            for i in range(sections):
                size, rva, rawsize, raw = struct.unpack_from('<IIII', data, table + i * 40 + 8)
                if rva <= offset < rva + rawsize:
                    offset = raw + offset - rva
                    break
            else:
                return None
        if not 0 <= offset < len(data):
            return None
        return next(self.decoder.disasm(data[offset:offset + 16], 0, count=1), None)

    def classify(self, name, offset, regs):
        self.last_offset = None
        ins = self.instruction(name, offset)
        self.last_instruction = ins
        if ins is None:
            return 'unknown'
        unknown = False
        for op in ins.operands:
            if op.type != (x86.X86_OP_MEM if self.arch == 'x64' else arm64.ARM64_OP_MEM):
                continue
            mem = op.mem
            base = ins.reg_name(mem.base)
            index = ins.reg_name(mem.index)
            if self.arch == 'x64' and (base in ('rip', 'eip') or mem.segment):
                unknown = True
                continue
            try:
                def value(reg):
                    if not reg: return 0
                    if reg in ('xzr', 'wzr'): return 0
                    if reg in ('fp', 'lr'): return regs['x29' if reg == 'fp' else 'x30']
                    if reg.startswith('w') and reg[1:].isdigit(): return regs['x' + reg[1:]] & 0xffffffff
                    if reg.startswith('e') and reg not in regs: return regs['r' + reg[1:]] & 0xffffffff
                    return regs[reg]
                if self.arch == 'x64':
                    address = value(base) + value(index) * mem.scale + mem.disp
                else:
                    iv = value(index)
                    if op.ext in (arm64.ARM64_EXT_UXTW, arm64.ARM64_EXT_SXTW):
                        iv &= 0xffffffff
                        if op.ext == arm64.ARM64_EXT_SXTW and iv & 0x80000000: iv -= 1 << 32
                    if op.shift.type not in (0, arm64.ARM64_SFT_LSL):
                        unknown = True
                        continue
                    address = value(base) + (iv << op.shift.value) + mem.disp
                if self.intersects(address & ((1 << 64) - 1)):
                    if self.arch == 'arm64': address &= 0x00ffffffffffffff
                    i = bisect_right(self.starts, address + 63) - 1
                    self.last_offset = address - self.starts[i]
                    return 'ctx'
            except KeyError:
                unknown = True
        if self.arch == 'arm64' and ins.mnemonic.startswith('ld') and any(op.type == arm64.ARM64_OP_IMM for op in ins.operands):
            unknown = True  # PC-relative literal loads are granted too.
        return 'unknown' if unknown else 'other'


def race_window(path):
    events = [json.loads(line) for line in path.read_text().splitlines()]
    marks = {e.get('name'): datetime.fromisoformat(e['utc'].replace('Z', '+00:00')).timestamp()
             for e in events if e.get('event') == 'fh1.render_test.capture'}
    # UTC markers have one-second precision. Avoid both capture spikes and
    # their timestamp rounding with a fixed two-second margin on each side.
    return marks['gauntlet-driving'] + 2, marks['gauntlet-end'] - 2


def windows_samples(path, epoch):
    with path.open() as f:
        for row in csv.DictReader(f):
            yield (int(row['thread']), epoch + float(row['t_ms']) / 1000,
                   row['module'], int(row['rva'], 16), int(row['cycles']),
                   {k: int(v, 16) for k, v in row.items() if k in
                    ('rax','rbx','rcx','rdx','rsi','rdi','rbp','rsp','r8','r9','r10','r11','r12','r13','r14','r15')})


def android_samples(path):
    """Stream simpleperf dump with --call-graph dwarf,8 --no-unwind.

    File offsets come from the actual MMAP2 records, not symbol guesses.
    The input recording must already be confined to the race window.
    """
    mappings, starts = {}, []
    def records():
        block = []
        with path.open() as f:
            for line in f:
                if line.startswith('record '):
                    if block: yield ''.join(block)
                    block = [line]
                elif block: block.append(line)
        if block: yield ''.join(block)
    for text in records():
        if text.startswith('record mmap2:'):
            m = re.search(r'addr (0x[0-9a-f]+), len (0x[0-9a-f]+).*?pgoff (0x[0-9a-f]+).*?filename (.+)', text, re.S)
            if m:
                start, size, off = (int(m[i],16) for i in (1,2,3))
                filename = m[4].splitlines()[0].strip()
                mappings[start] = (start + size, off, filename)
                starts = sorted(mappings)
        elif text.startswith('record sample:'):
            def integer(pattern):
                m = re.search(pattern,text);return int(m[1],0) if m else None
            ip = integer(r'\n  ip (0x[0-9a-f]+)')
            tid = integer(r'pid \d+, tid (\d+)')
            timestamp = integer(r'\n  time (\d+)')
            period = integer(r'\n  period (\d+)')
            assert None not in (ip,tid,timestamp,period), 'incomplete sample'
            regs = {}
            for name, value in re.findall(r'reg \((\w+)\) (0x[0-9a-f]+)',text):
                key = 'x' + name[1:] if name.startswith('r') and name[1:].isdigit() else 'x30' if name == 'lr' else name
                regs[key] = int(value,16)
            assert 'pc' in regs and 'sp' in regs and len(regs)>=33, 'missing user registers'
            i = bisect_right(starts,ip)-1
            name, offset = '?', 0
            if i >= 0:
                start=starts[i];end,off,filename=mappings[start]
                if ip < end: name,offset=filename,off+ip-start
            yield tid,timestamp/1e9,name,offset,period,regs


def upper99(p, n):
    if not n: return 1.
    z = 3.291  # one-sided 99.95%; conservative across the six hot rows
    return (p + z*z/(2*n) + z*((p*(1-p)/n + z*z/(4*n*n))**.5)) / (1 + z*z/n)


def summarize(samples, decoder, threads, window, abi=None):
    rows = defaultdict(Counter)
    blocks = defaultdict(lambda: defaultdict(Counter))
    for tid, timestamp, name, offset, weight, regs in samples:
        if threads.get(tid) not in ('825A6320','8255AE10','82FB4AF8') or not window[0] <= timestamp <= window[1]:
            continue
        kind = decoder.classify(name, offset, regs)
        row = rows[tid]
        row[kind] += 1
        row[kind + '_weight'] += weight
        if kind == 'ctx':
            ins=decoder.last_instruction
            load = ins.mnemonic.startswith('ld') if decoder.arch == 'arm64' else ins.operands[0].type != x86.X86_OP_MEM
            row['ctx_load' if load else 'ctx_store'] += 1
            row['offset_' + str(decoder.last_offset)] += 1
            if abi and Path(name).name=='libmain.so' and abi.needed(offset,decoder.last_offset,load):row['abi_needed']+=1
        project = Path(name).name.lower().startswith(('pinyon_shift', 'libmain', 'rex', 'librex'))
        if project:
            row['project'] += 1
            row['project_weight'] += weight
        row['samples'] += 1
        row['weight'] += weight
        blocks[tid][int(timestamp)][kind] += weight
    result = {}
    for tid, row in rows.items():
        removable = row['ctx'] + row['unknown']
        # The project-only denominator grants all candidate traffic in native
        # libraries as a bonus without counting those libraries' instructions.
        p = min(1., removable / row['project']) if row['project'] else 1.
        result[str(tid)] = dict(row, guest=threads[tid],
            all_samples_fraction=removable / row['samples'],
            weighted_fraction=(row['ctx_weight'] + row['unknown_weight']) / row['weight'],
            project_denominator_ceiling_estimate=p,
            project_denominator_upper99_95=upper99(p, row['project']),
            abi_ceiling_estimate=(removable-row['abi_needed'])/row['samples'],
            abi_upper99_95=upper99((removable-row['abi_needed'])/row['samples'],row['samples']))
    return result


def self_test():
    d = Decoder([], [(0x1000,0x2000)], 'x64')
    d.instruction = lambda name, offset: next(d.decoder.disasm(bytes.fromhex('488b4308'), 0))
    assert d.classify('',0,{'rbx':0x1000}) == 'ctx'
    assert d.classify('',0,{'rbx':0x3000}) == 'other'
    assert d.classify('',0,{}) == 'unknown'
    d.instruction = lambda name, offset: next(d.decoder.disasm(bytes.fromhex('f30f10c1'),0))
    assert d.classify('',0,{'rsi':0x1000}) == 'other'  # register-only movss
    d.instruction = lambda name, offset: next(d.decoder.disasm(bytes.fromhex('f3a4'),0))
    assert d.classify('',0,{'rsi':0x1000,'rdi':0x3000}) == 'ctx'
    a = Decoder([], [(0x1000,0x2000)], 'arm64')
    a.instruction = lambda name, offset: next(a.decoder.disasm(bytes.fromhex('200040f9'), 0))
    assert a.classify('',0,{'x1':0x1000}) == 'ctx'
    assert a.classify('',0,{'x1':0x3000}) == 'other'
    assert a.classify('',0,{'x1':0xb400000000001000}) == 'ctx'  # Android TBI
    assert upper99(.01,10000) < .015


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--windows', type=Path)
    p.add_argument('--android-dump', type=Path)
    p.add_argument('--epoch', type=Path)
    p.add_argument('--runtime', type=Path)
    p.add_argument('--events', type=Path)
    p.add_argument('--binaries', type=Path, action='append', default=[])
    p.add_argument('--output', type=Path)
    p.add_argument('--self-test', action='store_true')
    p.add_argument('--abi-sets', type=Path)
    p.add_argument('--layout', type=Path)
    a = p.parse_args(); self_test()
    if a.self_test:
        print('ctx traffic ceiling self-test passed')
    else:
        if not all((a.runtime,a.output)) or bool(a.windows) == bool(a.android_dump): p.error('one sample input, runtime and output required')
        ranges, threads = contexts(a.runtime)
        if a.windows:
            if not a.epoch or not a.events: p.error('Windows epoch and events required')
            samples=windows_samples(a.windows,float(a.epoch.read_text())/1000)
            window=race_window(a.events);arch='x64'
        else:
            samples=android_samples(a.android_dump);window=(0,1e20);arch='arm64'
        abi=None
        if a.abi_sets:
            spec=__import__('importlib.util',fromlist=['']).spec_from_file_location('ctx_abi_model',Path(__file__).with_name('ctx-abi-model.py'))
            mod=__import__('importlib.util',fromlist=['']).module_from_spec(spec);spec.loader.exec_module(mod)
            abi=mod.Model(next(d/'libmain.so' for d in a.binaries if (d/'libmain.so').exists()),json.loads(a.abi_sets.read_text()),json.loads(a.layout.read_text()))
        result = {'method':'all ctx operands and unknowns removable; optional optimistic ABI retention',
                  'window_seconds':window,
                  'threads':summarize(samples,Decoder(a.binaries,ranges,arch),threads,window,abi)}
        a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
