"""Conservative follow-up to the optimistic CS-0.2 ceiling.

Compute transitive read-before-write, may-write and must-write summaries,
then count guaranteed redundant imports and dead publications in a matched
AArch64 binary. Unknown calls read/write everything, never definitely write
anything. Returns expose everything. Unknowns are retained. This is a lower
census for this abstract model, not an exact hypothetical compiler result:
register allocation and replacements are not emitted or measured.
"""
from collections import Counter, defaultdict, deque
from functools import lru_cache
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys

import capstone as cs
from capstone import arm64


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


abi = module('ctx_abi', 'ctx-abi-model.py')
s = abi.s
# Undo the optimistic parser relaxations. A partial write cannot kill a slot.
s.FULL = re.compile(r'^ctx\.((?:r|f)\d+)(?:\.(?:u64|s64|f64))?\s*=\s*(?!=)|'
                    r'^ctx\.(v\d+|lr|ctr|msr|vscr_sat|last_indirect_target|dispatch_address)\s*=\s*(?!=)')


def must_summary(f, summaries):
    n = len(f.blocks)
    incoming, outgoing = [None] * n, [None] * n
    incoming[0] = 0
    queue = deque([0])
    while queue:
        i = queue.popleft()
        b = f.blocks[i]
        value = incoming[i] | b.kill
        for c in b.calls:
            value |= summaries[c]['must'] if c in summaries else 0
        if value == outgoing[i]:
            continue
        outgoing[i] = value
        for j in b.successors:
            new = value if incoming[j] is None else incoming[j] & value
            # Entry has a permanent zero predecessor, including back edges.
            if j == 0:
                new = 0
            if new != incoming[j]:
                incoming[j] = new
                queue.append(j)
    exits = [outgoing[i] for i, b in enumerate(f.blocks)
             if not b.successors and outgoing[i] is not None]
    result = s.ALL if exits else 0
    for value in exits:
        result &= value
    return result


def source_sets(generated):
    fs, digest = {}, hashlib.sha256()
    for p in sorted(generated.glob('*_recomp.*.cpp')):
        raw = p.read_bytes()
        digest.update(raw)
        for m in re.finditer(r'^DEFINE_REX_FUNC\((\w+)\) \{\n(.*?)^\}', raw.decode('utf-8'), re.M | re.S):
            f = s.parse(m[2])
            # A register passed to an opaque helper may be a mutable reference.
            # Without its signature retain all state, including implicit uses.
            if re.search(r'(?<![\w])(?!(?:REX_LOAD|REX_STORE)_)[A-Za-z_]\w*\([^;\n]*ctx\.', m[2]):
                f.blocks[0].read |= s.ALL
                f.blocks[0].write |= s.ALL
            fs[m[1]] = f
    summaries = {name: {'reads': 0, 'writes': 0, 'must': 0} for name in fs}
    callers = defaultdict(set)
    for name, f in fs.items():
        for b in f.blocks:
            for c in b.calls:
                callers[c].add(name)
    def run_queue(step):
        queue, pending = deque(fs), set(fs)
        while queue:
            name = queue.popleft()
            pending.remove(name)
            if step(name):
                for c in callers[name]:
                    if c not in pending:
                        queue.append(c)
                        pending.add(c)
    def must(name):
        value = must_summary(fs[name], summaries)
        old = summaries[name]['must']
        summaries[name]['must'] = value
        return old != value
    run_queue(must)
    def reads_writes(name):
        f = fs[name]
        live = [0] * len(f.blocks)
        writes = 0
        changed = True
        while changed:
            changed = False
            for i in range(len(f.blocks) - 1, -1, -1):
                b = f.blocks[i]
                reads, kill = b.read, b.kill
                writes |= b.write
                for c in b.calls:
                    callee = summaries.get(c, {'reads': s.ALL, 'writes': s.ALL, 'must': 0})
                    reads |= callee['reads'] & ~kill
                    writes |= callee['writes']
                    kill |= callee['must']
                tail = 0
                for j in b.successors:
                    tail |= live[j]
                value = reads | (tail & ~kill)
                if value != live[i]:
                    live[i], changed = value, True
        old = summaries[name]['reads'], summaries[name]['writes']
        summaries[name].update(reads=live[0], writes=writes)
        return old != (live[0], writes)
    run_queue(reads_writes)
    return {'generated_sha256': digest.hexdigest(), 'functions': summaries,
            'indirect_sites': sum(f.indirect for f in fs.values()),
            'opaque_sites': sum(f.opaque for f in fs.values())}


class Model(abi.Model):
    # Historical strict mode observes every local ctx read. The caller-demand
    # census overrides this to distinguish a local SSA use from publication.
    publication_census = False
    charge_conditional_writes = True

    def return_mask(self, pos):
        return s.ALL

    def tail_reads(self, ins, aliases):
        return 0

    @staticmethod
    def norm(reg):
        if reg == 'fp':
            return 'x29'
        if reg == 'lr':
            return 'x30'
        return 'x' + reg[1:] if re.fullmatch(r'w\d+', reg) else reg

    def full_mask(self, offset, width):
        return sum(s.BITS[name] for a, n, name in self.fields
                   if offset <= a and a + n <= offset + width)

    def target(self, ins, aliases):
        if aliases.get('x0') != frozenset({('ctx', 0)}):
            return {'reads': s.ALL, 'writes': s.ALL, 'must': 0}
        if ins.mnemonic == 'bl' and ins.operands[0].type == arm64.ARM64_OP_IMM:
            target = self.function(ins.operands[0].imm)
            if target:
                return self.sets[target[2]]
        return {'reads': s.ALL, 'writes': s.ALL, 'must': 0}

    @lru_cache(maxsize=20000)
    def demand(self, pos):
        insns, indices = self.instructions(pos)
        n = len(insns)
        succ, pred = [[] for _ in insns], [[] for _ in insns]
        for i, ins in enumerate(insns):
            tail = i + 1 < n
            if ins.group(cs.CS_GRP_RET):
                tail = False
            elif ins.group(cs.CS_GRP_JUMP) and ins.mnemonic not in ('bl', 'blr'):
                target = next((o.imm for o in reversed(ins.operands) if o.type == arm64.ARM64_OP_IMM), None)
                if target in indices:
                    succ[i].append(indices[target])
                if ins.mnemonic in ('b', 'br'):
                    tail = False
            if tail:
                succ[i].append(i + 1)
            for j in succ[i]:
                pred[j].append(i)
        # Alias sets are may facts; available context values are must facts.
        # Stack spills do not grant imports: ambiguous stack reloads lose aliases.
        entry = ({'x0': frozenset({('ctx', 0)})}, 0)
        incoming, outgoing = [None] * n, [None] * n
        queue, pending = deque([0]), {0}
        access = [(s.ALL, 0, 0)] * n
        while queue:
            i = queue.popleft()
            pending.remove(i)
            states = [outgoing[j] for j in pred[i] if outgoing[j] is not None]
            if i == 0:
                states.append(entry)
            if not states:
                continue
            aliases, known = {}, s.ALL
            for regs, bits in states:
                known &= bits
                for reg, values in regs.items():
                    values = aliases.get(reg, frozenset()) | values
                    if len(values) > 16 or ('ctx', None) in values:
                        values = frozenset({('ctx', None)})
                    aliases[reg] = values
            incoming[i] = (aliases, known)
            ins = insns[i]
            old, aliases = aliases, dict(aliases)
            reads = kills = touched = 0
            mem = next((o.mem for o in ins.operands if o.type == arm64.ARM64_OP_MEM), None)
            if mem:
                values = old.get(self.norm(ins.reg_name(mem.base)), ())
                width = self.width(ins)
                if values:
                    # Unknown arithmetic and multiple aliases retain the access.
                    if mem.index or len(values) != 1 or next(iter(values))[1] is None:
                        reads = s.ALL
                        if ins.mnemonic.startswith('st'):
                            known = 0
                    else:
                        kind, offset = next(iter(values))
                        if kind == 'ctx':
                            address = offset + mem.disp
                            covered = set()
                            for start, length, _ in self.fields:
                                covered.update(range(max(address, start), min(address + width, start + length)))
                            if len(covered) != width:
                                reads, known = s.ALL, 0
                            else:
                                touched = self.fields_mask(address, width)
                            if ins.mnemonic.startswith('ld'):
                                reads |= touched
                            elif ins.mnemonic.startswith('st'):
                                kills = self.full_mask(offset + mem.disp, width)
                            else:
                                reads = s.ALL
                elif ins.reg_name(mem.base) != 'sp':
                    # A spilled or otherwise untracked context pointer may be
                    # here. Unknown reads observe all slots; unknown writes
                    # invalidate all available values and retain publications.
                    reads = s.ALL
                    if ins.mnemonic.startswith('st'):
                        known = 0
                # Cached values are established by full loads/stores only.
                if touched:
                    known |= self.full_mask(next(iter(values))[1] + mem.disp, width)
            try:
                read_regs, written_regs = ins.regs_access()
                for reg in written_regs:
                    aliases.pop(self.norm(ins.reg_name(reg)), None)
            except cs.CsError:
                aliases, known, reads = {}, 0, s.ALL
                read_regs = []
            ops = ins.operands
            if ops and ops[0].type == arm64.ARM64_OP_REG:
                dst = self.norm(ins.reg_name(ops[0].reg))
                if ins.reg_name(ops[0].reg).startswith('x'):
                    if ins.mnemonic == 'mov' and len(ops) == 2 and ops[1].type == arm64.ARM64_OP_REG:
                        values = old.get(self.norm(ins.reg_name(ops[1].reg)), ())
                        if values:
                            aliases[dst] = frozenset(values)
                    elif ins.mnemonic in ('add', 'sub') and len(ops) == 3:
                        values = old.get(self.norm(ins.reg_name(ops[1].reg)), ())
                        if values:
                            delta = ops[2].imm << ops[2].shift.value if ops[2].type == arm64.ARM64_OP_IMM else None
                            aliases[dst] = frozenset((k, v + delta * (1 if ins.mnemonic == 'add' else -1)
                                                     if v is not None and delta is not None else None) for k, v in values)
                    elif any(self.norm(ins.reg_name(r)) in old for r in read_regs) and not ins.mnemonic.startswith('ld'):
                        aliases[dst] = frozenset({('ctx', None)})
            if ins.mnemonic in ('bl', 'blr'):
                target = self.target(ins, old)
                reads |= target['reads']
                kills |= target['must']
                known &= ~target['writes']
                aliases = {r: v for r, v in aliases.items() if re.fullmatch(r'x(?:19|2\d)', r)}
            escaped_branch = ins.group(cs.CS_GRP_JUMP) and ins.mnemonic not in ('bl', 'blr') and any(
                o.type == arm64.ARM64_OP_IMM and o.imm not in indices for o in ins.operands)
            if not succ[i] or escaped_branch:
                reads |= self.return_mask(pos)
            if escaped_branch:
                reads |= self.tail_reads(ins, old)
            access[i] = reads, kills, touched
            state = aliases, known
            if outgoing[i] != state:
                outgoing[i] = state
                for j in succ[i]:
                    if j not in pending:
                        queue.append(j)
                        pending.add(j)
        live = [0] * n
        changed = True
        while changed:
            changed = False
            for i in range(n - 1, -1, -1):
                tail = 0
                for j in succ[i]:
                    tail |= live[j]
                reads, kills, _ = access[i]
                value = reads | (tail & ~kills)
                if value != live[i]:
                    live[i], changed = value, True
        if self.publication_census:
            logical = live
            access_publication = []
            for i, ins in enumerate(insns):
                reads, kills, touched = access[i]
                # A local use reads the cached value, not the backing context.
                if touched and ins.mnemonic.startswith('ld'):
                    reads &= ~touched
                if self.charge_conditional_writes and ins.mnemonic in ('bl', 'blr') and incoming[i] is not None:
                    target = self.target(ins, incoming[i][0])
                    tail = 0
                    for j in succ[i]: tail |= logical[j]
                    # A may-write that preserves the slot on another path
                    # needs its incoming backing value for a later reimport.
                    reads |= tail & target['writes'] & ~target['must']
                access_publication.append((reads, kills, touched))
            live = [0] * n
            changed = True
            while changed:
                changed = False
                for i in range(n-1, -1, -1):
                    tail = 0
                    for j in succ[i]: tail |= live[j]
                    reads, kills, _ = access_publication[i]
                    value = reads | (tail & ~kills)
                    if value != live[i]: live[i], changed = value, True
        result = {}
        for i, ins in enumerate(insns):
            if incoming[i] is None:
                continue
            reads, kills, touched = access[i]
            if not touched:
                continue
            if ins.mnemonic.startswith('ld'):
                result[ins.address] = ('load', touched & ~incoming[i][1])
            elif ins.mnemonic.startswith('st'):
                tail = 0
                for j in succ[i]:
                    tail |= live[j]
                result[ins.address] = ('store', touched & tail)
        return result

    def deleted(self, offset, ctxoffset, load):
        fn = self.function(offset)
        if not fn:
            return False
        pos, _, _ = fn
        row = self.demand(pos).get(offset)
        return row is not None and row[0] == ('load' if load else 'store') and not row[1]


def self_test():
    fs = {'leaf': s.parse('ctx.r6.u64 = 1;\nreturn;'),
          'branch': s.parse('if (ctx.r3.u64) goto loc_x;\nctx.r6.u64 = 1;\nloc_x:\nreturn;')}
    sums = {k: {'must': 0} for k in fs}
    assert must_summary(fs['leaf'], sums) & s.BITS['r6']
    assert not must_summary(fs['branch'], sums) & s.BITS['r6']
    assert not must_summary(s.parse('ctx.r6.u32 = 0;\nreturn;'), {}) & s.BITS['r6']
    assert must_summary(s.parse('leaf(ctx, base);\nreturn;'), {'leaf': {'must': s.BITS['r6']}}) & s.BITS['r6']
    assert not must_summary(s.parse('unknown(ctx, base);\nreturn;'), {})
    model = Model.__new__(Model)
    model.data = bytes.fromhex('f30300aa680240f9690240f9c0035fd6')
    model.decoder = cs.Cs(cs.CS_ARCH_ARM64, cs.CS_MODE_ARM)
    model.decoder.detail = True
    model.functions = {0: (len(model.data), 'leaf')}
    model.starts = [0]
    model.sets = {'leaf': {'reads': s.BITS['r3'], 'writes': 0, 'must': 0}}
    model.fields = [(0, 8, 'r3'), (8, 8, 'r0')]
    assert not model.deleted(4, 0, True)
    assert model.deleted(8, 0, True)
    # A caller store dies only when the callee definitely replaces it.
    model.data = bytes.fromhex('f30300aa681a00f9e00313aa02000094c0035fd6c0035fd6')
    model.functions = {0: (20, 'caller'), 20: (4, 'leaf')}
    model.starts = [0, 20]
    model.fields = [(48, 8, 'r6')]
    model.sets = {'caller': {'reads': 0, 'writes': s.BITS['r6'], 'must': 0},
                  'leaf': {'reads': 0, 'writes': s.BITS['r6'], 'must': s.BITS['r6']}}
    model.instructions.cache_clear()
    model.demand.cache_clear()
    assert model.deleted(4, 48, False)
    model.sets['leaf'] = {'reads': s.ALL, 'writes': s.ALL, 'must': 0}
    model.demand.cache_clear()
    assert not model.deleted(4, 48, False)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--generated', type=Path)
    p.add_argument('--sets', type=Path)
    p.add_argument('--android-dump', type=Path)
    p.add_argument('--runtime', type=Path)
    p.add_argument('--binaries', type=Path)
    p.add_argument('--layout', type=Path)
    p.add_argument('--output', type=Path)
    p.add_argument('--self-test', action='store_true')
    a = p.parse_args()
    self_test()
    if a.self_test:
        print('strict context census self-test passed')
    elif a.generated:
        if not a.output:
            p.error('--output required with --generated')
        data = source_sets(a.generated)
        a.output.write_text(json.dumps(data, separators=(',', ':')) + '\n', encoding='utf-8')
        print('function summaries', len(data['functions']), 'indirect sites', data['indirect_sites'])
    else:
        if not all((a.sets, a.android_dump, a.runtime, a.binaries, a.layout, a.output)):
            p.error('sets, android-dump, runtime, binaries, layout and output required')
        traffic = module('ctx_traffic', 'ctx-traffic-ceiling.py')
        ranges, threads = traffic.contexts(a.runtime)
        source = json.loads(a.sets.read_text(encoding='utf-8'))
        model = Model(a.binaries / 'libmain.so', source['functions'],
                      json.loads(a.layout.read_text(encoding='utf-8')))
        decoder = traffic.Decoder([a.binaries], ranges, 'arm64')
        rows = defaultdict(Counter)
        for tid, timestamp, name, offset, weight, regs in traffic.android_samples(a.android_dump):
            if threads.get(tid) not in ('825A6320', '8255AE10', '82FB4AF8'):
                continue
            row = rows[tid]
            row['samples'] += 1
            kind = decoder.classify(name, offset, regs)
            row[kind] += 1
            if kind == 'ctx' and Path(name).name == 'libmain.so':
                load = decoder.last_instruction.mnemonic.startswith('ld')
                if model.deleted(offset, decoder.last_offset, load):
                    row['deleted_load' if load else 'deleted_store'] += 1
        result = {'method': 'conservative model census; unknowns retained; no emitted replacement or spill cost',
                  'generated_sha256': source['generated_sha256'],
                  'libmain_sha256': hashlib.sha256((a.binaries / 'libmain.so').read_bytes()).hexdigest(),
                  'threads': {str(t): dict(r, guest=threads[t],
                      deletion_fraction=(r['deleted_load'] + r['deleted_store']) / r['samples']) for t, r in rows.items()}}
        a.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, indent=2))
