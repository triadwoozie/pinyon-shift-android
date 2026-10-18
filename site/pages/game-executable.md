---
title: The executable
section: Reverse engineering
order: 3
description: How Forza Horizon's default.xex is laid out: its sections and function table, the kernel and XAM imports, what its RTTI records reveal about the code's classes, the runtime library it was built with, and notes for analysing it.
---

# The executable

`default.xex` is one big-endian PowerPC image for the Xbox 360's Xenon CPU, loaded at `0x82000000`. Everything on these pages is about that image, and every address is a guest address in it. The v4 title update patches the same image into a larger one ([Title update v4](game-title-update.md)); addresses in the second column of a record are in that image.

{{diagram:game-xex-layout|The loaded image in base and v4. Code and data move up in v4 because .text grows; .rdata, which holds the RTTI and most strings, starts at the same address in both.}}

Three facts make the image easier to work with than most console binaries:

- **It has a complete function table.** `.pdata` lists the start and length of 57,127 functions, so function boundaries never have to be guessed. The recompiler starts from it.
- **It keeps run-time type information.** About 6,800 class names with their vtables and base classes survive in `.rdata`. Most names on these pages come from there.
- **Its system calls are named.** Kernel and XAM functions are imported by ordinal, and the SDK's export tables name each ordinal ([shiftglue-sdk](https://github.com/arcanite24/shiftglue-sdk)), so each system call is labelled through its import thunk.

The `.pdata` table can be read from the loaded image (written by `pinyon_shift_fh1_archive_extract --xex-image`) with a few lines of Python. Each entry is two big-endian words: a function's start address, and a packed word whose bits 8 to 29 hold the length in instructions.

```python
import struct

def pdata_functions(image: bytes):
    """(start, size in bytes) for every function in a loaded FH1 image."""
    pe = struct.unpack_from("<I", image, 60)[0]                 # the PE header inside the image
    optional = pe + 24
    rva, size = struct.unpack_from("<II", image, optional + 96 + 8 * 3)  # data directory 3: exceptions
    for start, packed in struct.iter_unpack(">II", image[rva:rva + size]):
        yield start, ((packed >> 8) & 0x3FFFFF) * 4

functions = list(pdata_functions(open("default.image", "rb").read()))
print(len(functions), "functions,", sum(n for _, n in functions), "bytes of code")
```

On the base image this prints 57,127 functions and 13.7 MB of code. The headers inside the image are little-endian, as in any PE file; the table and the code are big-endian.

## Image layout

{{findings:executable/Image layout}}

## Imports and modules

{{findings:executable/Imports and modules}}

## Classes

The class names come from MSVC RTTI records: the word before a vtable's first slot points to a complete-object locator, which points to the class's `.?AV<name>@@` type descriptor and its base-class list. [Methods and tools](game-methods.md#strings-and-rtti) has a script that lists them.

{{findings:executable/Classes}}

## Runtime library

{{findings:executable/Runtime library}}

## Analysis notes

{{findings:executable/Analysis notes}}
