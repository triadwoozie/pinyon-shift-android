"""CS-0.2 caller-demand census, with explicit conservative observer contracts.

Analysis only. Unknown targets/escapes observe and may change every slot;
no ABI assumptions or runtime-observed target lists are used. Count memory
instructions, retaining a pair if either component is required. Deletions
keep required publications at their original stores, so no new store site
is introduced. Compare against a read-before-write-only model to expose the
extra publications required by conditional callee writes. Register spills
and non-context instructions are outside this semantic census.
"""
import argparse
from collections import Counter, defaultdict, deque
import hashlib
import json
from pathlib import Path
import re
import struct

import capstone as cs
from capstone import arm64

# Reuse the matched binary reader, CFG and conservative alias analysis.
import importlib.util
import sys
spec = importlib.util.spec_from_file_location('ctx_strict', Path(__file__).with_name('ctx-strict-model.py'))
strict = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = strict
spec.loader.exec_module(strict)
s = strict.s
FULL_SLOT = re.compile(r'^ctx\.(?:(?:r\d+|f\d+|ctr|reserved)(?:\.(?:u64|s64|f64))?|'
                       r'lr|xer|msr|cr\d+|fpscr(?:\.csr)?|vscr_sat|'
                       r'last_indirect_target|dispatch_address)\s*([+&|^*/-]?=)(?!=)')


def effects(line):
    """Whole-slot effects; partial writes preserve the untouched old bytes."""
    read = 0
    for name in s.CTX.findall(line):
        read |= s.BITS.get(name, s.ALL)
    write = kill = 0
    lhs = re.match(r'^ctx\.(\w+)(?:\.[\w\[\]]+)*\s*([+&|^*/-]?=)(?!=)', line)
    if lhs:
        write = s.BITS.get(lhs[1], s.ALL)
        full = FULL_SLOT.match(line)
        if full:
            kill = write
            if full[1] == '=':
                read = 0
                for name in s.CTX.findall(line.split('=', 1)[1]):
                    read |= s.BITS.get(name, s.ALL)
    else:
        # Conditional/loop assignments may write the destination, but are not
        # a definite full-slot kill. Include the old slot for partial updates.
        for name in re.findall(r'ctx\.(\w+)(?:\.[\w\[\]]+)*\s*[+&|^*/-]?=(?!=)',line):
            write |= s.BITS.get(name,s.ALL)
    member = re.match(r'ctx\.(cr\d+|fpscr)\.(\w+)(?:<[^>]+>)?\(', line)
    if member:
        name, method = member[1], member[2]
        if name.startswith('cr') and method in ('compare', 'setFromMask', 'set_raw'):
            write |= s.BITS[name]; kill |= write
            read = 0
            for name in s.CTX.findall(line[line.index('(')+1:]):
                read |= s.BITS.get(name, s.ALL)
        elif name == 'fpscr':
            if method in ('loadFromHost', 'InitHost', 'restoreGuestBits'):
                write |= s.BITS[name]; kill |= write
                read &= ~s.BITS[name]
                for arg in s.CTX.findall(line[line.index('(')+1:]): read |= s.BITS.get(arg,s.ALL)
            elif method in ('storeFromGuest','enableFlushMode','disableFlushMode',
                            'enableFlushModeUnconditional','disableFlushModeUnconditional'):
                write |= s.BITS[name]
            elif method in ('getcsr','setcsr'):
                read &= ~s.BITS[name]
                for arg in s.CTX.findall(line[line.index('(')+1:]): read |= s.BITS.get(arg,s.ALL)
            else:
                read, write, kill = s.ALL, s.ALL, 0
    vector = re.match(r'simde_mm_store_(?:si128|ps)\([^,]*ctx\.(v\d+)\.', line)
    if vector:
        write |= s.BITS[vector[1]]
        kill |= write
        read = 0
        for name in s.CTX.findall(line.split(',', 1)[1]):
            read |= s.BITS.get(name, s.ALL)
    expected = re.search(r'compare_exchange_strong\(\s*ctx\.(\w+)\.',line)
    if expected:
        write |= s.BITS.get(expected[1],s.ALL)
    return read, write, kill


def lower_switches(body, sites):
    def lower(m):
        selector, arms = m[1], m[2]
        case = re.compile(r'((?:\s*case (?:0x[\dA-Fa-f]+|\d+):)+)\s*goto (loc_\w+);')
        branches = []
        for arm in case.finditer(arms):
            for value in re.findall(r'case (0x[\dA-Fa-f]+|\d+):', arm[1]):
                branches.append(f'if ({selector} == {value}) goto {arm[2]};')
        rest = case.sub('', arms).strip()
        if not branches or (rest and not rest.startswith('default:')):
            sites['switch_unproved_all'] += 1
            return m[0]
        sites['switch_cfg_resolved'] += 1
        return '\n'.join(branches) + '\n' + rest.removeprefix('default:')
    return re.sub(r'switch\s*\(([^()]+)\)\s*\{([^{}]*)\}', lower, body)


def parse(body, mappings, sites):
    body = lower_switches(body, sites)
    blocks = [s.Block()]
    labels = {}
    constants = {}
    for raw in body.splitlines():
        line = raw.split('//', 1)[0].strip()
        if not line or line in ('{', '}') or line.startswith('#'):
            continue
        label = re.fullmatch(r'(loc_\w+):', line)
        if label:
            blocks.append(s.Block()); labels[label[1]] = len(blocks)-1
            constants.clear()
            continue
        b = blocks[-1]
        read, write, kill = effects(line)
        direct = re.search(r'\b([A-Za-z_]\w*)\(ctx, base\)|REX_TAIL_CALL\(([A-Za-z_]\w*)\)', line)
        indirect = re.search(r'REX_CALL_INDIRECT_FUNC\(([^)]+)\)', line)
        trap = re.search(r'ppc_trap\(ctx, base, (\d+)\)', line)
        opaque = False
        if trap:
            if int(trap[1]) in (20,26): read |= s.BITS['r3'] | s.BITS['r4']
            sites['trap_value_only'] += 1
        elif re.fullmatch(r'\w+\s*=\s*ctx;', line):
            read, write, kill = s.ALL, 0, 0
            sites['context_snapshot'] += 1
        elif re.search(r'\bctx\s*=\s*\w+;', line):
            read, write, kill = 0, s.ALL, s.ALL if line.startswith('ctx ') else 0
            sites['context_restore'] += 1
        elif direct:
            b.calls.append(direct[1] or direct[2])
            sites['direct'] += 1
        elif indirect:
            arg = indirect[1]
            address = int(arg, 16) if re.fullmatch(r'0x[\dA-Fa-f]+', arg) else constants.get(re.sub(r'\.u(?:32|64)$','',arg))
            if address is not None: address &= 0xffffffff
            target = mappings.get(address)
            if target:
                b.calls.append(target); sites['indirect_literal_resolved'] += 1
            else:
                b.calls.append('?indirect'); sites['indirect_all'] += 1
        elif re.search(r'\bctx\b(?!\.)', line) or 'switch' in line or line.startswith('case '):
            read, write, kill = s.ALL, s.ALL, 0
            opaque = True; sites['opaque_full_context_or_control'] += 1
        elif '(' in line:
            # A mutable reference or address passed to an unproved helper is
            # a publication of the complete context, never an ABI argument set.
            # Pure arithmetic casts and compiler builtins take values only.
            names = re.findall(r'(?<![\w.:])([A-Za-z_]\w*(?:::\w+)*)(?:<[^<>]+>)?\s*\(', line)
            pure = all(n.startswith(('REX_LOAD_', 'REX_STORE_', 'REX_STACK_', 'REX_RAW_ADDR',
                                     'simde_mm_',
                                     'rex::ppc::simde_mm_', '__builtin_')) or
                       n in ('uint8_t','uint16_t','uint32_t','uint64_t','int8_t','int16_t',
                             'int32_t','int64_t','float','double','bool','int','static_cast',
                             'reinterpret_cast','if','while','for','switch','sizeof','alignof','short',
                             'std::fma','std::isnan','std::isinf','std::abs','std::sqrt',
                             'log2f','sqrt','std::log2','std::numeric_limits','REX_FUNC_PROLOGUE',
                             'compare_exchange_strong','REX_PPC_SYNC','REX_PPC_LWSYNC','REX_PPC_EIEIO',
                             'REX_CHECK_GLOBAL_LOCK','REX_ENTER_GLOBAL_LOCK','REX_LEAVE_GLOBAL_LOCK',
                             'REX_ENTER_GLOBAL_LOCK_RESERVATION','REX_QUERY_TIMEBASE') for n in names)
            methods = re.findall(r'\.(\w+)(?:<[^<>]+>)?\s*\(',line)
            pure = pure and all(n in ('compare','setFromMask','set_raw','raw','load','store',
                                     'compare_exchange_strong','enableFlushMode','disableFlushMode',
                                     'enableFlushModeUnconditional','disableFlushModeUnconditional',
                                     'storeFromGuest','loadFromHost','getcsr','setcsr','InitHost',
                                     'restoreGuestBits') for n in methods)
            if not pure:
                read, write, kill = s.ALL, s.ALL, 0
                opaque = True; sites['opaque_helper_all'] += 1
                for name in set(names): sites['opaque_helper:'+name] += 1
        elif write:
            sites['explicit_slot_write'] += 1
        b.read |= read & ~b.kill
        b.write |= write
        b.kill |= kill
        const = re.fullmatch(r'([\w.]+)\s*=\s*(0x[\dA-Fa-f]+|\d+);', line)
        if const:
            constants[re.sub(r'\.u(?:32|64)$','',const[1])] = int(const[2], 0)
        else:
            copy = re.fullmatch(r'([\w.]+)\s*=\s*([\w.]+);', line)
            value = constants.get(re.sub(r'\.u(?:32|64)$','',copy[2])) if copy else None
            for name in re.findall(r'([\w.]+)\s*=(?!=)', line):
                constants.pop(re.sub(r'\.u(?:32|64)$','',name), None)
            if copy and value is not None:
                constants[re.sub(r'\.u(?:32|64)$','',copy[1])] = value
        goto = re.search(r'goto (loc_\w+)', line)
        if goto:
            b.targets.append(goto[1]); b.fallthrough = not line.startswith('goto ')
        terminal = line == 'return;' or line.startswith('REX_TAIL_CALL')
        if terminal:
            b.fallthrough = False
        if b.calls or goto or terminal or opaque:
            blocks.append(s.Block())
            constants.clear()
    for i, b in enumerate(blocks):
        b.successors = [labels[t] for t in b.targets if t in labels]
        if len(b.successors) != len(b.targets):
            b.read, b.write = s.ALL, s.ALL
        if b.fallthrough and i+1 < len(blocks):
            b.successors.append(i+1)
    return s.Function(blocks, 0, 0, 0, 0)


UNKNOWN = dict(reads=s.ALL, writes=s.ALL, must=0)


def native_contracts():
    """Reviewed SDK bodies, including the marshal in ppc/function.h.

    Guest pointers map into the guest arena, disjoint from PPCContext.
    These bodies do not execute guest callbacks or expose a context pointer.
    Every other native entry retains the ALL observer contract.
    """
    result = {}
    def add(names, inputs, output, proof):
        reads = sum(s.BITS[n] for n in inputs.split())
        writes = s.BITS[output] if output else 0
        for name in names.split():
            contract = dict(reads=reads,writes=writes,must=writes,proof=proof)
            result[name] = contract; result['__imp__'+name] = contract
    add('rexcrt_memcpy rexcrt_memmove rexcrt_memset rexcrt_XMemCpy rexcrt_XMemSet '
        'rexcrt_XMemSet128 rexcrt_memset_vmx', 'r3 r4 r5', '', 'src/kernel/crt/memory.cpp; void marshal preserves r3')
    add('rexcrt_memchr', 'r3 r4 r5', 'r3', 'src/kernel/crt/memory.cpp; pointer-result marshal')
    add('rexcrt_memcpy_s rexcrt_memmove_s', 'r3 r4 r5 r6', 'r3', 'src/kernel/crt/memory.cpp; integer-result marshal')
    add('RtlEnterCriticalSection', 'r3 r13', '', 'src/kernel/xboxkrnl/xboxkrnl_rtl.cpp; raw entry and CriticalSectionThread')
    add('RtlTryEnterCriticalSection', 'r3 r13', 'r3', 'src/kernel/xboxkrnl/xboxkrnl_rtl.cpp; raw entry')
    add('RtlLeaveCriticalSection RtlInitializeCriticalSection', 'r3', '', 'src/kernel/xboxkrnl/xboxkrnl_rtl.cpp; void marshal; non-alertable critical-section wait')
    add('RtlInitializeCriticalSectionAndSpinCount', 'r3 r4', 'r3', 'src/kernel/xboxkrnl/xboxkrnl_rtl.cpp; integer-result marshal')
    add('RtlInitAnsiString', 'r3 r4', '', 'src/kernel/xboxkrnl/xboxkrnl_rtl.cpp; guest-memory string operation')
    add('RtlNtStatusToDosError', 'r3', 'r3', 'src/kernel/xboxkrnl/xboxkrnl_error.cpp; status mapping')
    add('KeGetCurrentProcessType', '', 'r3', 'src/kernel/xboxkrnl/xboxkrnl_threading.cpp; process type query')
    return result


def plt_imports(binary):
    data = binary.read_bytes()
    shoff = struct.unpack_from('<Q',data,40)[0]
    shsize,shnum,shstr = struct.unpack_from('<HHH',data,58)
    sections = [struct.unpack_from('<IIQQQQIIQQ',data,shoff+i*shsize) for i in range(shnum)]
    names = sections[shstr]; names = data[names[4]:names[4]+names[5]]
    relocations = {}
    for sec in sections:
        if sec[1] != 4: continue
        table = sections[sec[6]]; st = sections[table[6]]; st = data[st[4]:st[4]+st[5]]
        for off in range(sec[4],sec[4]+sec[5],sec[9]):
            address,info,_ = struct.unpack_from('<QQq',data,off)
            n = struct.unpack_from('<I',data,table[4]+(info>>32)*table[9])[0]
            relocations[address] = st[n:st.find(b'\0',n)].decode(errors='replace')
    decoder = cs.Cs(cs.CS_ARCH_ARM64,cs.CS_MODE_ARM); decoder.detail = True
    result = {}
    for sec in sections:
        if names[sec[0]:names.find(b'\0',sec[0])] != b'.plt': continue
        insns = list(decoder.disasm(data[sec[4]:sec[4]+sec[5]],sec[3]))
        for i,ins in enumerate(insns[:-1]):
            if ins.mnemonic != 'adrp': continue
            nxt = insns[i+1]
            if nxt.mnemonic != 'ldr' or len(nxt.operands)<2: continue
            page = ins.operands[1].imm
            mem = nxt.operands[1]
            if mem.type != arm64.ARM64_OP_MEM or mem.mem.base != ins.operands[0].reg: continue
            name = relocations.get(page+mem.mem.disp)
            if name:
                pos = sec[4]+ins.address-sec[3]
                result[pos] = name
                if i and insns[i-1].mnemonic == 'bti': result[pos-4] = name
    return result


def symbols(binary):
    data = binary.read_bytes()
    shoff = struct.unpack_from('<Q', data, 40)[0]
    shsize, shnum = struct.unpack_from('<HH', data, 58)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff+i*shsize) for i in range(shnum)]
    phoff = struct.unpack_from('<Q', data, 32)[0]
    phsize, phnum = struct.unpack_from('<HH', data, 54)
    segments = [struct.unpack_from('<IIQQQQQQ', data, phoff+i*phsize) for i in range(phnum)]
    result = {}
    for sec in sections:
        if sec[1] != 2: continue
        st = sections[sec[6]]; strings = data[st[4]:st[4]+st[5]]
        for offset in range(sec[4], sec[4]+sec[5], sec[9]):
            n, info, _, _, va, size = struct.unpack_from('<IBBHQQ', data, offset)
            if info & 15 != 2 or not size: continue
            name = strings[n:strings.find(b'\0',n)].decode(errors='replace')
            pos = next((p[2]+va-p[3] for p in segments if p[0] == 1 and p[3] <= va < p[3]+p[5]), None)
            if pos is not None: result[name] = (pos, size)
    return result


def canonical(name, replaced):
    return name if not name.startswith('__imp__') or name[7:] in replaced else name[7:]


def liveness(f, summaries, returned):
    live = [0] * len(f.blocks)
    changed = True
    while changed:
        changed = False
        for i in reversed(range(len(live))):
            b = f.blocks[i]
            tail = 0
            for j in b.successors:
                tail |= live[j]
            if not b.successors:
                tail |= returned
            for target in reversed(b.calls):
                c = summaries.get(target, UNKNOWN)
                tail = c['reads'] | (tail & ~c['must'])
            value = b.read | (tail & ~b.kill)
            if value != live[i]:
                live[i] = value; changed = True
    return live


def solve(fs, mapped, native_callbacks=frozenset()):
    summaries = {name: dict(reads=0, writes=0, must=0, returned=0) for name in fs}
    callers = defaultdict(set)
    for name, f in fs.items():
        for b in f.blocks:
            for c in b.calls:
                callers[c].add(name)
    def queue_solve(step):
        queue = deque(fs); pending = set(fs)
        while queue:
            name = queue.popleft(); pending.remove(name)
            if step(name):
                for c in callers[name]:
                    if c not in pending:
                        queue.append(c); pending.add(c)
    def must(name):
        value = strict.must_summary(fs[name], summaries)
        old = summaries[name]['must']; summaries[name]['must'] = value
        return old != value
    queue_solve(must)
    def reads_writes(name):
        reads = liveness(fs[name], summaries, 0)[0]
        writes = 0
        for b in fs[name].blocks:
            writes |= b.write
            for c in b.calls:
                writes |= summaries.get(c, UNKNOWN)['writes']
        old = summaries[name]['reads'], summaries[name]['writes']
        summaries[name].update(reads=reads, writes=writes)
        return old != (reads, writes)
    queue_solve(reads_writes)
    # Unknown indirect calls can reach every dispatch-table entry. Their
    # continuation demand is shared by those targets, not replaced by an ABI
    # result mask. Start at bottom and propagate actual caller continuations.
    indirect_demand = 0
    # Native replacement wrappers can call their strong original body and
    # inspect the resulting context. Their source is opaque to this CFG.
    for name in native_callbacks:
        summaries[name]['returned'] = s.ALL
    changed = True
    iterations = 0
    while changed:
        changed = False; iterations += 1
        broad_return = 0
        for name, f in fs.items():
            live = liveness(f, summaries, summaries[name]['returned'])
            for b in f.blocks:
                tail = summaries[name]['returned'] if not b.successors else 0
                for j in b.successors:
                    tail |= live[j]
                for target in b.calls:
                    if target in summaries:
                        new = summaries[target]['returned'] | tail
                        if new != summaries[target]['returned']:
                            summaries[target]['returned'] = new; changed = True
                    elif target == '?indirect':
                        broad_return |= tail
        for name in mapped & fs.keys():
            new = summaries[name]['returned'] | broad_return
            if new != summaries[name]['returned']:
                summaries[name]['returned'] = new; changed = True
        indirect_demand |= broad_return
    return summaries, dict(return_iterations=iterations, indirect_return_demand=indirect_demand)


def source_sets(generated, binary):
    init = (generated/'pinyon_shift_init.cpp').read_text()
    mappings = {int(a,16): n for a,n in re.findall(r'\{ 0x([\dA-Fa-f]+), (\w+) \}', init)}
    syms = symbols(binary)
    replaced = {n for n in syms if not n.startswith('__imp__') and
                '__imp__'+n in syms and syms[n][0] != syms['__imp__'+n][0]}
    fs = {}; digest = hashlib.sha256(); sites = Counter()
    for p in sorted(generated.glob('*_recomp.*.cpp')):
        raw = p.read_bytes(); digest.update(raw)
        for m in re.finditer(r'^DEFINE_REX_FUNC\((\w+)\) \{\n(.*?)^\}', raw.decode(), re.M|re.S):
            if not m[1].startswith('ref_'):
                f = parse(m[2], mappings, sites)
                for b in f.blocks:
                    b.calls = [canonical(c, replaced) for c in b.calls]
                if m[1] in replaced:
                    fs['__imp__'+m[1]] = f
                    fs[m[1]] = s.Function([s.Block(read=s.ALL, write=s.ALL)],0,0,0,0)
                else:
                    fs[m[1]] = f
    contracts = native_contracts()
    for name,c in contracts.items():
        if name not in fs:
            fs[name] = s.Function([s.Block(read=c['reads'],write=c['writes'],kill=c['must'])],0,0,0,0)
    callbacks = {'__imp__'+name for name in replaced if '__imp__'+name in fs}
    summaries, meta = solve(fs, set(mappings.values()), callbacks)
    publications = []
    for name, f in fs.items():
        for i,b in enumerate(f.blocks):
            for c in b.calls:
                if c not in summaries:
                    publications.append(dict(function=name, block=i, target=c, **UNKNOWN))
            if b.read == s.ALL and b.write == s.ALL:
                publications.append(dict(function=name, block=i, target='opaque', **UNKNOWN))
    return dict(generated_sha256=digest.hexdigest(), functions=summaries, sites=sites,
                opaque_publications=publications, mappings=len(mappings),
                native_overrides=sorted(replaced & fs.keys()), native_callbacks=sorted(callbacks),
                native_contracts=contracts, **meta)


class Model(strict.Model):
    publication_census = True

    def __init__(self, binary, sets, layout, replaced):
        super().__init__(binary, sets, layout)
        self.fields += [(layout[n],w,n) for n,w in [('xer',3),('reserved',8),('msr',4),
                          ('vscr_sat',1),('last_indirect_target',4),('dispatch_address',4)]]
        self.functions = {pos:(size, canonical(n, replaced)) for n,(pos,size) in symbols(binary).items()
                          if canonical(n, replaced) in sets}
        self.starts = sorted(self.functions)
        self.import_names = plt_imports(binary)

    def target(self, ins, aliases):
        if (aliases.get('x0') == frozenset({('ctx',0)}) and ins.mnemonic in ('bl','b') and
            ins.operands[0].type == arm64.ARM64_OP_IMM):
            name = self.import_names.get(ins.operands[0].imm)
            if name in self.sets: return self.sets[name]
            fn = self.function(ins.operands[0].imm)
            if fn: return self.sets[fn[2]]
        return super().target(ins, aliases)

    def tail_reads(self, ins, aliases):
        return self.target(ins, aliases)['reads']

    def return_mask(self, pos):
        return self.sets[self.functions[pos][1]]['returned']


def census(a):
    traffic = strict.module('ctx_traffic', 'ctx-traffic-ceiling.py')
    ranges, threads = traffic.contexts(a.runtime)
    source = json.loads(a.sets.read_text())
    model = Model(a.binaries/'libmain.so', source['functions'], json.loads(a.layout.read_text()),
                  set(source['native_overrides']))
    uncharged = Model(a.binaries/'libmain.so', source['functions'], json.loads(a.layout.read_text()),
                      set(source['native_overrides']))
    uncharged.charge_conditional_writes = False
    decoder = traffic.Decoder([a.binaries], ranges, 'arm64')
    rows = defaultdict(Counter)
    for tid, _, name, offset, _, regs in traffic.android_samples(a.android_dump):
        if threads.get(tid) not in ('825A6320','8255AE10','82FB4AF8'): continue
        row = rows[tid]; row['samples'] += 1
        kind = decoder.classify(name, offset, regs); row[kind] += 1
        if kind == 'ctx' and Path(name).name == 'libmain.so':
            load = decoder.last_instruction.mnemonic.startswith('ld')
            if model.deleted(offset, decoder.last_offset, load):
                row['deleted_load' if load else 'deleted_store'] += 1
            elif not load and uncharged.deleted(offset, decoder.last_offset, load):
                row['newly_required_publication'] += 1
    hot = [rows[t] for t in (5168,5178,5189)]
    net = sum(r['deleted_load']+r['deleted_store'] for r in hot)/sum(r['samples'] for r in hot)
    return dict(method=__doc__, generated_sha256=source['generated_sha256'],
        libmain_sha256=hashlib.sha256((a.binaries/'libmain.so').read_bytes()).hexdigest(),
        new_store_sites=0,
        gate='PASS' if net >= .15 else 'FAIL', hot_thread_net_fraction=net,
        threads={str(t):dict(r, guest=threads[t], net_fraction=(r['deleted_load']+r['deleted_store'])/r['samples'])
                 for t,r in rows.items()})


def self_test():
    def p(body): return parse(body, {0x1000:'leaf'}, Counter())
    fs = {'leaf':p('ctx.r6.u64 = 2;\nreturn;'),
          'caller':p('leaf(ctx, base);\nctx.r3.u64 = ctx.r6.u64;\nreturn;')}
    sets, _ = solve(fs, set())
    assert sets['leaf']['returned'] == s.BITS['r6']
    assert sets['leaf']['must'] == s.BITS['r6']
    assert effects('ctx.r6.u32 = 1;')[2] == 0
    assert effects('ctx.r6.u32 = 1;')[0] & s.BITS['r6']
    assert effects('ctx.cr6 = local;') == (0,s.BITS['cr6'],s.BITS['cr6'])
    assert effects('ctx.fpscr.csr = 1;') == (0,s.BITS['fpscr'],s.BITS['fpscr'])
    assert effects('ctx.r6.u64 += 1;') == (s.BITS['r6'],s.BITS['r6'],s.BITS['r6'])
    assert effects('simde_mm_store_ps(ctx.v1.f32, simde_mm_load_ps(ctx.v2.f32));') == (s.BITS['v2'],s.BITS['v1'],s.BITS['v1'])
    assert p('ctr.u32 = 0x1000;\nREX_CALL_INDIRECT_FUNC(ctr.u32);').blocks[0].calls == ['leaf']
    assert p('REX_CALL_INDIRECT_FUNC(ctr.u32);').blocks[0].calls == ['?indirect']
    assert p('opaque(ctx, base);').blocks[0].calls == ['opaque']
    assert liveness(p('opaque(ctx, base);\nreturn;'), {}, 0)[0] == s.ALL
    assert p('unknown::opaque(ctx.r3);').blocks[0].read == s.ALL
    assert native_contracts()['rexcrt_memcpy']['writes'] == 0
    assert native_contracts()['RtlTryEnterCriticalSection']['must'] == s.BITS['r3']
    sets,_ = solve({'original':p('return;')},set(),{'original'})
    assert sets['original']['returned'] == s.ALL
    f = p('switch (ctx.r3.u32) {\ncase 0:\n goto loc_A;\ncase 1:\n goto loc_B;\n}\nreturn;\nloc_A:\nctx.r6.u64 = 1;\nreturn;\nloc_B:\nreturn;')
    assert f.blocks[0].read == s.BITS['r3']
    assert not strict.must_summary(f, {}) & s.BITS['r6']
    # The value in r6 is needed by a local use, but need not be published for
    # that use. A callee that conditionally writes it does need backing state.
    model = Model.__new__(Model)
    model.data = bytes.fromhex('f30300aa681a00f9e00313aa03000094681a40f9c0035fd6c0035fd6')
    model.decoder = cs.Cs(cs.CS_ARCH_ARM64, cs.CS_MODE_ARM); model.decoder.detail = True
    model.functions = {0:(24,'caller'),24:(4,'leaf')}; model.starts = [0,24]
    model.import_names = {}
    model.fields = [(48,8,'r6')]
    model.sets = {'caller':dict(UNKNOWN, returned=0),
                  'leaf':dict(reads=0,writes=s.BITS['r6'],must=0,returned=s.BITS['r6'])}
    assert not model.deleted(4,48,False)
    model.charge_conditional_writes = False; model.demand.cache_clear()
    assert model.deleted(4,48,False)
    model.instructions.cache_clear(); model.demand.cache_clear()


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--generated', type=Path)
    p.add_argument('--binary', type=Path)
    p.add_argument('--sets', type=Path)
    p.add_argument('--android-dump', type=Path)
    p.add_argument('--runtime', type=Path)
    p.add_argument('--binaries', type=Path)
    p.add_argument('--layout', type=Path)
    p.add_argument('--output', type=Path)
    p.add_argument('--self-test', action='store_true')
    a = p.parse_args(); self_test()
    if a.self_test:
        print('caller demand and publication contract self-tests passed')
    else:
        if not a.output: p.error('--output required')
        if a.generated:
            if not a.binary: p.error('--binary required with --generated')
            result = source_sets(a.generated, a.binary)
        else:
            if not all((a.sets,a.android_dump,a.runtime,a.binaries,a.layout)): p.error('matched profile inputs required')
            result = census(a)
        a.output.write_text(json.dumps(result, separators=(',', ':'))+'\n')
        print(json.dumps({k:v for k,v in result.items() if k not in ('functions','opaque_publications')}))
