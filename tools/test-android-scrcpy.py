#!/usr/bin/env python3
"""Quick testing tool for Pinyon Shift on Android with scrcpy.

Integrates building/installing the APK, launching the game on the device,
mirroring display and audio with scrcpy, and viewing live logs.

Usage:
    python3 tools/test-android-scrcpy.py [options] [-- game arguments...]
    ./tools/test-android-scrcpy.py --help
"""

from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "studio.deimos.pinyonshift"
ACTIVITY = "studio.deimos.pinyonshift.PinyonShiftActivity"
SETUP_ACTIVITY = "studio.deimos.pinyonshift.SetupActivity"


def find_serial(explicit_serial: str | None) -> str:
    if explicit_serial:
        return explicit_serial
    res = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True)
    devices = []
    for line in res.stdout.strip().splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    if not devices:
        print("Error: No adb devices detected. Connect a device or start an emulator.", file=sys.stderr)
        sys.exit(1)
    if len(devices) > 1:
        print(f"Multiple devices detected: {', '.join(devices)}. Using {devices[0]} (use -s/--serial to override).")
    return devices[0]


def run_adb(serial: str, *command: str, **kwargs) -> subprocess.CompletedProcess:
    cmd = ["adb", "-s", serial] + list(command)
    return subprocess.run(cmd, **kwargs)


def get_device_name(serial: str) -> str:
    model = run_adb(serial, "shell", "getprop", "ro.product.model", capture_output=True, text=True).stdout.strip()
    brand = run_adb(serial, "shell", "getprop", "ro.product.brand", capture_output=True, text=True).stdout.strip()
    if model:
        return f"{brand.capitalize()} {model}" if brand else model
    return serial


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Quick test runner for Pinyon Shift Android via scrcpy",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("-s", "--serial", help="Target device serial (auto-detects if omitted)")
    parser.add_argument("-b", "--build", action="store_true", help="Run 'pinyon.py android build' before testing")
    parser.add_argument("-i", "--install", action="store_true", help="Install the APK to the device before running")
    parser.add_argument("-k", "--kill", action="store_true", help="Force stop the app before launching")
    parser.add_argument("--stop-on-exit", action="store_true", help="Force stop the app when scrcpy closes")
    parser.add_argument("--setup", action="store_true", help="Launch SetupActivity instead of main game activity")
    parser.add_argument("--null-gpu", action="store_true", help="Pass --gpu_backend=null to test headless / CPU execution")
    parser.add_argument("--no-scrcpy", action="store_true", help="Skip launching scrcpy window (only run/log)")
    parser.add_argument("--turn-off-screen", "-S", action="store_true", help="Turn device screen off while mirroring with scrcpy")
    parser.add_argument("--fullscreen", "-f", action="store_true", help="Launch scrcpy in fullscreen")
    parser.add_argument("--stay-awake", "-w", action="store_true", default=True, help="Keep device awake while scrcpy runs")
    parser.add_argument("--logcat", "-l", action="store_true", help="Stream logcat filtered for Pinyon Shift")
    parser.add_argument("game_args", nargs=argparse.REMAINDER, help="Additional arguments passed to the game")

    args = parser.parse_args()

    serial = find_serial(args.serial)
    device_name = get_device_name(serial)
    print(f"==> Target device: {device_name} ({serial})")

    pinyon_py = ROOT / "tools" / "pinyon.py"

    if args.build:
        print("==> Building Android package...")
        build_cmd = [sys.executable, str(pinyon_py), "android", "build"]
        res = subprocess.run(build_cmd)
        if res.returncode != 0:
            print(f"Error: Build failed with exit code {res.returncode}", file=sys.stderr)
            return res.returncode

    if args.install:
        print("==> Installing package to device...")
        apk_path = ROOT / ".local" / "android" / "pinyon-shift.apk"
        if not apk_path.is_file():
            print(f"Error: {apk_path} not found. Run with --build first.", file=sys.stderr)
            return 1
        run_adb(serial, "install", "-r", str(apk_path), check=True)

    if args.kill:
        print(f"==> Force stopping {PACKAGE}...")
        run_adb(serial, "shell", "am", "force-stop", PACKAGE)

    target_activity = SETUP_ACTIVITY if args.setup else ACTIVITY

    # Prepare intent extras
    intent_extras: list[str] = []
    game_arguments = list(args.game_args)
    if game_arguments and game_arguments[0] == "--":
        game_arguments.pop(0)

    if args.null_gpu:
        game_arguments.append("--gpu_backend=null")

    if game_arguments:
        intent_extras += ["--esa", "args", ",".join(game_arguments)]

    print(f"==> Launching {target_activity}...")
    run_adb(serial, "shell", "am", "start", "-n", f"{PACKAGE}/{target_activity}", *intent_extras)

    logcat_proc: subprocess.Popen | None = None
    if args.logcat:
        print("==> Starting logcat monitor...")
        # Clear logcat buffer first for cleaner output
        run_adb(serial, "logcat", "-c")
        logcat_cmd = [
            "adb", "-s", serial, "logcat", "-v", "time",
            f"{PACKAGE}:V", "PinyonShift:V", "SDL:V", "DEBUG:I", "*:S"
        ]
        logcat_proc = subprocess.Popen(logcat_cmd)

    scrcpy_proc: subprocess.Popen | None = None
    if not args.no_scrcpy:
        scrcpy_cmd = [
            "scrcpy",
            "-s", serial,
            f"--window-title=Pinyon Shift - {device_name}",
        ]
        if args.stay_awake:
            scrcpy_cmd.append("--stay-awake")
        if args.turn_off_screen:
            scrcpy_cmd.append("--turn-screen-off")
        if args.fullscreen:
            scrcpy_cmd.append("--fullscreen")

        print(f"==> Launching scrcpy: {' '.join(scrcpy_cmd)}")
        scrcpy_proc = subprocess.Popen(scrcpy_cmd)

    def cleanup(signum=None, frame=None):
        if logcat_proc and logcat_proc.poll() is None:
            logcat_proc.terminate()
        if scrcpy_proc and scrcpy_proc.poll() is None:
            scrcpy_proc.terminate()
        if args.stop_on_exit:
            print(f"==> Stopping {PACKAGE} on device...")
            run_adb(serial, "shell", "am", "force-stop", PACKAGE)
        sys.exit(0)

    signal.signal(signal.SIGINT, cleanup)
    signal.signal(signal.SIGTERM, cleanup)

    try:
        if scrcpy_proc:
            scrcpy_proc.wait()
        elif logcat_proc:
            logcat_proc.wait()
        else:
            print("==> App launched. Press Ctrl+C to exit.")
            while True:
                time.sleep(1)
    finally:
        cleanup()

    return 0


if __name__ == "__main__":
    sys.exit(main())
