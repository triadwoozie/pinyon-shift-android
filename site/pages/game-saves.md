---
title: Saves and profile
section: Reverse engineering
order: 10
description: What Forza Horizon writes to a profile, how it encrypts and decrypts its secure files, the self-describing layout of the ForzaProfile body, the values that gate career progress, and why a v4 save cannot go back to the base game.
---

# Saves and profile

A player's progress is a **profile directory** of about a dozen files. `ForzaProfile` holds the player's values and career state; `PlayerDatabase` is a small SQLite database of owned cars, tunes and parts; `VersionFlags`, liveries and thumbnails complete it. The secure files are encrypted with the console's `XeKeysAesCbcUsingKey`, so they are opaque on disk, but the game decrypts each one into a plaintext body in memory before it reads it, and builds a plaintext body before it encrypts one. Both points are hook sites, and both are exposed to mods.

{{diagram:game-save-pipeline|The two save paths. A mod sees the plaintext body on both: before encryption on save, and after decryption on load, before the game parses it.}}

To get plaintext bodies to study, run with save tracing on: the host then writes every body it sees to `<state>/logs/save-snapshots` as `payload-<kind>-<size>-<hash>.bin`, where the kind is `plaintext` on save and `loaded` on load.

```powershell
$env:PINYON_SHIFT_M5_SAVE_TRACE = "1"
.\tools\launch-preview.ps1 -StateRoot <private copy of a state root>
```

`tools/fh1-profile.py` reads and edits the profile body's self-describing section, and re-encodes it byte for byte:

```sh
python tools/fh1-profile.py dump body.bin           # every field as JSON
python tools/fh1-profile.py get body.bin Main/Credits
python tools/fh1-profile.py set body.bin Main/Credits 1000000 --output edited.bin
python tools/fh1-profile.py check body.bin          # decode, encode, compare
```

A mod changes a save while it loads, through `PINYON_HOOK_SAVE_AFTER_DECRYPT`, editing the body in place without changing its size. Editing the outgoing body instead does not last: the running game keeps its own copy of every value and writes it again on the next save ([Modding](modding.md)). Work on copies of a save, never on the only one.

## Save files

{{findings:saves/Save files}}

## Encryption and loading

{{findings:saves/Encryption and loading}}

## Profile values

{{findings:saves/Profile values}}

## Career progress

{{findings:saves/Career progress}}
