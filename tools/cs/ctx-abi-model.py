"""Optimistic ABI model for ctx-traffic-ceiling.py, not a codegen input.

Unknown calls read only ABI arguments. All calls may fully overwrite volatile
slots, and partial assignments fully kill a slot: both grant extra deletions.
Only data registers participate in transitive live-ins. Other state traffic
is granted as removable except the explicitly preserved return state.
"""
from bisect import bisect_right
from collections import defaultdict, deque
from functools import lru_cache
import importlib.util
from pathlib import Path
import re
import struct
import sys

import capstone as cs
from capstone import arm64

spec=importlib.util.spec_from_file_location('ctx_sets',Path(__file__).with_name('ctx-publish-sets.py'))
s=importlib.util.module_from_spec(spec);sys.modules[spec.name]=s;spec.loader.exec_module(s)
s.FULL=re.compile(r'^ctx\.([A-Za-z_]\w*)(?:\.[\w\[\]]+)*\s*=\s*(?!=)')
s.DIRECT=re.compile(r'\b([A-Za-z_]\w*)\(ctx, base\)|REX_TAIL_CALL\(([A-Za-z_]\w*)\)')
def mask(names): return sum(s.BITS[n] for n in names)
DATA=mask([f'{p}{i}' for p,n in [('r',32),('f',32),('v',128)] for i in range(n)])
ARG=mask([f'r{i}' for i in range(1,11)]+['r13']+[f'{p}{i}' for p in ('f','v') for i in range(1,14)])
PRESERVED=mask(['r1','r2','r13']+[f'r{i}' for i in range(14,32)]+[f'f{i}' for i in range(14,32)]+[f'v{i}' for i in list(range(14,32))+list(range(64,128))]+['cr2','cr3','cr4','fpscr'])
RESULT=mask(['r3','r4']+[f'f{i}' for i in range(1,5)]+[f'v{i}' for i in range(1,4)])
VOL=DATA & ~PRESERVED


def source_sets(generated):
    fs={}
    for p in sorted(generated.glob('*_recomp.*.cpp')):
        for m in re.finditer(r'^DEFINE_REX_FUNC\(([A-Za-z_]\w*)\) \{\n(.*?)^\}',p.read_text(),re.M|re.S):
            f=s.parse(m[2],ARG,VOL,VOL);fs[m[1]]=f
    return solve(fs)


def solve(fs):
    for f in fs.values():
        for b in f.blocks:
            b.read &= DATA
    callers=defaultdict(set)
    for name,f in fs.items():
        for b in f.blocks:
            for c in b.calls:callers[c].add(name)
    # First stabilize may-writes, then start live-ins at bottom with fixed
    # optimistic kills. This avoids a recursive stale live-in cycle.
    q=deque(fs);pending=set(q)
    while q:
        name=q.popleft();pending.remove(name);f=fs[name];writes=0
        for b in f.blocks:
            writes|=b.write
            for c in b.calls:writes|=fs[c].writes if c in fs else VOL
        if f.writes!=writes:
            f.writes=writes
            for c in callers[name]:
                if c not in pending:q.append(c);pending.add(c)
    q=deque(fs);pending=set(q)
    while q:
        name=q.popleft();pending.remove(name);f=fs[name];live=[0]*len(f.blocks)
        changed=True
        while changed:
            changed=False
            for i in range(len(f.blocks)-1,-1,-1):
                b=f.blocks[i];reads=b.read;kills=b.kill
                for c in b.calls:
                    reads |= (fs[c].rbw if c in fs else ARG) & ~b.kill
                    kills |= fs[c].writes if c in fs else VOL
                tail=0
                for j in b.successors:tail|=live[j]
                value=reads|(tail&~kills)
                if value!=live[i]:live[i]=value;changed=True
        if f.rbw!=live[0]:
            f.rbw=live[0]
            for c in callers[name]:
                if c not in pending:q.append(c);pending.add(c)
    return {n:{'reads':f.rbw,'writes':f.writes} for n,f in fs.items()}


class Model:
    def __init__(self,binary,sets,layout):
        self.data=binary.read_bytes();self.sets=sets;self.layout=layout
        self.decoder=cs.Cs(cs.CS_ARCH_ARM64,cs.CS_MODE_ARM);self.decoder.detail=True
        self.fields=[]
        for i in range(32): self.fields.append((layout['r3'] if i==3 else layout[f'r{i}'] if i<3 else layout['r4']+(i-4)*8,8,f'r{i}'))
        for prefix,n,width in [('f',32,8),('v',128,16)]:
            self.fields += [(layout[prefix+'0']+i*width,width,f'{prefix}{i}') for i in range(n)]
        self.fields += [(layout['cr0']+i*4,4,f'cr{i}') for i in range(8)]
        self.fields += [(layout['fpscr'],4,'fpscr'),(layout['lr'],8,'lr'),(layout['ctr'],8,'ctr')]
        phoff=struct.unpack_from('<Q',self.data,32)[0];phsize,phnum=struct.unpack_from('<HH',self.data,54)
        segments=[]
        for i in range(phnum):
            vals=struct.unpack_from('<IIQQQQQQ',self.data,phoff+i*phsize)
            if vals[0]==1:segments.append((vals[3],vals[3]+vals[5],vals[2]))
        def fileoff(va):
            for a,b,o in segments:
                if a<=va<b:return o+va-a
            return None
        shoff=struct.unpack_from('<Q',self.data,40)[0];shsize,shnum=struct.unpack_from('<HH',self.data,58)
        sections=[struct.unpack_from('<IIQQQQIIQQ',self.data,shoff+i*shsize) for i in range(shnum)]
        self.functions={}
        for sec in sections:
            if sec[1]!=2:continue
            strings=sections[sec[6]];st=self.data[strings[4]:strings[4]+strings[5]]
            for off in range(sec[4],sec[4]+sec[5],sec[9]):
                n,info,other,idx,va,size=struct.unpack_from('<IBBHQQ',self.data,off)
                if info&15!=2 or not size:continue
                name=st[n:st.find(b'\0',n)].decode(errors='replace').removeprefix('__imp__')
                if name in sets:
                    pos=fileoff(va)
                    if pos is not None:self.functions[pos]=(size,name)
        self.starts=sorted(self.functions)
        assert self.starts,'no generated function symbols'

    def fields_mask(self,offset,width=8):
        out=0
        for a,n,name in self.fields:
            if offset<a+n and offset+width>a:out|=s.BITS[name]
        return out

    @staticmethod
    def width(ins):
        reg=ins.reg_name(ins.operands[0].reg)
        if ins.mnemonic.endswith('b'):width=1
        elif ins.mnemonic.endswith('h'):width=2
        elif ins.mnemonic.endswith('sw'):width=4
        else:width=16 if reg.startswith('q') else 4 if reg.startswith(('w','s')) else 8
        return width*2 if ins.mnemonic.startswith(('ldp','stp')) else width

    def function(self,offset):
        i=bisect_right(self.starts,offset)-1
        if i<0:return None
        pos=self.starts[i];size,name=self.functions[pos]
        return (pos,size,name) if offset<pos+size else None

    @lru_cache(maxsize=20000)
    def instructions(self,pos):
        size,name=self.functions[pos]
        ins=list(self.decoder.disasm(self.data[pos:pos+size],pos))
        return ins,{x.address:i for i,x in enumerate(ins)}

    def target_reads(self,ins):
        if ins.mnemonic=='bl' and ins.operands[0].type==arm64.ARM64_OP_IMM:
            target=self.function(ins.operands[0].imm)
            if target:return self.sets.get(target[2],{'reads':ARG})['reads']
        return ARG

    def output_writes(self,ins):
        writes=RESULT
        if ins.mnemonic=='bl':
            target=self.function(ins.operands[0].imm)
            if target:
                name=target[2]
                outputs=RESULT|(s.BITS['r6'] if name=='sub_823F2ED8' else 0)
                if name.startswith('__rest'):outputs=s.ALL
                writes=self.sets.get(name,{'writes':VOL})['writes'] & outputs
        return writes

    @lru_cache(maxsize=20000)
    def imports(self,pos):
        """May-cache analysis: a load is retained only if every path lacks it.

        Pointer offsets and available values use union joins. Partial accesses
        grant the whole slot. Thus branch/loop ambiguity grants deletions.
        """
        size,name=self.functions[pos];instructions,indices=self.instructions(pos)
        n=len(instructions)
        successors=[[] for _ in instructions];predecessors=[[] for _ in instructions]
        need=self.sets.get(name,{'reads':0})['reads']|RESULT|PRESERVED
        for i,ins in enumerate(instructions):
            tail=i+1<n
            if ins.mnemonic in ('bl','blr'):
                need|=self.output_writes(ins)
            elif ins.group(cs.CS_GRP_RET):tail=False
            elif ins.group(cs.CS_GRP_JUMP):
                target=next((o.imm for o in reversed(ins.operands) if o.type==arm64.ARM64_OP_IMM),None)
                if target in indices:successors[i].append(indices[target])
                if ins.mnemonic in ('b','br'):tail=False
            if tail:successors[i].append(i+1)
            for j in successors[i]:predecessors[j].append(i)
        # Alias values are sets of context-relative offsets; None means any
        # offset. A stack reload may recover a spilled context pointer.
        entry=({'x0':frozenset({0})},0)
        outgoing=[None]*n;incoming=[None]*n;queue=deque([0]);pending={0}
        def union(states):
            aliases={};known=0
            for regs,bits in states:
                known|=bits
                for r,values in regs.items():
                    v=aliases.get(r,frozenset())|values
                    aliases[r]=frozenset({None}) if None in v or len(v)>32 else v
            return aliases,known
        def norm(reg):
            if reg=='fp':return 'x29'
            if reg=='lr':return 'x30'
            return 'x'+reg[1:] if reg.startswith('w') and reg[1:].isdigit() else reg
        def memory_bits(ins,aliases):
            mem=next((o.mem for o in ins.operands if o.type==arm64.ARM64_OP_MEM),None)
            if mem is None:return 0
            values=aliases.get(norm(ins.reg_name(mem.base)),())
            if not values:return 0
            if None in values or mem.index:return s.ALL
            if ins.mnemonic not in ('ldr','ldur','ldp','ldrb','ldrh','ldrsw','ldrsb','ldrsh','str','stur','stp','strb','strh'):return s.ALL
            width=self.width(ins)
            bits=0
            for offset in values:bits|=self.fields_mask(offset+mem.disp,width)
            return bits
        while queue:
            i=queue.popleft();pending.remove(i)
            states=[outgoing[j] for j in predecessors[i] if outgoing[j] is not None]
            if i==0:states.append(entry)
            if not states:continue
            aliases,known=union(states);incoming[i]=(aliases,known)
            ins=instructions[i];bits=memory_bits(ins,aliases)
            if ins.mnemonic.startswith(('ld','st')):known|=bits
            old=aliases;aliases=dict(old)
            try:
                read,written=ins.regs_access()
                for r in written:aliases.pop(norm(ins.reg_name(r)),None)
            except cs.CsError:
                # Unsupported effects may have cached any slot.
                aliases={};known=s.ALL;read=written=[]
            ops=ins.operands
            if ops and ops[0].type==arm64.ARM64_OP_REG:
                dest=ins.reg_name(ops[0].reg)
                if (dest.startswith('x') and dest[1:].isdigit()) or dest in ('fp','lr'):
                    dest=norm(dest)
                    if ins.mnemonic=='mov' and len(ops)==2 and ops[1].type==arm64.ARM64_OP_REG:
                        values=old.get(norm(ins.reg_name(ops[1].reg)),frozenset())
                        if values:aliases[dest]=values
                    elif ins.mnemonic in ('add','sub') and len(ops)==3 and ops[1].type==arm64.ARM64_OP_REG:
                        values=old.get(norm(ins.reg_name(ops[1].reg)),frozenset())
                        if values:
                            if ops[2].type==arm64.ARM64_OP_IMM and None not in values:
                                delta=ops[2].imm<<ops[2].shift.value
                                aliases[dest]=frozenset(v+delta*(1 if ins.mnemonic=='add' else -1) for v in values)
                            else:aliases[dest]=frozenset({None})
                    elif ins.mnemonic.startswith('ld'):
                        mem=next((o.mem for o in ops if o.type==arm64.ARM64_OP_MEM),None)
                        if mem and ins.reg_name(mem.base)=='sp':
                            for o in ops:
                                if o.type==arm64.ARM64_OP_REG and ins.reg_name(o.reg).startswith('x'):
                                    aliases[norm(ins.reg_name(o.reg))]=frozenset({None})
                    elif any(norm(ins.reg_name(r)) in old for r in read):
                        aliases[dest]=frozenset({None})
            if ins.mnemonic in ('bl','blr'):
                known&=~self.output_writes(ins)
                aliases={r:v for r,v in aliases.items() if r.startswith('x') and r[1:].isdigit() and 19<=int(r[1:])<=29}
            state=(aliases,known)
            if outgoing[i]!=state:
                outgoing[i]=state
                for j in successors[i]:
                    if j not in pending:queue.append(j);pending.add(j)
        return {ins.address:(need & ~incoming[i][1]) for i,ins in enumerate(instructions)
                if incoming[i] is not None and ins.mnemonic.startswith('ld')}

    @lru_cache(maxsize=200000)
    def needed(self,offset,ctxoffset,load):
        fn=self.function(offset)
        if not fn:return False
        pos,size,name=fn;ins,indices=self.instructions(pos)
        if offset not in indices:return False
        i=indices[offset];current=ins[i]
        if load:
            if current.mnemonic not in ('ldr','ldur','ldp','ldrb','ldrh','ldrsw','ldrsb','ldrsh'):return False
            return bool(self.fields_mask(ctxoffset,self.width(current)) & self.imports(pos).get(offset,0))
        if current.mnemonic not in ('str','stur','stp','strb','strh'):return False
        width=self.width(current)
        bits=self.fields_mask(ctxoffset,width)
        if not bits:return False
        # A surviving component still needs one host memory instruction.
        current_mem=next((o.mem for o in current.operands if o.type==arm64.ARM64_OP_MEM),None)
        if not current_mem or current_mem.index:return False
        base=current_mem.base;mutated=set()
        for nxt in ins[i+1:i+129]:
            if nxt.mnemonic in ('bl','blr'):
                return bool(bits & self.target_reads(nxt))
            if nxt.group(cs.CS_GRP_RET):return bool(bits & (RESULT|PRESERVED|(s.BITS['r6'] if name=='sub_823F2ED8' else 0)))
            if nxt.group(cs.CS_GRP_JUMP):return False
            if nxt.mnemonic.startswith('st'):
                mem=next((o.mem for o in nxt.operands if o.type==arm64.ARM64_OP_MEM),None)
                if not mem:return False
                # A stack spill cannot overwrite the heap context. Any other
                # unknown store is granted as a possible overwrite.
                if nxt.reg_name(mem.base)=='sp':pass
                elif mem.base==base and not mem.index and base not in mutated:
                    nextwidth=self.width(nxt)
                    if mem.disp<current_mem.disp+width and current_mem.disp<mem.disp+nextwidth:return False
                else:return False
            try:mutated.update(nxt.regs_access()[1])
            except cs.CsError:return False
        return False


if __name__=='__main__':
    import argparse,json
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--generated',type=Path);p.add_argument('--output',type=Path);p.add_argument('--self-test',action='store_true')
    a=p.parse_args()
    if a.self_test:
        def parse(body):return s.parse(body,ARG,VOL,VOL)
        fs={'sub_00000001':parse('ctx.r3.u64 = ctx.r6.u64;\nreturn;'),
            'sub_00000002':parse('REX_CALL_INDIRECT_FUNC(0);\nctx.r3.u64 = ctx.r11.u64;\nreturn;'),
            'sub_00000003':parse('ctx.r6.u64 = 0;\nsub_00000001(ctx, base);\nreturn;'),
            'sub_00000004':parse('ctx.r6.u8[0] = 0;\nctx.r3.u64 = ctx.r6.u64;\nreturn;'),
            'sub_00000005':parse('ctx.r1.u64 = 0;\nREX_CALL_INDIRECT_FUNC(0);\nreturn;')}
        out=solve(fs)
        assert out['sub_00000001']['reads']==s.BITS['r6']
        assert not out['sub_00000002']['reads']&s.BITS['r11']
        assert not out['sub_00000003']['reads']&s.BITS['r6']
        assert not out['sub_00000004']['reads']&s.BITS['r6']
        assert out['sub_00000005']['reads']==ARG&~s.BITS['r1']
        # Authored synthetic AArch64: mov x19,x0; ldr x8,[x19];
        # ldr x9,[x19]; ret. Only the first r3 load imports a value.
        model=Model.__new__(Model)
        model.data=bytes.fromhex('f30300aa680240f9690240f9c0035fd6')
        model.decoder=cs.Cs(cs.CS_ARCH_ARM64,cs.CS_MODE_ARM);model.decoder.detail=True
        model.functions={0:(len(model.data),'sub_00000001')};model.starts=[0]
        model.sets={'sub_00000001':{'reads':s.BITS['r3'],'writes':0}}
        model.fields=[(0,8,'r3'),(8,8,'r0')]
        imports=model.imports(0)
        assert imports[4]&s.BITS['r3'] and not imports[8]&s.BITS['r3']
        model.data=bytes.fromhex('f30300aa682600a9c0035fd6')
        model.functions={0:(len(model.data),'sub_00000001')}
        model.instructions.cache_clear();model.imports.cache_clear();model.needed.cache_clear()
        assert model.needed(4,0,False)  # stp r3/r0 retains one store for r3
        print('optimistic ABI model self-test passed')
    else:
        if not a.generated or not a.output:p.error('--generated and --output required')
        out=source_sets(a.generated);a.output.write_text(json.dumps(out,separators=(',',':'))+'\n');print(len(out),'optimistic source summaries')
