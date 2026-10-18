---
title: Address index
section: Reverse engineering
order: 16
description: Every guest address named on the reverse-engineering pages in one sorted table, plus the mod symbol table, the hook sites and the structure offsets, with their title update v4 values.
---

# Address index

Every address the reverse-engineering records name, sorted, with a link to the record that explains it. The same data is in [`symbols.json`](https://arcanite24.github.io/pinyon-shift/symbols.json) for scripts and disassemblers; the [Overview](reverse-engineering.md#using-the-records-in-a-disassembler) has a Ghidra script that loads it.

Addresses are in the base game's `default.xex`. The v4 column gives the title update's address where it is known: for records, as the record states it; for mod symbols and hooks, from the generated v4 symbol table, which only holds addresses the mapping tool verified ([Methods](game-methods.md#mapping-base-addresses-to-the-title-update)).

## All addresses

{{addresses:all}}

## Mod symbols

The names a mod passes to `find_symbol`, from `config/mod/fh1-symbols.toml`. Each is a function start or a hook site in the generated code, checked by `tools/tests/test_fh1_symbols.py`. The register notes say what the function takes and returns.

{{addresses:symbols}}

## Hook sites

Every mid-assembly hook in `config/rexglue/analysis/main-xex.toml`, which a mod can also find as `hook.<name>`. The description is the comment above the hook in that file, where there is one.

{{addresses:hooks}}

## Structure offsets

Byte offsets inside game objects, as mods read them with `find_offset`. The v4 values come from `config/mod/fh1-offsets-v4.toml`, each verified on aligned base and v4 instructions; only `profile.loaded` moved.

{{addresses:offsets}}
