#!/usr/bin/env python3
"""The reverse-engineering knowledge base as one address table (NEXT_LEVEL_RESEARCH.md NL-2.1).

The records live in `site/findings/<topic>.toml` (one `[[finding]]` per fact,
with a confidence and its evidence), the mod API's names in
`config/mod/fh1-symbols.toml` and the hook sites in
`config/rexglue/analysis/main-xex.toml`. This tool reads them all and:

  symbols   writes them as one JSON address table, with a `names` map from
            generated function names (`sub_82438EA8`) to the recorded names,
            which tools/summarize-thread-samples.py --symbols uses to label
            profiles;
  check     verifies that every address a record writes as `sub_XXXXXXXX` is
            the start of a generated function, so a record cannot name a
            function the code does not have (base and, when the v4 tree
            exists, v4);
  lint      refuses records whose text contains what docs/LEGAL.md keeps out of
            the repository: runs of disassembled instructions or hex dumps.

`check` and `lint` exit 1 with one line per problem; tools/tests/test_re_kb.py
runs both.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FINDINGS = ROOT / "site" / "findings"
SYMBOLS = ROOT / "config" / "mod" / "fh1-symbols.toml"
V4_SYMBOLS = ROOT / "src" / "mod" / "fh1_symbols_v4.inc"
HOOKS = ROOT / "config" / "rexglue" / "analysis" / "main-xex.toml"
V4_HOOKS = ROOT / "config" / "rexglue" / "analysis-v4" / "main-xex.toml"
GENERATED = ROOT / ".local" / "generated" / "default"
GENERATED_V4 = ROOT / ".local" / "generated-v4" / "default"

# The same patterns the site build uses: sub_82D505C0 is a function start,
# 0x8207AA58 a data address or code site, a bare 825A6320 a thread entry.
GUEST_ADDRESS = re.compile(r"\b(?:sub_|0x)?(8[23][0-9A-Fa-f]{6})\b")
FUNCTION_ADDRESS = re.compile(r"\bsub_(8[23][0-9A-Fa-f]{6})\b")
THREAD_ADDRESS = re.compile(r"(?<![\w_x])(8[23][0-9A-Fa-f]{6})\b")
GENERATED_FUNCTION = re.compile(r"\{ 0x([0-9A-Fa-f]{8}), sub_([0-9A-Fa-f]{8}) \}")

# docs/LEGAL.md: analysis only, no disassembly listings or game data. Three or
# more instructions with register operands in one record (a listing always
# names a register; naming one instruction in prose does not), or a hex dump,
# fail the lint.
INSTRUCTION = re.compile(
    r"\b(?:lwz|lwzx|lwzu|stw|stwu|stwx|lis|addi|addis|ori|oris|mr|mflr|mtlr|mtctr|cmplwi|cmpwi|"
    r"cmplw|cmpw|rlwinm|lbz|lbzx|stb|lhz|sth|lfs|lfd|stfs|stfd|fmuls|fmadds|fnmsubs|fadds|lvx|"
    r"stvx|vmaddfp|lwarx|stwcx\.?|srawi|subf|add|neg|andi\.|and|or|xor)\b[ \t]+(?:[rfv]\d{1,3}|cr\d)\b",
    re.IGNORECASE)
HEX_DUMP = re.compile(r"(?:\b[0-9A-Fa-f]{2}\b[ ,:]*){24,}")


def load_findings() -> list[dict]:
    """Every record, with its topic and the file position for messages."""
    records = []
    for path in sorted(FINDINGS.glob("*.toml")):
        data = tomllib.loads(path.read_text(encoding="utf-8"))
        for number, raw in enumerate(data.get("finding", []), 1):
            record = dict(raw)
            record["topic"] = path.stem
            record["where"] = f"{path.relative_to(ROOT).as_posix()} finding {number}"
            records.append(record)
    return records


def load_symbols() -> list[dict]:
    """Mod symbols and hook sites: name, base address, v4 address when known."""
    symbols = []
    table = tomllib.loads(SYMBOLS.read_text(encoding="utf-8"))
    for name, address in table.get("symbols", {}).items():
        symbols.append({"name": name, "base": int(address), "kind": "mod symbol"})
    for path, kind in ((HOOKS, "hook"), (V4_HOOKS, "hook v4")):
        if not path.is_file():
            continue
        for match in re.finditer(r"\[\[midasm_hook\]\]\s*\naddress = (0x[0-9A-Fa-f]+)\s*\nname = \"(\w+)\"",
                                 path.read_text(encoding="utf-8")):
            symbols.append({"name": f"hook.{match.group(2)}", "base": int(match.group(1), 16), "kind": kind})
    if V4_SYMBOLS.is_file():
        v4 = {m.group(1): int(m.group(2), 0) for m in
              re.finditer(r'PINYON_SYMBOL\("([^"]+)", (0x[0-9A-Fa-f]+|\d+)\)',
                          V4_SYMBOLS.read_text(encoding="utf-8"))}
        for symbol in symbols:
            if symbol["kind"] == "mod symbol" and symbol["name"] in v4:
                symbol["v4"] = v4[symbol["name"]]
    return symbols


def address_table(records: list[dict], symbols: list[dict]) -> dict:
    """Every address the records name, and a names map for generated functions."""
    rows = []
    names: dict[str, list[str]] = {}

    def remember(address: int, name: str) -> None:
        names.setdefault(f"sub_{address:08X}", [])
        if name not in names[f"sub_{address:08X}"]:
            names[f"sub_{address:08X}"].append(name)

    for record in records:
        base = record.get("base", "")
        for match in GUEST_ADDRESS.finditer(base):
            address = int(match.group(1), 16)
            rows.append({"address": f"0x{address:08X}", "name": record["name"], "kind": record["topic"],
                         "confidence": record.get("confidence", ""), "v4": record.get("v4", ""),
                         "group": record.get("group", "")})
        for match in FUNCTION_ADDRESS.finditer(base):
            remember(int(match.group(1), 16), record["name"])
        # Thread entries are function starts too; the sampler shows them as
        # sub_<entry> in the stacks.
        for match in THREAD_ADDRESS.finditer(base):
            if record.get("topic") == "threads":
                remember(int(match.group(1), 16), record["name"])
    for symbol in symbols:
        if symbol["kind"] == "hook v4":
            continue
        rows.append({"address": f"0x{symbol['base']:08X}", "name": symbol["name"], "kind": symbol["kind"],
                     "confidence": "confirmed",
                     "v4": f"{symbol['v4']:08X}" if symbol.get("v4") else "", "group": ""})
        if symbol["kind"] == "mod symbol":
            remember(symbol["base"], symbol["name"])
    rows.sort(key=lambda r: (r["address"], r["kind"], r["name"]))
    return {"executables": {"base": "default.xex", "v4": "default.xex+default.xexp"},
            "addresses": rows,
            "names": {function: "; ".join(labels) for function, labels in sorted(names.items())}}


def generated_functions(generated: Path) -> set[int] | None:
    """Function starts of a generated tree: the init table's identity entries."""
    init = generated / "pinyon_shift_init.cpp"
    if not init.is_file():
        return None
    starts = set()
    for match in GENERATED_FUNCTION.finditer(init.read_text(encoding="utf-8", errors="replace")):
        if match.group(1).upper() == match.group(2).upper():
            starts.add(int(match.group(1), 16))
    return starts


def check(records: list[dict], symbols: list[dict], generated: Path, generated_v4: Path) -> list[str]:
    problems = []
    base_functions = generated_functions(generated)
    v4_functions = generated_functions(generated_v4)
    if base_functions is None:
        return [f"{generated} has no generated tree to check against"]
    for record in records:
        for field, functions in (("base", base_functions), ("v4", v4_functions)):
            if functions is None:
                continue
            # Only `sub_X` claims a function start; a bare address may be a
            # thread entry, a code site inside a function or a data address.
            # A v4 field may pair addresses as `base -> v4`.
            text = record.get(field, "")
            if field == "v4":
                text = ", ".join(part.split("->", 1)[1] if "->" in part else part
                                 for part in text.split(","))
            claimed = {int(m.group(1), 16) for m in FUNCTION_ADDRESS.finditer(text)}
            for address in sorted(claimed - functions):
                problems.append(f"{record['where']} ({record['name']}): {field} {address:08X} is not a "
                                "generated function start")
    for symbol in symbols:
        functions = v4_functions if symbol["kind"] == "hook v4" else base_functions
        if symbol["kind"] == "mod symbol" or functions is None:
            continue  # hook sites are instruction addresses inside functions
    return problems


def lint(records: list[dict]) -> list[str]:
    problems = []
    for record in records:
        for field in ("what", "how", "note"):
            text = record.get(field, "")
            if not text:
                continue
            instructions = INSTRUCTION.findall(text)
            if len(instructions) >= 3:
                problems.append(f"{record['where']} ({record['name']}): {field} reads like a disassembly "
                                f"listing ({len(instructions)} instructions with operands)")
            if HEX_DUMP.search(text):
                problems.append(f"{record['where']} ({record['name']}): {field} contains a hex dump")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    symbols_parser = sub.add_parser("symbols", help="write the address table as JSON")
    symbols_parser.add_argument("--out", type=Path, help="default: stdout")
    check_parser = sub.add_parser("check", help="every recorded function is a generated function start")
    check_parser.add_argument("--generated", type=Path, default=GENERATED)
    check_parser.add_argument("--generated-v4", type=Path, default=GENERATED_V4)
    sub.add_parser("lint", help="no disassembly or hex dumps in the records")
    args = parser.parse_args()

    records = load_findings()
    symbols = load_symbols()
    if args.command == "symbols":
        text = json.dumps(address_table(records, symbols), indent=1)
        if args.out:
            args.out.write_text(text + "\n", encoding="utf-8")
        else:
            print(text)
        return 0
    problems = check(records, symbols, args.generated, args.generated_v4) if args.command == "check" \
        else lint(records)
    for problem in problems:
        print(problem, file=sys.stderr)
    if not problems:
        print(f"{args.command}: {len(records)} records, {len(symbols)} symbols and hook sites, no problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
