---
title: Android
section: Playing
order: 2
description: Build Pinyon Shift for an Android handheld from the launcher and install it over Wi-Fi.
---

# Android

A developer alpha. The Android build is the same recompiled game, cross-compiled for arm64 on your PC from your own disc and installed on your own device. It's never published or shared.

## Requirements

- A Windows PC where the game is already built and plays.
- About 15 GB free on the PC's install drive. The launcher downloads the pinned Android SDK, NDK and JDK 17 when the PC has none, after you accept the Android SDK license. With Android Studio's SDK already installed, it adds the missing packages to that SDK with its own pinned command-line tools.
- An arm64 device with an ARMv8.2 CPU (Cortex-A55, Cortex-A75 or newer; every Snapdragon 845 and later), Android 13 or later and a Vulkan 1.3 driver, about 8 GB free, on the same network as the PC.
- Tuned for Snapdragon 8 Gen 2 or newer (Adreno 740 and up). Supports Snapdragon 8-series (Adreno 650/660/730/740/750/830) and 7-series chips including Snapdragon 7s Gen 3 (Adreno 810).

## Standalone Setup on Device

Pinyon Shift on Android runs standalone without requiring a PC for initial setup:

1. Copy your extracted game folder (holding `default.xex`) to your device's internal storage or an SD card.
2. Open Pinyon Shift. Tap **Choose Extracted Game Folder** to select your folder with Android's system document picker.
3. The app verifies `default.xex` and copies the assets directly into its private storage.
4. Tap **Launch Game**.

Alternatively, you can transfer files over Wi-Fi from the PC launcher or over USB with adb (`python tools/pinyon.py android push-data`).

## Display Scaling and Widescreen (Hor+)

Modern phones and handhelds feature wide aspect ratios (such as 20:9 or 19.5:9 on Galaxy, Xperia, and Odin devices). In **Settings > Display Scaling Mode**:

- **Widescreen (Hor+)** *(Default)*: Widens the in-engine camera horizontal field of view to match your screen's exact aspect ratio without stretching the 3D scene. HUD elements maintain proper circular proportions.
- **Fit (16:9 Letterbox)**: Preserves the original Xbox 360 16:9 aspect ratio with side pillarboxes.
- **Stretch**: Fills the entire display by stretching the 16:9 frame.
- **Crop**: Zooms into the image to fill the screen without distortion.

## Drivers and Performance

The **Drivers** tab in the mobile launcher manages Vulkan drivers before starting the game:

- **Auto**: Recommends the optimal tested driver for your GPU (Mesa Turnip Gen8 V37 for Adreno 7xx and 8xx). Other GPUs use the system driver.
- **System Default**: Uses the vendor's pre-installed Vulkan driver (recommended for Adreno 6xx).
- **Import Driver (.zip)**: Unpacks any adrenotools or custom Turnip driver archive directly on device.
- **GPU Turbo Clocks**: Locks Adreno GPU to peak frequency states during gameplay.
- **No-Preamble Shaders**: Lowers draw call latency in Turnip.

After the game has run once on a device, `python tools/pinyon.py android shader-pack` makes a shader pack for it: it starts the game to the title screen over adb, collects the SPIR-V of every shader the device's pipeline storage names, builds a pack and puts it in the state's shader cache.

## Presets and drivers

**SETTINGS > GRAPHICS > GRAPHICS PRESET** has three choices:

| Preset | MSAA | Sun shadows | Rate |
| --- | --- | --- | ---: |
| ULTRA LOW 30 | Off | Off | 30 |
| SMOOTH 60 | 2x | Off | 60 |
| QUALITY 30 | 4x | On | 30 |

ULTRA LOW 30 is for entry-level phones and also keeps textures at most 256 texels across (**TEXTURE SIZE**), which saves memory a phone shares with the CPU. All three draw the game's three predicated tiles in one pass and redraw the reflection map at a quarter of the game's rate.

On Adreno 7xx and 8xx the bundled Mesa Turnip driver (Gen8 V37) is chosen automatically. It is much faster than the stock driver on the Adreno 740 and takes about a fifth less GPU time on the Adreno 830. Other GPUs use the system driver. **SETTINGS > GRAPHICS > GPU DRIVER** picks the driver for the next start. **IMPORT DRIVER (.ZIP)** adds an adrenotools package, including Qualcomm driver packages; the log's `Vulkan device` line shows which driver version runs.

Where a phone's fastest cores form their own cluster of two or more (the Snapdragon 8 Elite's two 4.32 GHz cores), the GPU command decoder thread is pinned to them. Left to the scheduler it spent whole stretches on the slower cores: in an in-run A/B on the Astra at the 60 fps limit, 25 % of frames took over 17.5 ms and the decoder 16.2 ms of CPU a frame, against 2 % and 10.2 ms pinned, with the same GPU time. Phones with a single fastest core, such as the 8 Gen 2 and 8 Gen 3, are left alone. The `android_thread_cores` setting overrides it (`NAME=CORES` entries separated by semicolons, `off` to disable).

## Measured devices

| Device | SoC and GPU | Driver | SMOOTH 60 |
| --- | --- | --- | --- |
| AYN Odin 2 Portal | Snapdragon 8 Gen 2, Adreno 740 | Turnip Gen8 V37 | About 60 fps |
| AYN Thor (Max) | Snapdragon 8 Gen 2, Adreno 740 | Turnip Gen8 V37 | About 60 fps |
| RedMagic Astra (nubia NP05J) | Snapdragon 8 Elite, Adreno 830 | Qualcomm | 60 fps while cool, falling toward 30 as it heats up |

The Thor and the Astra were measured before SMOOTH 60 moved from no MSAA to 2x.

## Controls

A built-in, Bluetooth or USB controller works as on the PC. On a touch screen, Pinyon Shift provides a unified mobile HUD with an interactive mode pill (`[🎮 DRIVE]` / `[🧭 MENU]`):

- **Menu Mode** *(Default on launch)*: Clean semi-transparent D-Pad cluster, face buttons (A, B, X, Y), bumpers (LB, RB), START and MAP for navigating menus cleanly without touch clutter.
- **Drive Mode**: Ergonomic thumb controls with lower-left subtle steering arrows, right-hand vertical split pedals (brake top, throttle bottom), haptic feedback, flick-to-shift, and experimental tilt gyro.
- **Top HUD**: Live performance pill (FPS, frame time ms, battery temperature °C), instant camera shutter button, and live tuning gear icon.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| The app doesn't find the PC | Different networks, a network that blocks broadcasts, or Windows Firewall. Enter the address the launcher shows and allow the launcher on private networks |
| The app closes at once | The game files are missing. Open it from the app icon, or run `push-data` again |
| RESOLUTION SCALE offers only 1X | Higher scales need more shared memory than a phone has |
| No sound | No output device could be opened. The game runs silently instead of stopping |

The full Android reference, including DLC and title update v4 on Android, is in [docs/ANDROID.md](https://github.com/arcanite24/pinyon-shift/blob/dev/docs/ANDROID.md).
