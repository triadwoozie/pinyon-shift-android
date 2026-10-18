#!/usr/bin/env python3
"""Back up and restore FH1 profiles around the one-way v4 title-update switch.

A profile saved by the v4 title update cannot be loaded by the base-disc
build: v4 writes a nonzero version in ForzaProfile/VersionFlags after its
'cmss' tag, and the base build then stays on PRESS START. Before the first v4
load, `backup` copies every player profile (user/<xuid>, never the shared
Marketplace content in user/0000000000000000) into
<state>/backups/pre-v4/<timestamp>/ with a SHA-256 manifest. `restore` moves
the current profiles into <state>/backups/v4-saves/<timestamp>/ and copies
the newest pre-v4 backup back, verified against its manifest. Nothing is
deleted; files are never edited in place.

  title-update-profile.py status  --state-root STATE
  title-update-profile.py backup  --state-root STATE
  title-update-profile.py restore --state-root STATE
  title-update-profile.py check-base --state-root STATE
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

CONTENT_USER = "0000000000000000"  # shared Marketplace installations
TITLE = "4D5309C9"
DEFAULT_XUID = "B13EBABEBABEBABE"
V4_SAVE = ("This save was made with title update v4 (on Xenia or the v4 build), and the "
           "base build can't load it: the game would ask for an update from Xbox Live. "
           "Import your own title update v4 in the launcher's DLC panel (Import update), "
           "turn on Use v4 and play again.")


def profiles(state: Path) -> list[Path]:
    user = state / "user"
    if not user.is_dir():
        return []
    return sorted(p for p in user.iterdir() if p.is_dir() and p.name != CONTENT_USER and
                  (p / TITLE).is_dir())


def profile_version(profile: Path) -> str:
    """'base', 'v4' or 'none' from the newest VersionFlags in the profile."""
    flags = sorted(profile.glob(f"{TITLE}/*/ForzaProfile/VersionFlags"))
    if not flags:
        return "none"
    data = flags[0].read_bytes()
    if len(data) < 12 or data[:4] != b"cmss":
        return "unknown"
    return "base" if data[4:12] == bytes(8) else "v4"


def digest(path: Path) -> str:
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest().upper()


def manifest_of(root: Path) -> dict[str, str]:
    return {str(p.relative_to(root)).replace("\\", "/"): digest(p) for p in sorted(root.rglob("*")) if p.is_file()}


def stamp() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def backups(state: Path) -> list[Path]:
    root = state / "backups" / "pre-v4"
    return sorted((p for p in root.iterdir() if (p / "manifest.json").is_file()), reverse=True) \
        if root.is_dir() else []


def status(state: Path) -> dict:
    return {"profiles": {p.name: profile_version(p) for p in profiles(state)},
            "pre_v4_backups": [p.name for p in backups(state)]}


def backup(state: Path) -> dict:
    """Copy base-version profiles once, before their first v4 load."""
    base = [p for p in profiles(state) if profile_version(p) == "base"]
    if not base:
        return {"backed_up": None, "reason": "no base-version profile"}
    target = state / "backups" / "pre-v4" / stamp()
    staging = target.with_name(target.name + ".partial")
    staging.mkdir(parents=True)
    try:
        for profile in base:
            shutil.copytree(profile, staging / profile.name)
        manifest = manifest_of(staging)
        (staging / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
        staging.rename(target)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    return {"backed_up": str(target), "profiles": [p.name for p in base], "files": len(manifest)}


def restore(state: Path) -> dict:
    candidates = backups(state)
    if not candidates:
        raise ValueError("no pre-v4 backup to restore")
    source = candidates[0]
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    if {k: v for k, v in manifest_of(source).items() if k != "manifest.json"} != manifest:
        raise ValueError(f"the pre-v4 backup {source.name} no longer matches its manifest")
    names = sorted({key.split("/", 1)[0] for key in manifest})
    aside = state / "backups" / "v4-saves" / stamp()
    aside.mkdir(parents=True)
    for name in names:
        current = state / "user" / name
        if current.exists():
            current.rename(aside / name)
    for name in names:
        shutil.copytree(source / name, state / "user" / name)
    restored = {k: v for k, v in manifest_of(state / "user").items() if k.split("/", 1)[0] in names}
    if restored != manifest:
        raise ValueError("restored profile does not match the backup manifest")
    return {"restored": source.name, "profiles": names, "v4_saves_kept_in": str(aside)}


def _setting(state: Path, name: str) -> str | None:
    config = state / "config" / "pinyon_shift.toml"
    try:
        text = config.read_text(encoding="utf-8-sig")
    except OSError:
        return None
    match = re.search(rf'^{name}[ \t]*=[ \t]*"([^"]*)"', text, re.MULTILINE)
    return match.group(1) if match else None


def base_build_refusal(state: Path) -> str | None:
    """Why the base build can't start the profile it would load, or None.
    A v4 save makes the base game ask for a title update from Xbox Live
    (#555). Mods that play their own profile (XE) don't load it."""
    for mod in filter(None, (_setting(state, "enabled_mods") or "").split(",")):
        manifest = state / "mods" / mod.strip() / "mod.toml"
        if manifest.is_file() and re.search(r"^profile[ \t]*=", manifest.read_text(
                encoding="utf-8", errors="replace"), re.MULTILINE):
            return None
    xuid = (_setting(state, "user_xuid") or DEFAULT_XUID).upper()
    if profile_version(state / "user" / xuid) != "v4":
        return None
    if backups(state):
        return V4_SAVE + (" To go back to the save from before v4 instead, run "
                          "tools/pinyon.py title-update restore.")
    return V4_SAVE


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=("status", "backup", "restore", "check-base"))
    parser.add_argument("--state-root", type=Path, required=True)
    args = parser.parse_args()
    state = args.state_root.resolve()
    if args.action == "check-base":
        refusal = base_build_refusal(state)
        print(json.dumps({"refusal": refusal}))
        return 3 if refusal else 0
    try:
        result = {"status": status, "backup": backup, "restore": restore}[args.action](state)
    except (OSError, ValueError) as error:
        print(json.dumps({"error": str(error)}))
        return 1
    print(json.dumps(result, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
