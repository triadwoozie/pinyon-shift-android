#!/usr/bin/env python3
"""Print the static libraries an XEX2 executable was linked with.

The XEX2 optional header 0x000200FF (`xex2_opt_static_libraries` in the SDK's
rex/system/util/xex2_info.h) lists every XDK library the linker pulled in,
with its version: the library identity NEXT_LEVEL_RESEARCH.md NL-2.4 asks
for. The headers are plaintext even when the image is encrypted, so this
reads the player's own dump as it is.

    python -I tools/re/xex-libraries.py .local/game/base/default.xex
    python -I tools/re/xex-libraries.py --json .local/game/base/*.xex
"""
from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

XEX2_MAGIC = 0x58455832
STATIC_LIBRARIES_KEY = 0x000200FF
LIBRARY_SIZE = 16


def read_static_libraries(data: bytes) -> list[dict]:
    magic, _flags, _header_size, _reserved, _security, count = struct.unpack_from(">IIIIII", data, 0)
    if magic != XEX2_MAGIC:
        raise ValueError("not an XEX2 file")
    for index in range(count):
        key, value = struct.unpack_from(">II", data, 0x18 + 8 * index)
        if key != STATIC_LIBRARIES_KEY:
            continue
        size = struct.unpack_from(">I", data, value)[0]
        libraries = []
        for i in range((size - 4) // LIBRARY_SIZE):
            offset = value + 4 + LIBRARY_SIZE * i
            name = data[offset:offset + 8].rstrip(b"\0").decode("ascii", "replace")
            major, minor, build = struct.unpack_from(">HHH", data, offset + 8)
            approval, qfe = data[offset + 14], data[offset + 15]
            libraries.append({
                "name": name,
                "version": f"{major}.{minor}.{build}.{qfe}",
                "approval": approval,
            })
        return libraries
    return []


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("xex", nargs="+", type=Path)
    parser.add_argument("--json", action="store_true", help="one JSON object per file")
    args = parser.parse_args()
    for path in args.xex:
        libraries = read_static_libraries(path.read_bytes())
        if args.json:
            print(json.dumps({"file": str(path), "libraries": libraries}))
            continue
        print(f"{path} ({len(libraries)} static libraries)")
        for library in libraries:
            print(f"  {library['name']:8s} {library['version']:16s} approval {library['approval']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
