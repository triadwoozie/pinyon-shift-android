---
title: Title update v4
section: Reverse engineering
order: 15
description: What Forza Horizon's title update 4 is and how it applies to the USA disc, how its executable differs from the base game's, how base addresses are mapped to v4 and how far they move, and which structure layouts changed.
---

# Title update v4

Title update 4 adds the Rally expansion's game code and the 1000 Club car challenges. It is a small package: three executable patches and one `media.zip`. The update was built for a different disc release than the supported USA disc, but its patches still apply to the USA disc's modules and produce exactly the v4 images.

{{diagram:game-title-update|How v4 is put together from the disc and the update package. The patched images are byte-identical to v4's own page hashes, so code addresses on these pages are the same however the image was produced.}}

For reverse engineering, v4 is a second, larger executable with the same design. About half of its functions are the base game's functions moved to new addresses; the rest differ, largely because classes grew and their fields and vtable slots moved. Every record on these pages gives the v4 address when it is known, and the mapping between the two is mechanical for most code:

```sh
# Map base addresses to v4. Both inputs are loaded images (pinyon_shift_fh1_archive_extract
# --xex-image and --title-update-image); addresses.json is a list of {"address", "kind"}.
python tools/map-fh1-v4-addresses.py default.base.bin default.v4.bin --addresses addresses.json --output v4.json
```

It tries, in order, an identical normalized function body, a call-graph pairing, a unique instruction window and a data reference from already-matched code, and reports what it cannot map rather than guessing ([Methods and tools](game-methods.md#mapping-base-addresses-to-the-title-update)). The verified pairs the host uses are in `src/fh1_v4_addresses.inc`, and the mod symbol table is generated for both executables, so mods that use names run on either.

## Package and images

{{findings:title-update/Package and images}}

## Address mapping

{{findings:title-update/Address mapping}}

## Layout changes

Struct offsets the host and mods use were rechecked on v4. Most did not move; these did, or were proven not to.

{{findings:title-update/Layout changes}}
