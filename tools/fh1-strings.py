"""List the keys and text of a Forza Horizon string table (.str).

Mods rename UI text with the mod API's set_ui_string(table, key, text), which
needs the table's name and the 16-bit key of the string. This prints them:

  fh1-strings.py --archive <game>/media/StringTables/EN.zip --table PauseMenu.str
  fh1-strings.py --file PauseMenu.str --grep resume

A table starts with a 24-byte LSB2 header whose u32 at +12 is the offset of
the string chunk: [u32 size][u32 count], count + 1 entries of (u16 key, u32
character offset into the pool) in key order closed by a 0xFFFF sentinel,
then the pool of NUL-terminated UTF-16BE strings. Archive entries are
LZX-compressed and need the native extractor
(pinyon_shift_fh1_archive_extract, built with the host).

The u32 at +16 counts the chunk pointers that follow at +20 (four, 0 for an
absent chunk): 0 the strings, 1 the IDS_ names, 2 translator notes for some
keys (optional), 3 the key order. A key is string_key() of its IDS_ name;
chunk 1 holds count entries of (u16 key, u16 1-based position in chunk 3,
u32 byte offset into its pool of NUL-terminated ASCII names), in key order;
chunk 3 holds count u16 keys in the order of the name pool. The database
refers to a string as "_&N", N = (table_hash(table) << 16) | key. Chunks
are contiguous and unaligned.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import struct
import sys
from pathlib import Path
from zipfile import ZipFile

MAGIC = b"LSB2"
SENTINEL = 0xFFFF
DEFAULT_EXTRACTOR = Path("out/build/win-amd64-release/pinyon_shift_fh1_archive_extract.exe")


class StringTableError(ValueError):
    pass


def parse(data: bytes) -> list[tuple[int, str]]:
    if len(data) < 24 or data[:4] != MAGIC:
        raise StringTableError("not an LSB2 string table")
    chunk = struct.unpack_from(">I", data, 12)[0]
    if chunk + 8 > len(data):
        raise StringTableError("string chunk is outside the file")
    size, count = struct.unpack_from(">II", data, chunk)
    end = chunk + size
    entries_at = chunk + 8
    pool = entries_at + 6 * (count + 1)
    if end > len(data) or pool > end:
        raise StringTableError("string chunk is truncated")
    entries = [struct.unpack_from(">HI", data, entries_at + 6 * i) for i in range(count + 1)]
    if entries[-1][0] != SENTINEL:
        raise StringTableError("missing the 0xFFFF sentinel")
    strings = []
    for key, offset in entries[:-1]:
        at = pool + 2 * offset
        characters = []
        while True:
            if at + 2 > end:
                raise StringTableError(f"string 0x{key:04X} runs past the chunk")
            code = struct.unpack_from(">H", data, at)[0]
            if code == 0:
                break
            characters.append(code)
            at += 2
        text = struct.pack(f">{len(characters)}H", *characters).decode("utf-16-be", "replace")
        strings.append((key, text))
    return strings


def read_archive_entry(archive: Path, table: str, extractor: Path) -> bytes:
    spec = importlib.util.spec_from_file_location(
        "inspect_fh1_ui", Path(__file__).with_name("inspect-fh1-ui.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    assert spec.loader
    spec.loader.exec_module(module)
    with ZipFile(archive) as zipped:
        matches = [info for info in zipped.infolist() if info.filename.lower() == table.lower()]
    if not matches:
        raise StringTableError(f"{table} is not in {archive}")
    return module.extract_entry(archive, matches[0], extractor, "auto", 1 << 24)

def merge_preserving_base(stock: bytes, owned: bytes) -> bytes:
    """Use the owned lookup dictionaries and added strings, retaining base text."""
    original, replacement = dict(parse(stock)), dict(parse(owned))
    if not original.keys() <= replacement.keys():
        raise StringTableError("owned table omits base string keys")
    changed = {key: text for key, text in original.items() if text and replacement[key] != text}
    if not changed:
        return owned
    offset = struct.unpack_from(">I", owned, 12)[0]
    size, count = struct.unpack_from(">II", owned, offset)
    chunk = bytearray(owned[offset:offset + size])
    pool = 8 + 6 * (count + 1)
    for index in range(count):
        key = struct.unpack_from(">H", chunk, 8 + 6 * index)[0]
        if key in changed:
            struct.pack_into(">I", chunk, 10 + 6 * index, (len(chunk) - pool) // 2)
            chunk.extend(changed[key].encode("utf-16-be") + b"\0\0")
    struct.pack_into(">I", chunk, 10 + 6 * count, (len(chunk) - pool) // 2 - 1)
    struct.pack_into(">I", chunk, 0, len(chunk))
    header = bytearray(owned[:offset])
    pointers = struct.unpack_from(">I", header, 16)[0]
    if pointers > 16 or 20 + pointers * 4 > offset:
        raise StringTableError("invalid LSB2 chunk directory")
    for index in range(pointers):
        at = 20 + index * 4
        value = struct.unpack_from(">I", header, at)[0]
        if value >= offset + size:
            struct.pack_into(">I", header, at, value + len(chunk) - size)
    result = bytes(header) + bytes(chunk) + owned[offset + size:]
    if any(text and dict(parse(result))[key] != text for key, text in original.items()):
        raise StringTableError("base strings changed during Rally merge")
    return result


def replace_strings(data: bytes, replacements: dict[int, str]) -> bytes:
    """Replace or add text while retaining the table header and lookup chunks."""
    entries = dict(parse(data)) | replacements
    if any(not 0 <= key < SENTINEL or "\0" in text for key, text in entries.items()):
        raise StringTableError("invalid replacement string")
    offset = struct.unpack_from(">I", data, 12)[0]
    old_size = struct.unpack_from(">I", data, offset)[0]
    pool, records = bytearray(), bytearray()
    for key, text in sorted(entries.items()):
        records.extend(struct.pack(">HI", key, len(pool) // 2))
        pool.extend(text.encode("utf-16-be") + b"\0\0")
    # The sentinel points at an empty string after the last one, as the
    # game's own tables do.
    records.extend(struct.pack(">HI", SENTINEL, len(pool) // 2))
    pool.extend(bytes(2))
    chunk = struct.pack(">II", 8 + len(records) + len(pool), len(entries)) + records + pool
    header = bytearray(data[:offset])
    pointers = struct.unpack_from(">I", header, 16)[0]
    if pointers > 16 or 20 + pointers * 4 > offset:
        raise StringTableError("invalid LSB2 chunk directory")
    for index in range(pointers):
        at = 20 + index * 4
        value = struct.unpack_from(">I", header, at)[0]
        if value >= offset + old_size:
            struct.pack_into(">I", header, at, value + len(chunk) - old_size)
    return bytes(header) + chunk + data[offset + old_size:]


def string_key(name: str) -> int:
    """The 16-bit key of an IDS_ name; table_hash() uses it on table names."""
    value = 0xFFFF
    for character in name.lower().encode("ascii"):
        value ^= character
        value = ((value << 7) | (value >> 9)) & 0xFFFF
    return value


def table_hash(table: str) -> int:
    """The high half of a "_&N" reference: string_key of the table's name."""
    return string_key(table.rsplit("/", 1)[-1].removesuffix(".str").removesuffix(".STR"))


def string_reference(table: str, name: str) -> str:
    """The database's "_&N" text for an IDS_ name in a table."""
    return f"_&{(table_hash(table) << 16) | string_key(name)}"


def _chunks(data: bytes) -> list[int]:
    if len(data) < 24 or data[:4] != MAGIC:
        raise StringTableError("not an LSB2 string table")
    count = struct.unpack_from(">I", data, 16)[0]
    if count != 4 or 20 + 4 * count > struct.unpack_from(">I", data, 12)[0]:
        raise StringTableError("unexpected LSB2 chunk directory")
    return [struct.unpack_from(">I", data, 20 + 4 * index)[0] for index in range(count)]


def parse_names(data: bytes) -> list[tuple[int, str]]:
    """(key, IDS_ name) in the order of the name pool (the key-order chunk)."""
    pointers = _chunks(data)
    at = pointers[1]
    size, count = struct.unpack_from(">II", data, at)
    pool = at + 8 + 8 * count
    names = []
    for index in range(count):
        key, _, offset = struct.unpack_from(">HHI", data, at + 8 + 8 * index)
        end = data.index(b"\0", pool + offset, at + size)
        names.append((offset, key, data[pool + offset:end].decode("ascii")))
    return [(key, name) for _, key, name in sorted(names)]


def add_strings(data: bytes, strings: dict[str, str]) -> bytes:
    """Add IDS_ names with their text to every part of a table.

    A name the table already has only replaces its text. A new name whose
    key another name already holds is refused: the game finds strings by key.
    """
    names = parse_names(data)
    by_key = {key: name for key, name in names}
    texts = {}
    added = []
    for name, text in strings.items():
        if not name.isascii() or not name or "\0" in name:
            raise StringTableError(f"invalid string name {name!r}")
        key = string_key(name)
        if key in by_key and by_key[key].lower() != name.lower():
            raise StringTableError(f"{name} has key 0x{key:04X}, which {by_key[key]} holds")
        if key not in by_key:
            by_key[key] = name
            added.append((key, name))
        texts[key] = text
    result = replace_strings(data, texts)
    if not added:
        return result
    pointers = _chunks(result)
    if not pointers[3]:
        raise StringTableError("the table has no key-order chunk to add names to")
    order = names + added
    pool = bytearray()
    offsets = {}
    for key, name in order:
        offsets[key] = len(pool)
        pool.extend(name.encode("ascii") + b"\0")
    position = {key: index + 1 for index, (key, _) in enumerate(order)}
    records = b"".join(struct.pack(">HHI", key, position[key], offsets[key])
                       for key in sorted(offsets))
    names_chunk = struct.pack(">II", 8 + len(records) + len(pool), len(order)) + records + pool
    order_chunk = struct.pack(f">II{len(order)}H", 8 + 2 * len(order), len(order),
                              *(key for key, _ in order))
    sizes = {index: struct.unpack_from(">I", result, at)[0]
             for index, at in enumerate(pointers) if at}
    blocks = {index: result[at:at + sizes[index]] for index, at in enumerate(pointers) if at}
    blocks[1] = names_chunk
    blocks[3] = order_chunk
    header = bytearray(result[:struct.unpack_from(">I", result, 12)[0]])
    body = bytearray()
    for index, at in sorted(enumerate(pointers), key=lambda item: item[1]):
        if not at:
            continue
        struct.pack_into(">I", header, 20 + 4 * index, len(header) + len(body))
        body.extend(blocks[index])
    return bytes(header) + bytes(body)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--file", type=Path, help="an extracted .str file")
    source.add_argument("--archive", type=Path, help="a StringTables/<language>.zip")
    parser.add_argument("--table", help="the table inside --archive, such as PauseMenu.str")
    parser.add_argument("--extractor", type=Path, default=DEFAULT_EXTRACTOR)
    parser.add_argument("--grep", help="only strings containing this text (any case)")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.file:
            data = args.file.read_bytes()
        else:
            if not args.table:
                parser.error("--archive needs --table")
            data = read_archive_entry(args.archive, args.table, args.extractor)
        strings = parse(data)
    except (OSError, StringTableError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    if args.grep:
        strings = [(key, text) for key, text in strings if args.grep.lower() in text.lower()]
    if args.json:
        print(json.dumps([{"key": f"0x{key:04X}", "text": text} for key, text in strings],
                         ensure_ascii=False, indent=2))
    else:
        for key, text in strings:
            print(f"0x{key:04X}  {text}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
