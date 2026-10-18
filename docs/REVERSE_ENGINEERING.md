# Reverse-engineering records

The reverse-engineering records moved to the docs site (#448): the
[Reverse engineering](https://arcanite24.github.io/pinyon-shift/reverse-engineering/)
section explains each part of the game with diagrams and examples, and
lists every finding with its address, method and confidence.

The records themselves are data in [`site/findings/`](../site/findings),
one TOML file per topic, rendered by `site/build.py`. Add a finding there,
in the same change as the work that found it:

```toml
[[finding]]
group = "Time of day"              # a section of the topic's page
base = "sub_825C7DD0"              # base address(es), comma-separated
v4 = "sub_82656D40"                # v4 address(es), when known
name = "time-of-day request"
what = "What it does, in our own words."
how = "How it was established: hook, trace, xref, string, experiment."
confidence = "confirmed"           # confirmed, probable or guess
negative = false                   # true for a result that rules something out
source = "docs/SOME_BACKLOG.md ID" # optional: where the detail lives
```

Symbols and offsets that mod code uses still go in
[`config/mod/fh1-symbols.toml`](../config/mod/fh1-symbols.toml) (v4 offsets
in [`fh1-offsets-v4.toml`](../config/mod/fh1-offsets-v4.toml)); the site's
[address index](https://arcanite24.github.io/pinyon-shift/game-addresses/)
lists them with the records. Only our own analysis goes in a record: no
disassembly listings, decompiled code or game data ([LEGAL.md](LEGAL.md)).

The table this file held until 2026-10-12 is at commit
[`fc1751fe2a38`](https://github.com/arcanite24/pinyon-shift/blob/fc1751fe2a38/docs/REVERSE_ENGINEERING.md);
every row of it is now a record.
