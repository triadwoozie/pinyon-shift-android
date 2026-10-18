import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "fh1-strings.py"
SPEC = importlib.util.spec_from_file_location("fh1_strings", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def table(strings):
    pool = b""
    entries = b""
    for key, text in strings:
        entries += struct.pack(">HI", key, len(pool) // 2)
        pool += text.encode("utf-16-be") + b"\x00\x00"
    entries += struct.pack(">HI", 0xFFFF, len(pool) // 2 - 1)
    chunk = struct.pack(">II", 8 + len(entries) + len(pool), len(strings)) + entries + pool
    header = b"LSB2" + struct.pack("<I", 1) + struct.pack(">IIII", 2 << 24, 24, 1, 24)
    return header + chunk


def game_table(named):
    """A table laid out as the game's are: strings, IDS_ names, no notes,
    key order; named is [(IDS_ name, text)] in name-pool order."""
    keyed = sorted((MODULE.string_key(name), text) for name, text in named)
    pool = b""
    entries = b""
    for key, text in keyed:
        entries += struct.pack(">HI", key, len(pool) // 2)
        pool += text.encode("utf-16-be") + b"\x00\x00"
    entries += struct.pack(">HI", 0xFFFF, len(pool) // 2)
    pool += b"\x00\x00"
    strings = struct.pack(">II", 8 + len(entries) + len(pool), len(keyed)) + entries + pool
    name_pool = b""
    offsets = {}
    for name, _ in named:
        offsets[MODULE.string_key(name)] = len(name_pool)
        name_pool += name.encode() + b"\x00"
    position = {MODULE.string_key(name): index + 1 for index, (name, _) in enumerate(named)}
    records = b"".join(struct.pack(">HHI", key, position[key], offsets[key]) for key in sorted(offsets))
    names = struct.pack(">II", 8 + len(records) + len(name_pool), len(named)) + records + name_pool
    order = struct.pack(f">II{len(named)}H", 8 + 2 * len(named), len(named),
                        *(MODULE.string_key(name) for name, _ in named))
    header = b"LSB2" + struct.pack(">III", 1 << 24, 2 << 24, 36) + struct.pack(">I", 4)
    header += struct.pack(">IIII", 36, 36 + len(strings), 0, 36 + len(strings) + len(names))
    return header + strings + names + order


class Fh1StringsTests(unittest.TestCase):
    def test_string_keys_match_the_game(self):
        # Pairs read from the game's tables and the Rally database rows.
        self.assertEqual(MODULE.string_key("IDS_Profile_105"), 0x1477)
        self.assertEqual(MODULE.string_key("IDS_DisplayName_16"), 0x1854)
        self.assertEqual(MODULE.table_hash("List_CarMake.str"), 0x3323)
        self.assertEqual(MODULE.table_hash("Upgrades"), 0x7091)
        self.assertEqual(MODULE.table_hash("Events.str"), 0x0C15)
        self.assertEqual(MODULE.string_reference("Upgrades.str", "IDS_Profile_105"),
                         f"_&{0x70911477}")

    def test_add_strings_round_trips_and_adds_to_every_part(self):
        data = game_table([("IDS_Make_2", "Second"), ("IDS_Make_1", "First")])
        self.assertEqual(MODULE.add_strings(data, {}), data)
        result = MODULE.add_strings(data, {"IDS_Make_New": "Pinyon Motors",
                                           "IDS_Make_1": "Renamed"})
        key = MODULE.string_key("IDS_Make_New")
        self.assertEqual(dict(MODULE.parse(result))[key], "Pinyon Motors")
        self.assertEqual(dict(MODULE.parse(result))[MODULE.string_key("IDS_Make_1")], "Renamed")
        self.assertEqual(MODULE.parse_names(result),
                         [(MODULE.string_key("IDS_Make_2"), "IDS_Make_2"),
                          (MODULE.string_key("IDS_Make_1"), "IDS_Make_1"),
                          (key, "IDS_Make_New")])
        expected = game_table([("IDS_Make_2", "Second"), ("IDS_Make_1", "Renamed"),
                               ("IDS_Make_New", "Pinyon Motors")])
        self.assertEqual(result, expected)

    def test_add_strings_refuses_a_key_another_name_holds(self):
        # Each character is rotated by 7 bits per later character, a full
        # turn every 16: the same change 16 characters apart cancels.
        held = "IDS_" + "a" * 17
        clash = "IDS_b" + "a" * 15 + "b"
        self.assertEqual(MODULE.string_key(held), MODULE.string_key(clash))
        data = game_table([(held, "First")])
        with self.assertRaisesRegex(MODULE.StringTableError, "holds"):
            MODULE.add_strings(data, {clash: "Clash"})


    def test_replace_adds_localized_keys_and_keeps_other_text_and_lookup_tail(self):
        original = table([(1, "Keep"), (2, "Old")])
        chunk = original[24:]
        header = b"LSB2" + struct.pack("<I", 1) + struct.pack(">IIIII", 2 << 24, 28, 2, 28, 28 + len(chunk))
        result = MODULE.replace_strings(header + chunk + b"LOOKUP", {2: "Rally", 3: "Neumáticos"})
        self.assertEqual(MODULE.parse(result), [(1, "Keep"), (2, "Rally"), (3, "Neumáticos")])
        self.assertEqual(result[struct.unpack_from(">I", result, 24)[0]:], b"LOOKUP")

    def test_merge_preserves_base_text_and_owned_lookup_tail(self):
        stock = table([(1, "Original sponsor"), (2, "")])
        data = table([(1, "Changed sponsor"), (2, "Rally name"), (3, "Stage 1")])
        chunk = data[24:]
        header = b"LSB2" + struct.pack("<I", 1) + struct.pack(">IIIII", 2 << 24, 28, 2, 28, 28 + len(chunk))
        owned = header + chunk + b"LOOKUP DICTIONARY"
        merged = MODULE.merge_preserving_base(stock, owned)
        self.assertEqual(MODULE.parse(merged), [(1, "Original sponsor"), (2, "Rally name"), (3, "Stage 1")])
        self.assertEqual(merged[struct.unpack_from(">I", merged, 24)[0]:], b"LOOKUP DICTIONARY")
        self.assertEqual(MODULE.merge_preserving_base(stock, merged), merged)

    def test_merge_rejects_missing_base_keys(self):
        with self.assertRaisesRegex(MODULE.StringTableError, "omits base"):
            MODULE.merge_preserving_base(table([(1, "Original"), (2, "Keep")]), table([(1, "Replacement")]))

    def test_lists_keys_and_text(self):
        data = table([(0x1126, "RESUME"), (0xDD6B, "MULTIPLAYER"), (0xDED7, "PHOTO MODE")])
        self.assertEqual([(0x1126, "RESUME"), (0xDD6B, "MULTIPLAYER"), (0xDED7, "PHOTO MODE")],
                         MODULE.parse(data))

    def test_rejects_other_files(self):
        with self.assertRaises(MODULE.StringTableError):
            MODULE.parse(b"PK\x03\x04" + b"\x00" * 40)
        data = bytearray(table([(0x1126, "RESUME")]))
        data[24 + 8 + 6:24 + 8 + 8] = b"\x00\x01"  # the sentinel key
        with self.assertRaises(MODULE.StringTableError):
            MODULE.parse(bytes(data))

    def test_cli_filters_by_text(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "PauseMenu.str"
            path.write_bytes(table([(0x1126, "RESUME"), (0xDED7, "PHOTO MODE")]))
            self.assertEqual(0, MODULE.main(["--file", str(path), "--grep", "photo"]))


if __name__ == "__main__":
    unittest.main()
