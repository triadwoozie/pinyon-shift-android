---
title: Overview
section: Reverse engineering
order: 1
description: What Pinyon Shift has learned about Forza Horizon's own code and data by reverse engineering, organised by subsystem, with addresses, evidence and a confidence for every record.
---

# Reverse engineering

These pages record what Pinyon Shift has learned about *Forza Horizon* itself: what its functions do, where its objects keep their fields, how its files are laid out, how its threads share a frame, and what was tried and found not to work. They are written for people who write mods, extend the port, or recompile other Xbox 360 games, especially ones built on the same Forza engine.

Every record says how it was established and how sure we are. Negative results are kept on purpose, so a question that was already answered "no" is not investigated again.

## The game at a glance

{{diagram:game-architecture|Data files drive one simulation thread. It runs the game flow and the game objects, and hands rendering and audio work to other threads as deferred commands. Thread entry addresses are base-executable addresses.}}

| | |
| --- | --- |
| Executable | `default.xex`, one PowerPC image at `0x82000000`, 24.6 MB loaded; v4 adds a `default.xexp` patch (25.8 MB) |
| Functions | 57,127 in the `.pdata` table (60,903 in v4); the recompiler splits them into 76,262 |
| Classes | 6,789 RTTI type names (7,558 in v4); largest namespaces `ForzaUI`, `States`, `GameModes`, `Network` |
| Game flow | 50 XML state-graph files whose 439 behaviours are `States::` classes in the executable |
| Data | `media/`: 7.2 GB in 2,385 files, 524 ZIP archives with 258,936 members, almost all LZX compressed |
| Database | `gamedb.slt`, plain SQLite: 221 tables, 43,949 rows |
| Threads | about 42 in a race: simulation, render, audio, eleven job workers, asset streaming and smaller ones |
| Frame | the simulation steps every second vblank (30 Hz on the console); a race frame is 2,900 to 7,400 draws |

## Pages

| Page | What it covers |
| --- | --- |
| [Methods and tools](game-methods.md) | How findings are made and checked, with the scripts and hook code to repeat them |
| [Executable](game-executable.md) | Image layout, the function table, RTTI, the deferred command pattern, CRT and library code |
| [Threads and frame loop](game-threads.md) | Which thread does what, how the simulation is paced, the present path and the hot functions |
| [Game flow](game-flow.md) | The XML state graphs that run the game's modes, and the code behaviours they call |
| [Game objects](game-objects.md) | The world, session, time of day, vehicles, activities, collectibles and the career profile |
| [Rendering](game-rendering.md) | The passes of a frame, render targets, resolves, shadows, post effects and render scenarios |
| [World and tracks](game-world.md) | Track data, scenery visibility, placements, routes and what controls level of detail |
| [Files and formats](game-files.md) | The media layout, XMem archives, string tables, textures, the database and other formats |
| [Saves and profile](game-saves.md) | Save files, their encryption, the profile and player database |
| [UI, HUD and input](game-ui.md) | Front-end scenes, the pause menu, the HUD, movies and controls |
| [Audio and radio](game-audio.md) | The audio engine, the radio stations and their banks |
| [Cars, events and DLC](game-content.md) | Car data, events, upgrades, liveries and how downloadable content is mounted and licensed |
| [Developer leftovers](game-systems.md) | Launch options, debug menus, network services and systems that shipped unused |
| [Title update v4](game-title-update.md) | What the title update changes and how base addresses are mapped to it |
| [Address index](game-addresses.md) | Every address on these pages in one sorted table, the mod symbols and offsets, and `symbols.json` |

## Reading the records

Each subsystem page ends in a table of records with a filter above it:

- **Address.** The base-game address first, the title update's below it, marked v4. `sub_82D505C0` is the start of a function in the retail disc's `default.xex`. `0x8207AA58` is a data address or a code site inside a function. A bare `825A6320` is a thread's entry point. `guest phys` addresses are in the 512 MB physical heap and may move between sessions. File records give a path instead.
- **Finding.** What it is, then how it was established, where the full reasoning is written down, and the issue it came from.
- **Confidence.** *Confirmed* means shown directly, by a hook, an experiment or reading the data. *Probable* means strong indirect evidence, such as a class name with a matching call pattern. *Guess* means a lead that has not been tested. *Negative* marks a record of something that was found **not** to do what was hoped.

Every record has a link: click its name to get an address like `game-world/#world-pvs-zones` to cite in an issue or commit.

The generator also writes `classes.json` beside the generated code (`.local/generated/default`): every vtable with its class, its offset in the complete object, its base classes in order and its slots, read from the executable's RTTI. In the base image that is 7,636 vtables for 6,389 classes, 7,236 with base classes. It is derived from the game, so it stays local, like the generated code; the findings cite it.

## Using the records in a disassembler

The build writes [`symbols.json`](https://arcanite24.github.io/pinyon-shift/symbols.json) next to these pages: every address on the site with its name, kind, v4 address and the page that explains it, plus the mod structure offsets. It can name functions in a disassembler. This Ghidra script (Jython, run from the Script Manager with the base `default.xex` loaded) labels each one:

```python
# Label Forza Horizon addresses from Pinyon Shift's symbols.json.
import json
from ghidra.program.model.symbol import SourceType

path = askFile("symbols.json", "Load").getAbsolutePath()
with open(path) as f:
    records = json.load(f)["addresses"]
for record in records:
    name = record["name"].replace(" ", "_").replace(".", "_")
    address = toAddr(int(record["address"], 16))
    createLabel(address, name, False, SourceType.USER_DEFINED)
print("labelled %d addresses" % len(records))
```

For the v4 executable, use each record's `v4` field where it is set.

The same names label profiles of the running game: `python tools/re/kb.py symbols --out names.json`, then `tools/summarize-thread-samples.py --symbols names.json` prints `sub_82438EA8 CRT pow` instead of the bare generated name.

## What these pages contain

Only our own analysis: names, addresses, offsets, sizes and counts, and descriptions of behaviour in our own words. No disassembly listings, decompiled code, game data or extracted assets, as for the rest of the project ([Legal](legal.md)). The records come from the project's work since 2026; the engineering detail behind each one is in the repository's [docs/](https://github.com/arcanite24/pinyon-shift/tree/dev/docs), linked from the record.

New findings go in `site/findings/<topic>.toml` in the same change that makes them; [Methods and tools](game-methods.md#recording-a-finding) shows the format.
