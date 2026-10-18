---
title: UI, HUD and input
section: Reverse engineering
order: 11
description: Forza Horizon's Anark Gameface (UI4) interface: the BGF, BSG and FBF scene files, how text is bound, the pause menu's data model and action switch, event hubs, string tables and fonts, movies, and controller, wheel and system UI input.
---

# UI, HUD and input

The front end, the pause menu and the HUD are **Anark Gameface** scenes, called UI4 in the code. Each scene is authored data in `media/UI.zip`: three binary members that describe its items, its scene graph and its native objects, plus Lua scripts for its logic. Text comes from the string tables, bound to named text elements at run time. About 600 `ForzaUI` classes in the executable own the screens and feed them data.

{{diagram:game-ui-scene|What one UI4 scene is made of. The three members must agree: BGF items and BSG nodes refer to object IDs that only the FBF defines, which is why adding a row to a menu means changing all three.}}

For a developer this splits the work cleanly:

- **Changing text** needs no scene edit. A mod replaces a string table entry with `set_ui_string`, and the next load of the table uses it.
- **Changing what a menu does** is code: the screen class owns a data model and an action switch (the pause menu's are below).
- **Changing a scene's layout** means editing the three members together. Same-length relabels and hiding rows are safe; new rows are experimental.

The tools for each:

```sh
# The keys and text of a string table, to pick a key for set_ui_string
python tools/fh1-strings.py --archive <game>/media/StringTables/EN.zip --table PauseMenu.str --grep resume

# A scene's native objects, checked against its items and scene graph
python tools/fh1-ui-fbf.py 925_PAUSE_MENU.fbf --bgf 925_PAUSE_MENU.bgf --bsg 925_PAUSE_MENU.bsg

# A catalogue of the UI archives, fonts and textures, written under .local
python tools/inspect-fh1-ui.py --game-root <game> --output .local/ui-catalog
```

```c
/* In rex_mod_create, so the first load of the table already uses it.
   KEY is the 16-bit key fh1-strings.py printed for the entry. */
api->set_ui_string("PauseMenu.str", KEY, "RESUME DRIVING");
```

## UI system

{{findings:ui/UI system}}

## Scene files

The scene format was worked out from the readers named below and checked against the retail pause scene with `tools/fh1-ui-fbf.py` ([Files and formats](game-files.md) covers the archives they come in).

{{findings:ui/Scene files}}

## Pause menu

{{findings:ui/Pause menu}}

## Event hubs and Rally screens

{{findings:ui/Event hubs and Rally screens}}

## HUD and map

{{findings:ui/HUD and map}}

## Strings and fonts

{{findings:ui/Strings and fonts}}

## Movies and camera shots

{{findings:ui/Movies and camera shots}}

## Input and system UI

{{findings:ui/Input and system UI}}
