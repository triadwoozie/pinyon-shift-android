#!/usr/bin/env python3
"""Break a running Pinyon Shift process's memory down by region (NEXT_LEVEL_RESEARCH.md NL-0.3).

Windows only. Walks the process's address space with VirtualQueryEx and asks
QueryWorkingSetEx which committed pages are resident, then sums reserved,
committed and resident bytes per class:

  guest <view>      the guest arena's views (4 KB and 64 KB virtual heaps, the
                    XEX views, the physical views at A0/C0/E0 and the raw
                    physical view), split out of the mapped file by the
                    `Guest memory arena mapped` line of the runtime log;
  guest dispatch    the indirect-call dispatch table after the main image, from
                    the log's `Function table initialized` line;
  image <name>      the executable and its DLLs (the GPU driver's among them);
  private           anonymous committed memory: host heaps, driver allocations,
                    thread stacks;
  mapped            other mapped files.

Totals come from GetProcessMemoryInfo. Run it while a route plays, for example
60 s into fh1-race-sustained:

  python tools/measure-process-memory.py --process pinyon_shift.exe \
      --log <state>/logs/runtime.log --json out.json
"""

from __future__ import annotations

import argparse
import ctypes
import ctypes.wintypes as wt
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

PROCESS_QUERY_INFORMATION = 0x0400
PROCESS_VM_READ = 0x0010
MEM_COMMIT, MEM_RESERVE, MEM_FREE = 0x1000, 0x2000, 0x10000
MEM_PRIVATE, MEM_MAPPED, MEM_IMAGE = 0x20000, 0x40000, 0x1000000
TH32CS_SNAPPROCESS, TH32CS_SNAPTHREAD = 0x2, 0x4
PAGE = 0x1000

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
psapi = ctypes.WinDLL("psapi", use_last_error=True)
kernel32.OpenProcess.restype = wt.HANDLE
kernel32.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
kernel32.CloseHandle.argtypes = [wt.HANDLE]
kernel32.CreateToolhelp32Snapshot.restype = wt.HANDLE
kernel32.VirtualQueryEx.restype = ctypes.c_size_t
kernel32.VirtualQueryEx.argtypes = [wt.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t]
psapi.EnumProcessModulesEx.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD), wt.DWORD]
psapi.GetModuleInformation.argtypes = [wt.HANDLE, wt.HMODULE, ctypes.c_void_p, wt.DWORD]
psapi.GetModuleFileNameExW.argtypes = [wt.HANDLE, wt.HMODULE, wt.LPWSTR, wt.DWORD]
psapi.QueryWorkingSetEx.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.DWORD]
psapi.GetProcessMemoryInfo.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.DWORD]


class MEMORY_BASIC_INFORMATION(ctypes.Structure):
    _fields_ = [("BaseAddress", ctypes.c_void_p), ("AllocationBase", ctypes.c_void_p),
                ("AllocationProtect", wt.DWORD), ("PartitionId", wt.WORD),
                ("RegionSize", ctypes.c_size_t), ("State", wt.DWORD), ("Protect", wt.DWORD),
                ("Type", wt.DWORD)]


class PSAPI_WORKING_SET_EX_INFORMATION(ctypes.Structure):
    _fields_ = [("VirtualAddress", ctypes.c_void_p), ("VirtualAttributes", ctypes.c_size_t)]


class PROCESS_MEMORY_COUNTERS_EX(ctypes.Structure):
    _fields_ = [("cb", wt.DWORD), ("PageFaultCount", wt.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                ("PeakPagefileUsage", ctypes.c_size_t), ("PrivateUsage", ctypes.c_size_t)]


class PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [("dwSize", wt.DWORD), ("cntUsage", wt.DWORD), ("th32ProcessID", wt.DWORD),
                ("th32DefaultHeapID", ctypes.c_size_t), ("th32ModuleID", wt.DWORD), ("cntThreads", wt.DWORD),
                ("th32ParentProcessID", wt.DWORD), ("pcPriClassBase", wt.LONG), ("dwFlags", wt.DWORD),
                ("szExeFile", wt.WCHAR * 260)]


class MODULEINFO(ctypes.Structure):
    _fields_ = [("lpBaseOfDll", ctypes.c_void_p), ("SizeOfImage", wt.DWORD), ("EntryPoint", ctypes.c_void_p)]


# The guest views of Memory::map_info (rex/system/xmemory.cpp), by guest address.
GUEST_VIEWS = [
    (0x00000000, 0x40000000, "virtual 4 KB heap"),
    (0x40000000, 0x7F000000, "virtual 64 KB heap"),
    (0x7F000000, 0x80000000, "GPU writeback window"),
    (0x80000000, 0x90000000, "XEX 64 KB view"),
    (0x90000000, 0xA0000000, "XEX 4 KB view"),
    (0xA0000000, 0xC0000000, "physical A0 view"),
    (0xC0000000, 0xE0000000, "physical C0 view"),
    (0xE0000000, 0x100000000, "physical E0 view"),
    (0x100000000, 0x120000000, "physical raw view"),
]


def find_process(name: str) -> tuple[int, int]:
    snapshot = kernel32.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0)
    entry = PROCESSENTRY32W()
    entry.dwSize = ctypes.sizeof(entry)
    found = []
    ok = kernel32.Process32FirstW(snapshot, ctypes.byref(entry))
    while ok:
        if entry.szExeFile.lower() == name.lower():
            found.append((entry.th32ProcessID, entry.cntThreads))
        ok = kernel32.Process32NextW(snapshot, ctypes.byref(entry))
    kernel32.CloseHandle(snapshot)
    if len(found) != 1:
        raise SystemExit(f"{len(found)} processes named {name}; pass --pid")
    return found[0]


def modules(process) -> list[tuple[int, int, str]]:
    count = wt.DWORD()
    handles = (wt.HMODULE * 1024)()
    if not psapi.EnumProcessModulesEx(process, handles, ctypes.sizeof(handles), ctypes.byref(count), 0x3):
        return []
    result = []
    for handle in handles[: count.value // ctypes.sizeof(wt.HMODULE)]:
        info = MODULEINFO()
        psapi.GetModuleInformation(process, handle, ctypes.byref(info), ctypes.sizeof(info))
        name = ctypes.create_unicode_buffer(1024)
        psapi.GetModuleFileNameExW(process, handle, name, 1024)
        result.append((info.lpBaseOfDll or 0, info.SizeOfImage, Path(name.value).name.lower()))
    return result


def resident_bytes(process, base: int, size: int) -> tuple[int, int]:
    """Resident and shared-resident bytes of a committed range, 4 K pages at a time."""
    resident = shared = 0
    pages = size // PAGE
    chunk = 65536
    for start in range(0, pages, chunk):
        count = min(chunk, pages - start)
        infos = (PSAPI_WORKING_SET_EX_INFORMATION * count)()
        for i in range(count):
            infos[i].VirtualAddress = base + (start + i) * PAGE
        if not psapi.QueryWorkingSetEx(process, infos, ctypes.sizeof(infos)):
            raise SystemExit(f"QueryWorkingSetEx failed: {ctypes.get_last_error()}")
        for i in range(count):
            attributes = infos[i].VirtualAttributes
            if attributes & 1:
                resident += PAGE
                if attributes & (1 << 15):
                    shared += PAGE
    return resident, shared


def parse_log(log: Path) -> dict:
    try:
        text = log.read_text(encoding="utf-8", errors="replace")
    except OSError as error:  # the running game holds its log open
        print(f"could not read {log} ({error}); detecting the guest arena instead", file=sys.stderr)
        return {}
    info = {}
    arena = re.search(r"Guest memory arena mapped: virtual base 0x([0-9A-Fa-f]+), physical base 0x([0-9A-Fa-f]+)",
                      text)
    if arena:
        info["virtual_base"] = int(arena.group(1), 16)
    table = re.search(r"Function table initialized for module: code=([0-9A-F]+)-([0-9A-F]+), image=([0-9A-F]+)-([0-9A-F]+)",
                      text)
    if table:
        code_start, code_end, _, image_end = (int(v, 16) for v in table.groups())
        info["dispatch_table"] = (image_end, 2 * (code_end - code_start + 0x10000))
    return info


def classify(base: int, size: int, mbi_type: int, guest: dict, images: list) -> str:
    virtual_base = guest.get("virtual_base")
    if virtual_base is not None and virtual_base <= base < virtual_base + 0x120000000:
        offset = base - virtual_base
        table = guest.get("dispatch_table")
        if table and table[0] <= offset < table[0] + table[1]:
            return "guest dispatch table"
        for start, end, name in GUEST_VIEWS:
            if start <= offset < end:
                return f"guest {name}"
        return "guest other"
    for image_base, image_size, name in images:
        if image_base <= base < image_base + image_size:
            return f"image {name}"
    if mbi_type == MEM_IMAGE:
        return "image other"
    if mbi_type == MEM_MAPPED:
        return "mapped"
    return "private"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--pid", type=int)
    group.add_argument("--process", help="executable name, e.g. pinyon_shift.exe")
    parser.add_argument("--log", type=Path, help="the run's runtime.log, for the guest arena base")
    parser.add_argument("--guest-base", type=lambda v: int(v, 0), help="guest virtual base, if no log")
    parser.add_argument("--dispatch-table", help="BASE:SIZE of the dispatch table in guest addresses, "
                        "if no log (FH1 base image: 0x83620000:0x1C3EB38)")
    parser.add_argument("--json", type=Path, help="write the breakdown here too")
    args = parser.parse_args()

    threads = None
    pid = args.pid
    if args.process:
        pid, threads = find_process(args.process)
    process = kernel32.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, False, pid)
    if not process:
        raise SystemExit(f"OpenProcess({pid}) failed: {ctypes.get_last_error()}")

    guest = parse_log(args.log) if args.log else {}
    if args.guest_base is not None:
        guest["virtual_base"] = args.guest_base
    if args.dispatch_table:
        base, size = args.dispatch_table.split(":")
        guest["dispatch_table"] = (int(base, 0), int(size, 0))
    images = modules(process)

    regions = []
    address = 0
    mbi = MEMORY_BASIC_INFORMATION()
    while address < (1 << 47):
        if not kernel32.VirtualQueryEx(process, ctypes.c_void_p(address), ctypes.byref(mbi), ctypes.sizeof(mbi)):
            break
        base, size = mbi.BaseAddress or 0, mbi.RegionSize
        regions.append((base, size, mbi.State, mbi.Type))
        address = base + size
    if "virtual_base" not in guest:
        # The arena is 4.5 GB of file views starting at a power of two at or
        # above 4 GB (Memory::Initialize tries 2^32, 2^33, ...).
        mapped = [(b, s) for b, s, state, t in regions if t == MEM_MAPPED and state != MEM_FREE]
        for candidate in (1 << n for n in range(32, 48)):
            span = sum(s for b, s in mapped if candidate <= b < candidate + 0x120000000)
            if span >= 0x100000000:
                guest["virtual_base"] = candidate
                break

    totals = defaultdict(lambda: {"reserved": 0, "committed": 0, "resident": 0, "shared_resident": 0})
    for base, size, state, mbi_type in regions:
        if state != MEM_FREE:
            key = classify(base, size, mbi_type, guest, images)
            totals[key]["reserved"] += size
            if state == MEM_COMMIT:
                totals[key]["committed"] += size
                resident, shared = resident_bytes(process, base, size)
                totals[key]["resident"] += resident
                totals[key]["shared_resident"] += shared

    counters = PROCESS_MEMORY_COUNTERS_EX()
    counters.cb = ctypes.sizeof(counters)
    psapi.GetProcessMemoryInfo(process, ctypes.byref(counters), ctypes.sizeof(counters))
    kernel32.CloseHandle(process)

    result = {
        "pid": pid, "threads": threads,
        "working_set_mb": round(counters.WorkingSetSize / 2**20, 1),
        "private_commit_mb": round(counters.PrivateUsage / 2**20, 1),
        "regions": {key: {k: round(v / 2**20, 1) for k, v in value.items()}
                    for key, value in sorted(totals.items(), key=lambda kv: -kv[1]["resident"])},
    }
    print(f"pid {pid}, threads {threads}, working set {result['working_set_mb']} MB, "
          f"private commit {result['private_commit_mb']} MB")
    print(f"{'region':34} {'reserved':>10} {'committed':>10} {'resident':>10} {'shared':>10}  (MB)")
    for key, value in result["regions"].items():
        print(f"{key:34} {value['reserved']:>10} {value['committed']:>10} {value['resident']:>10} "
              f"{value['shared_resident']:>10}")
    if args.json:
        args.json.write_text(json.dumps(result, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    if sys.platform != "win32":
        raise SystemExit("Windows only")
    sys.exit(main())
