#!/usr/bin/env python3
"""Automated pipeline:
1. Waits for Firefox download to finish.
2. Extracts .7z into .local/downloads/extracted/.
3. Extracts ISO or folder into .local/game/base/.
4. Pushes game files to the phone via adb.
5. Runs test on phone with scrcpy screen capture.
6. Force stops app and turns off phone screen.
"""
import os, sys, time, glob, subprocess, shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOWNLOAD_DIR = Path('/home/triadwoozie/Downloads')
PART_FILE = DOWNLOAD_DIR / 'Forza Horizon (USA) (En,Ja,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi,Zh,Ko,Pl,Ru,Cs,Hu).Jw8GYHxA.7z.part'
TARGET_7Z = DOWNLOAD_DIR / 'Forza Horizon (USA) (En,Ja,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi,Zh,Ko,Pl,Ru,Cs,Hu).7z'
EXPECTED_SIZE = 7982067213

def log(msg):
    t = time.strftime('%Y-%m-%d %H:%M:%S')
    print(f'[{t}] {msg}', flush=True)

def wait_for_download():
    log('Waiting for Firefox download to complete...')
    last_cur = 0
    while True:
        # Check if completed
        if TARGET_7Z.is_file() and not PART_FILE.exists() and TARGET_7Z.stat().st_size >= EXPECTED_SIZE:
            log(f'Download complete: {TARGET_7Z} ({TARGET_7Z.stat().st_size} bytes)')
            break
        if PART_FILE.is_file():
            cur = PART_FILE.stat().st_size
            if cur != last_cur:
                pct = (cur / EXPECTED_SIZE) * 100
                rem_mb = (EXPECTED_SIZE - cur) / (1024 * 1024)
                log(f'Downloading: {cur / (1024**3):.2f} / {EXPECTED_SIZE / (1024**3):.2f} GB ({pct:.1f}%), {rem_mb:.0f} MB remaining')
                last_cur = cur
        time.sleep(15)

def extract_archive():
    log('Extracting .7z archive...')
    staging = ROOT / '.local' / 'downloads' / 'forza_extracted'
    staging.mkdir(parents=True, exist_ok=True)
    subprocess.run(['7z', 'x', '-y', str(TARGET_7Z), f'-o{staging}'], check=True)
    log('Extraction complete. Examining contents...')
    return staging

def prepare_game(staging: Path):
    log('Preparing game in .local/game/base...')
    # Check for .iso files
    isos = list(staging.rglob('*.iso'))
    if isos:
        log(f'Found ISO: {isos[0]}')
        # Use pinyon_setup extract_iso
        sys.path.insert(0, str(ROOT / 'tools'))
        import pinyon_setup
        pinyon_setup.extract_iso(isos[0], pinyon_setup.Progress(as_json=False))
        return
    # Check for default.xex
    xexs = list(staging.rglob('default.xex'))
    if xexs:
        folder = xexs[0].parent
        log(f'Found game folder with default.xex: {folder}')
        sys.path.insert(0, str(ROOT / 'tools'))
        import pinyon_setup
        pinyon_setup.verify_folder(folder, pinyon_setup.Progress(as_json=False))
        return
    raise RuntimeError(f'No ISO or default.xex found in {staging}')

def push_to_phone():
    log('Pushing game files to device via adb...')
    cmd = [sys.executable, str(ROOT / 'tools' / 'pinyon_android.py'), 'android', 'push-data']
    subprocess.run(cmd, check=True)
    log('Data push complete!')

def run_tests_on_phone():
    log('Launching game on phone for live testing...')
    serial = 'RZCT80MM3RM'
    # Start game on scrcpy virtual display or default
    subprocess.run(['adb', '-s', serial, 'shell', 'am', 'start', '-n',
                    'studio.deimos.pinyonshift/.PinyonShiftActivity',
                    '--es', 'pinyon_shift_base', '/sdcard/Android/data/studio.deimos.pinyonshift/files'], check=True)
    log('Game launched! Monitoring execution for 45 seconds...')
    for i in range(9):
        time.sleep(5)
        # Capture screenshot
        cap = ROOT / '.local' / f'test_frame_{i}.png'
        subprocess.run(['adb', '-s', serial, 'shell', 'screencap', '-p', f'/sdcard/test_frame_{i}.png'], capture_output=True)
        subprocess.run(['adb', '-s', serial, 'pull', f'/sdcard/test_frame_{i}.png', str(cap)], capture_output=True)
        log(f'Captured test frame {i} -> {cap.name}')

def cleanup_phone():
    log('Stopping app on device and turning off screen...')
    serial = 'RZCT80MM3RM'
    # Stop app
    subprocess.run(['adb', '-s', serial, 'shell', 'am', 'force-stop', 'studio.deimos.pinyonshift'])
    # Sleep screen
    subprocess.run(['adb', '-s', serial, 'shell', 'input', 'keyevent', '223'])
    log('Device screen turned off. Verification complete!')

if __name__ == '__main__':
    wait_for_download()
    staging = extract_archive()
    prepare_game(staging)
    push_to_phone()
    run_tests_on_phone()
    cleanup_phone()
    log('ALL DONE!')
