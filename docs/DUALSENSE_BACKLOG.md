# DualSense backlog

Status: **DS-0 to DS-7 built and measured on a USB DualSense on Windows
(2026-10-13); Bluetooth and the other platforms of DS-8 still open**, tracked in
[#561](https://github.com/arcanite24/pinyon-shift/issues/561); from a read-only audit of the tree
(`dev` at `18fc79e`, vendored SDL `release-3.4.0-428-g8bf3b7215`) and from
the public protocol references listed at the end. The research sections
below are the plan as written before any code; [Results](#results) records
what the pad and the game did. This is the detailed plan for FB-3.2, FB-3.3 and
FB-3.5 of the [feature backlog](FEATURE_BACKLOG.md#fb-3-controller-extras-s-m)
and for NP-6.3 of the [native port backlog](NATIVE_PORT_BACKLOG.md), with the
row conventions of the other backlogs (`DS-n`, sizes S/M/L, a gate per row).
`sdk/` stands for `thirdparty/shiftglue-sdk/`. Claims the tree cannot show
are marked **guess** or **probable**; each has a row that checks it before
anything depends on it.

## Goal

A player holding a Sony DualSense (or DualSense Edge) gets what the pad can
do and the Xbox 360 game could not ask for:

1. **Adaptive triggers**: the brake stiffens, pulses under ABS and lock-up;
   the throttle buzzes with wheelspin and clicks on gear changes.
2. **Rumble as today**, through the pad's voice coils, with no regression.
3. **Lightbar and player LEDs** that mean something (redline, menus) and can
   be turned off.
4. **PlayStation glyphs** in the host UI (F6 menu, help bar, mapping
   assistant). The game's own HUD keeps its Xbox art until NP-6.2 replaces
   textures.
5. **Gyro steering** as an option.
6. Works over **USB and Bluetooth** on Windows, Linux and macOS; over USB-C on
   Android (Bluetooth on Android gives rumble only, see below).
7. **No stuck state**: triggers go slack and LEDs are released when the game
   exits, loses focus or crashes.

Everything is off or neutral by default until DS-8 has verified it on
hardware, as the repository rule for hardware features demands
(`FEATURE_BACKLOG.md`, "Hardware features need hardware").

## What exists today

The input layer is in the SDK; this repository adds only settings pages and
the touch pad. The relevant facts (see the file references for detail):

- **SDL3 with HIDAPI on** is already the gamepad path on every platform
  (`sdk/src/input/sdl/sdl_input_driver.cpp`; `SDL_HIDAPI` is SDL's default,
  `SDL_HIDAPI_LIBUSB OFF` on Windows in the top-level `CMakeLists.txt:18-20`).
  So the DualSense already runs through SDL's native PS5 driver
  (`sdl3/src/joystick/hidapi/SDL_hidapi_ps5.c`), not through DirectInput.
  Rumble already works that way; nothing else is used.
- **The guest exposes two motor values and nothing else.** `XamInputSetState`
  (`sdk/src/kernel/xam/xam_input.cpp:119-133`) carries `X_INPUT_VIBRATION`
  with `left_motor_speed` and `right_motor_speed`. `InputSystem::SetState`
  scales them by `pad_rumble_strength` and `SDLInputDriver::SetDeviceVibration`
  calls `SDL_RumbleGamepad(low = left, high = right, 0xFFFF ms)`. There is no
  trigger motor, no curve and no per-device handling.
- **The game's own force-feedback path is known but not traced at run time**:
  `sub_830EB930` / `sub_8310CBC8` with a wheel branch tuned by
  `media/ControllerFFB.ini` (inertia, friction, damper, spring, spring against
  speed, rear slip), recorded on the site under "rumble and force-feedback
  output" (`site/findings/ui.toml`, FB-2). The telemetry HUD class
  (`CTelemetryHud`) is recorded as the lead to RPM, gear, slip and suspension
  values (`site/findings/systems.toml`, "telemetry pages"). Neither has been
  followed to the car's physics fields; that is DS-3.
- **The player's car is reachable**: `sub_8248BAE8(world)` returns the
  player's car slot (`site/findings/objects.toml`, "player car and AI
  control"), with several fields already mapped (`+0x3B70`, `+0x3B78`,
  `+11728`, `+15216`; v4 moves fields after `0x3B00` by 16 bytes).
- **Controller type is logged, not stored.** `OnControllerDeviceAddedLocked`
  (`sdl_input_driver.cpp:467-507`) logs `SDL_GetGamepadType`, vendor and
  product and discards them. `DeviceInfo` (`sdk/include/rex/input/device.h`)
  has no type field. `ActiveDeviceTracker` (`sdk/include/rex/input/state_merge.h:30-44`)
  already exists "so button glyphs follow the pad in the player's hands".
- **The event watch passes gamepad touchpad and sensor events** (they are
  `0x650`-`0x65A`, below the `SDL_EVENT_FINGER_DOWN` cut at
  `sdl_input_driver.cpp:81`), but `ProcessEventLocked` has no case for them.
- **`SDL_SENSOR OFF`** (`sdk/thirdparty/CMakeLists.txt:272`) removes SDL's
  device-sensor subsystem only; gamepad gyro and accelerometer are reported by
  the gamepad layer (`SDL_EVENT_GAMEPAD_SENSOR_UPDATE`) and are not gated by
  it (**probable**, from reading `SDL_events.c`; DS-6 confirms).
- **No `SDL_HINT_JOYSTICK_*` hint is set anywhere.** The PS5 driver's
  defaults apply: HIDAPI PS5 on, player LEDs on, enhanced reports `auto`.
- **Host UI glyphs are the disc's Xbox textures** (`Controller/A_Button.xds`,
  `Controller/B_Button.xds` from `Horizon.zip`, `src/ui/hostui/host_ui.cpp:181-182,1004-1005`);
  the mapping assistant's prompts are Xbox names
  (`src/ui/settings_menu.cpp:1040-1066`).
- **Settings**: F6 > CONTROLS has RUMBLE (0-100 %), remap, chords, wheel
  shaping and key binds (`settings_menu.cpp:1635-1729`). No deadzone, curve,
  trigger or glyph settings.

## Research results

### How to drive the pad: SDL, not a vendor SDK

Sony's DualSense PC SDK is partner-only. Microsoft's GameInput 3.5 ships a
header-only `DualSenseHelper.h` that builds the same output reports, but it is
Windows-only, USB-only and needs GameInput v3; it adds nothing over SDL for
us. The Linux kernel driver (`hid-playstation`) exposes rumble, lightbar,
LEDs, touchpad and motion through evdev but **not** the adaptive triggers;
tools that need them (dualsensectl, pydualsense) write raw HID reports, as
SDL's HIDAPI driver does.

**Decision: use the vendored SDL3.** Everything needed is already compiled
and declared in the SDK's `SDL_gamepad.h`:

| Need | SDL call | Notes |
| --- | --- | --- |
| Adaptive triggers | `SDL_SendGamepadEffect(pad, data, size)` | Raw effect payload, see below. SDL adds the report ID, the Bluetooth framing (`0x31`, tag `0x00`, magic `0x10`) and the CRC32 seeded with `0xA2`. Thread-safe. |
| Rumble | `SDL_RumbleGamepad` | As today. SDL uses the pad's "improved rumble" on firmware >= 2.24 and the Edge, else the compatibility path (values halved "to match Xbox"). |
| Lightbar | `SDL_SetGamepadLED(pad, r, g, b)` | Until set, SDL colours it by player index (blue, red, green, pink, orange, teal, white). |
| Player LEDs | `SDL_SetGamepadPlayerIndex` (already called) | Hint `SDL_HINT_JOYSTICK_HIDAPI_PS5_PLAYER_LED` turns them off. |
| Type and labels | `SDL_GetGamepadType` (`SDL_GAMEPAD_TYPE_PS5`), `SDL_GetGamepadButtonLabel` (cross, circle, square, triangle) | Third-party pads may report PS5 too; the label API covers face buttons only, so we keep our own glyph set per type. |
| Touchpad | `SDL_GetGamepadTouchpadFinger`, touchpad events | One pad, two fingers, 1920x1070 raw units. |
| Gyro, accel | `SDL_SetGamepadSensorEnabled`, `SDL_EVENT_GAMEPAD_SENSOR_UPDATE` | 250 Hz over USB, 1000 Hz over Bluetooth and on the Edge; radians/s and m/s^2, calibrated by SDL. |
| Trigger rumble (Xbox One) | `SDL_RumbleGamepadTriggers` | Returns unsupported on the DualSense; used for Xbox pads in the same code path (FB-3.3). |

### Effect payload (what `SDL_SendGamepadEffect` takes)

SDL copies up to 47 bytes after the report ID; the layout is the PS5 driver's
`DS5EffectsState_t` (offsets from the start of the payload):

| Offset | Field | Used for |
| --- | --- | --- |
| 0 | `ucEnableBits1` | `0x01` compatible rumble, `0x02` disable audio haptics (SDL sets both when it rumbles), **`0x04` right trigger effect, `0x08` left trigger effect** |
| 1 | `ucEnableBits2` | `0x01` mic LED, `0x04` lightbar colour, `0x08` release LEDs, `0x10` player LEDs |
| 2-3 | right, left motor | leave to SDL |
| 4-9 | audio, mic LED, mute bits | not used |
| 10 | `rgucRightTriggerEffect[11]` | throttle (R2) |
| 21 | `rgucLeftTriggerEffect[11]` | brake (L2) |
| 32-37 | unknown | 0 |
| 38 | `ucEnableBits3` | `0x04` improved rumble (SDL's) |
| 41-46 | LED animation, brightness, pad lights, R, G, B | leave to SDL |

The layout agrees byte for byte with the kernel's
`dualsense_output_report_common` (47 bytes; `valid_flag2` at 38 is SDL's
`ucEnableBits3`, and its `COMPATIBLE_VIBRATION2 0x04` is SDL's "improved
rumble"). The kernel never drives the triggers and leaves bits 2 and 3 of
`valid_flag0` undefined; the right `0x04` / left `0x08` trigger enables come
from Microsoft's MIT-licensed `DualSenseHelper.h`, which also places the
trigger blocks at the same offsets (`0x0B` and `0x16` after the report ID).
The LED flags (`0x04` lightbar, `0x10` player LEDs, `0x08` release) are the
kernel's `DS_OUTPUT_VALID_FLAG1_*`. SDL never writes the trigger bytes
itself. **Rule for our payload: set only `0x04`/`0x08` in byte 0 and nothing
else**, so SDL's own rumble and LED reports and ours never overwrite each
other's fields. (A report with the rumble flag set and zero motors would stop
the rumble SDL just sent; a report without the trigger flags leaves the
triggers as they are, which is why SDL's rumble reports do not cancel ours.)

Each 11-byte trigger block is mode byte + parameters. The modes, from John
Klein's MIT-licensed `TriggerEffectGenerator` (the reference every PC tool
uses) and dualsensectl:

| Mode | Byte 0 | Parameters | Feel | Status |
| --- | --- | --- | --- | --- |
| Off | `0x05` | none | slack | official |
| Feedback | `0x21` | start zone 0-9, strength 1-8 (b1-b2 zone mask, b3-b6 3-bit strengths per zone) | constant resistance past the start | official |
| Weapon | `0x25` | start 2-7, end start+1..8, strength 1-8 | resistance between start and end, then a release click | official |
| Vibration | `0x26` | start 0-9, amplitude 1-8, frequency Hz (b9) | buzz past the start | official |
| Multi-position Feedback / Vibration | `0x21` / `0x26` | ten per-zone values | arbitrary resistance or amplitude profile over the travel; a linear ramp ("slope") is the useful case | official layout, less tested |
| Bow | `0x22` | start, end, strength, snap force | weapon with a snap back | reverse-engineered |
| Galloping | `0x23` | start, end, two foot positions, frequency | two-step rhythm | reverse-engineered |
| Machine | `0x27` | start, end, amplitude A/B 0-7, frequency, period (0.1 s) | oscillation between two amplitudes; the usual "ABS" | reverse-engineered |
| Simple Feedback / Weapon / Vibration | `0x01` / `0x02` / `0x06` | position + strength; start + end + strength; frequency + amplitude + position (one byte each) | the same three effects without zones | the modes Microsoft's `DualSenseHelper.h` uses; Klein's generator calls them leftovers |
| Limited `0x11 0x12`, debug `0xFC-0xFE` | | | | do not use |

We will use Off, Feedback, Weapon, Vibration and Machine in their zoned
forms (`0x21 0x25 0x26 0x27`), since the ABS band and the brake slope need
per-zone values, and keep Bow for the gear-change click behind a setting,
since it is reverse-engineered. If a pad or firmware ignores a zoned mode,
the simple forms are the fallback (DS-8 checks).

### Signal sources for the effects

FH1 has no telemetry output (that arrived in later Forzas), so the effects
must come from what the host can see:

1. **Pad input** (always available): throttle and brake positions from the
   merged guest state.
2. **The guest's two motor values** (always available): the game already
   encodes surface rumble, kerbs, collisions and wheelspin into them. Which
   motor carries what is **not traced**; DS-2.0 logs both against route
   events to find out.
3. **Car physics** (needs DS-3): per-wheel slip, ABS activity, RPM, gear,
   surface, speed, from the player car object. The game's own FFB path
   computes a "rear slip" term and a "spring against speed" term for wheels,
   so following it (and `CTelemetryHud`'s data sources) leads to those fields.

The community Forza-to-DualSense bridges (RacingDSX, `forza_trigger.py`)
give a tested effect design we can adopt once (3) exists, with their
thresholds as starting points: brake lock-up above combined slip 0.25 as
Vibration at full strength and >= 20 Hz, an ABS band as Machine on zones 2-8
with amplitudes 1/3 to 3/7 at 20-28 Hz rising with slip, otherwise a slope
of resistance from zone 2 to 8 that follows brake pressure; throttle
wheelspin as Vibration whose frequency maps slip 0.6-5.0 onto 0-55 Hz and
whose strength follows the dominant axle's slip on a log curve, a one-frame
Bow at zones 4-6 on a gear change, resistance falling with acceleration;
exponential smoothing (0.4-0.7) on every term and reports only when a value
changed. Those are tuned for FH5's physics, so DS-3.2 re-tunes on a route.

### Steam Input

When Steam runs with PlayStation Configuration Support on (the default) and
the game is launched through Steam, Steam opens the pad's HID device and
hands the game a **virtual Xbox 360 pad**: SDL then reports
`SDL_GAMEPAD_TYPE_XBOX360`, "Steam Virtual Gamepad", and no effects, LED or
sensors reach the hardware. If both Steam and the game write to the real
device the triggers oscillate (reported by the Linux bridge above). The
Steam Input API has no adaptive-trigger call for games; adaptive triggers
through Steam are a per-user configurator setting.

**Decision:** talk to the pad directly through SDL and detect the wrap.
`SDL_GetGamepadSteamHandle` is non-zero (or the name is "Steam Virtual
Gamepad") when Steam Input is in between; the DUALSENSE settings page then
says so and tells the player to disable Steam Input for the game (Properties
> Controller) to get the native features. On the Deck in Game Mode this is
the only way; FB-3.6 owns the Deck layout question. Valve's guidance for
games with native support is exactly this split: native path by default,
Steam Input for pads the game does not know.

### Platform matrix

| Platform | Transport | Expected | Caveats |
| --- | --- | --- | --- |
| Windows 10/11 | USB | full (triggers, rumble, LED, touchpad, gyro) | Steam wrap as above. No driver install. |
| Windows | Bluetooth | full once SDL enters enhanced reports (it does on the first effect, ~10 ms) | 78-byte reports with CRC; keep the send rate at or below 60 Hz and only on change. Firmware 0 over Bluetooth is treated as new by SDL. |
| Linux desktop | USB, Bluetooth | full | hidraw needs a udev rule (Steam installs one; without Steam document `054c:0ce6` and `0df2` with `TAG+="uaccess"`). SDL retries `open` for the udev race. Kernel >= 5.12 has `hid-playstation`. |
| Steam Deck, Game Mode | USB, Bluetooth | full only with Steam Input disabled for the game | Otherwise virtual Xbox pad. Desktop Mode without Steam: as Linux desktop. |
| macOS | USB, Bluetooth | full | SDL uses IOHIDManager; no permission prompt for HID gamepads (**probable**). |
| Android | USB-C (OTG) | full through SDL's Android HIDAPI | Android shows the USB permission dialog once per attach; the manifest already declares USB host. |
| Android | Bluetooth | buttons, axes, rumble only | SDL's Android HIDAPI opens USB devices and BLE Steam Controllers only; a Bluetooth DualSense goes through the kernel driver and Android's `InputDevice`, which has no trigger path. Android 12+ kernels (5.10/5.15) carry `hid-playstation`. |
| DualSense Edge | all | as DualSense plus four paddles and 1000 Hz sensors over USB | PID `0x0df2`; SDL maps the paddles as extra buttons. |

Voice-coil **haptics** (the pad is also a 4-channel USB audio device whose
rear pair drives the actuators) are a different mechanism: a PCM stream, USB
only, with no public Windows API beyond picking the audio endpoint. SDL's
rumble emulation already uses the coils. Out of scope here; see DS-9.

## Rows

| ID | Item | Size | Status |
| --- | --- | --- | --- |
| DS-0.1 | **Device identity.** Keep `SDL_GetGamepadType`, vendor, product and `SDL_GetGamepadSteamHandle` in `ControllerState` and expose a `type` and `steam_wrapped` on `DeviceInfo`, through `ActiveDeviceTracker` so the UI follows the pad in use. Log one line per pad at open. | S | Done (SDK): `PadFamily`, vendor, product, `steam_virtual` and the capability flags on `DeviceInfo`; `InputSystem::ActiveDevice`; one `SDL pad:` log line per pad |
| DS-0.2 | **Hints.** Set `SDL_HINT_JOYSTICK_HIDAPI_PS5_PLAYER_LED` from the LED setting before `SDL_InitSubSystem(SDL_INIT_GAMEPAD)` (`sdl_input_driver.cpp:96`); leave enhanced reports on `auto`. | S | Done (SDK): the player-LED hint is set before the gamepad subsystem starts and again when the setting changes |
| DS-1.0 | **Effect channel.** A `DualSenseEffects` object per PS5-type pad in the SDL driver: holds the two 11-byte trigger blocks, a dirty flag and the last send time; `Flush()` builds the 47-byte payload with only bits `0x04`/`0x08` set and calls `SDL_SendGamepadEffect`, at most once per 16 ms and only on change. `SetTrigger(side, effect)` with typed builders for Off, Feedback, Weapon, Vibration, Machine, slope Feedback and Bow, range-checked as in the reference generator. | S-M | Done (SDK `rex/input/dualsense.h`): builders checked byte for byte on the pad; the channel sends only changes, at most one report per 16.67 ms (unit test: 1000 changes in a second give 55-60 reports) |
| DS-1.1 | **Reset paths.** Off on both triggers and `SDL_SetGamepadLED` back to the player colour on: pad removal, window focus loss (rumble is already zeroed there), host UI taking input, process exit, and the crash handler's last breath. Gate: after a forced crash the triggers are slack. | S | Done: focus loss, quit and crash measured on the pad (see Results); the crash path writes straight to the HID device as well as through SDL |
| DS-2.0 | **Motor census.** Log the guest's two motor values with the route's events (surface, kerb, collision, wheelspin) on `fh1-race-retire`, and under a wheel-shaped state if DS-3 finds the flag, to learn which motor carries which effect. Record it on the site next to "rumble and force-feedback output". | S | Done: right motor = engine, left = bumps and impacts; recorded on the "rumble and force-feedback output" finding |
| DS-2.1 | **Trigger model v1 (input and motors).** Brake: slope Feedback from zone 2 to 8 following brake pressure, scaled by the strength setting; Vibration when the motor that carries wheel events is active while braking. Throttle: Vibration whose amplitude follows that motor while the throttle is pressed; otherwise light Feedback or Off. Smoothing 0.5 per step. Gate: feels like a brake and a wheelspin on a route by hand, no report storms (count <= 60/s), rumble unchanged. | M | Done: the fallback when no telemetry arrives; motor thresholds with hysteresis from the census; rumble path untouched |
| DS-3.0 | **Physics fields (reverse engineering).** From the FFB path (`sub_830EB930`, `sub_82BFC6F0`, the +4244 flag) and `CTelemetryHud`'s data sources, find the player car's per-wheel slip, ABS active, RPM, redline, gear, surface class and speed. Record each in `site/findings/objects.toml` and `config/mod/fh1-symbols.toml` (v4 in `fh1-offsets-v4.toml`) with how it was found and a confidence. Negative results too. | M | Done: engine, redline, gear, speed, ABS and per-wheel slip in `site/findings/objects.toml` (Car dynamics) and the mod symbols; the surface class is an index only (not enumerated) |
| DS-3.1 | **Telemetry hook.** A frame hook that copies those fields into a host `CarTelemetry` struct (like `PinyonShiftTraceFrameTelemetry` does for the frame root), with a cvar to dump it on a route for tuning. | S | Done: `QueueCarTelemetry` in `pinyon_shift_runtime_hooks.cpp`, only while a trigger or RPM setting is on; `pad_ds_log_telemetry` dumps it |
| DS-3.2 | **Trigger model v2 (physics).** Brake: ABS Machine band and lock-up Vibration from brake slip, slope resistance otherwise. Throttle: wheelspin Vibration with frequency and strength from drive-axle slip, resistance falling with acceleration, Bow click on gear change, launch resistance at the start line. Re-tune the bridge thresholds on `fh1-race-retire` with the telemetry dump. Gate: ABS band appears only while the game's ABS is active; wheelspin buzz only above the slip threshold; nothing in menus. | M | Done: thresholds re-tuned to the measured slip scale (1 = peak grip); ABS held 200 ms across the flag's gaps; launch resistance not built. After play-testing, the wheelspin buzz is a faint 40 Hz at 1-2 of 8 with hysteresis and a 0.25 s hold (stronger read as noise, slower as clicking) |
| DS-4.0 | **Lightbar and LEDs.** Modes: PLAYER (SDL default), RPM (green to red toward redline, from DS-3 or the game's rev motor if the census shows one), OFF (LEDs released, brightness low). Player LEDs follow the setting. | S | Done: PLAYER, RPM (green to red, flashing at 97 % of the redline) and OFF; PLAYER LIGHTS on or off |
| DS-5.0 | **Glyphs per device (FB-3.5).** Our own cross, circle, square, triangle, L1/R1/L2/R2, options and create marks for the host UI, chosen from `DeviceInfo.type`; replace the A/B help-bar textures and the mapping assistant's prompts; Xbox stays the default. The game HUD stays Xbox until NP-6.2. | S | Done in part: our own cross and circle for the help bar (drawn in code), PlayStation names in the mapping assistant; the other marks are text |
| DS-6.0 | **Gyro steering (FB-3.2).** Enable sensors on PS5, PS4 and Switch Pro types, handle `SDL_EVENT_GAMEPAD_SENSOR_UPDATE`, blend yaw rate into left-stick X with recentre on a chord and a sensitivity setting. Confirms that `SDL_SENSOR OFF` does not block it. | S-M | Built as tilt steering (roll from the accelerometer, gyro for the fast part), touchpad click re-centres; play-tested by hand on Windows over USB, steers the right way |
| DS-7.0 | **Settings.** F6 > CONTROLS > DUALSENSE: ADAPTIVE TRIGGERS (OFF / BRAKE / FULL), TRIGGER STRENGTH (25-100 %), GEAR CLICK, LIGHTBAR (PLAYER / RPM / OFF), GYRO STEERING and its sensitivity; the Steam Input notice from DS-0.1. Cvars `pad_ds_triggers`, `pad_ds_trigger_strength`, `pad_ds_gear_click`, `pad_ds_lightbar`, `pad_gyro_steering`, `pad_gyro_sensitivity`, hot-reloadable. Shown only when a PS5-type pad is present or was seen. | S | Done: F6 > CONTROLS > DUALSENSE, shown when a PlayStation pad or Steam's virtual pad is present or a setting is not at its default |
| DS-8.0 | **Hardware matrix.** Windows USB and Bluetooth, Linux desktop with the udev rule, Deck Game Mode with Steam Input off, macOS Bluetooth, Android USB-C and Bluetooth, DualSense Edge if available. Record results in the tested-hardware table on the site. | M | Windows 11 USB done and play-tested by hand (triggers, gear click, tilt); Bluetooth needs the pad paired; Linux, Deck, macOS, Android and Edge not tested |
| DS-9 | **Later.** Voice-coil haptics over the USB audio endpoint (engine note into the coils); touchpad gestures (swipe for the map); Edge paddle defaults; trigger effects for DualShock 4 (none, rumble only). | L | Not planned |

Order: DS-0 and DS-1 first (a day), DS-2 gives a shippable v1 without any
reverse engineering, DS-7 exposes it, DS-8 qualifies it; DS-3 runs in
parallel as the research thread and lands v2. DS-4, DS-5, DS-6 are
independent and small.

## Results

Measured on multivac (Windows 11, a DualSense over USB) with scripted routes from the pinned seed
(`--fh1_render_test_physical_pads=true` adds the real pad beside the
synthetic one). A second process read the pad's input report while the game
ran and logged the effect each trigger reported back (byte 48 of the USB
input report: low nibble right trigger, high nibble left; 0 off, 1
feedback, 2 weapon, 3 vibration, 4 bow, 6 machine).

| Gate | Result |
|---|---|
| Effects reach the pad | Feedback, vibration, bow and machine each reported back by the pad; on a drive the brake reported the slope, the ABS band on hard stops and the lock-up buzz, the throttle the spring, the wheelspin buzz and the gear click |
| Reports per second | At most 20 in any one-second window on a drive (the log counts each report); the channel's limit is 60 |
| Slack after quit | Both triggers off as the process exits, every run |
| Slack after focus loss | Off as another window took focus, and nothing sent while unfocused |
| Slack after a crash | Off after a forced access violation in 3 of 3 runs once the direct write was added (3 of 6 before it) |
| Rumble unchanged | `SetDeviceVibration` untouched; the crash path alone also stops the motors |
| Steam Input | With Steam's `SDL_GAMECONTROLLER_IGNORE_DEVICES` list set, SDL does not open the pad and the log and the DUALSENSE page say why |
| Everything off by default | `pad_ds_triggers=off`, the lightbar stays SDL's player colour, tilt steering off; with the triggers off the telemetry hook does not run |

**Telemetry.** On the route's launch the rear wheels read +4 to +12
slip (spinning in first), about +0.4 accelerating cleanly in second, the
front −2.75 locking under braking, and −6 to −14 spinning in reverse, so the
model flips the sign in reverse. The engine idled at 700-800 RPM against a
7300 RPM redline. The ABS flag is set only on the physics steps that release
a wheel, so the model holds the ABS band for 200 ms after the last one.

**The crash release and SDL's rumble queue.** SDL's HIDAPI driver sends
every effect, LED and rumble report from one thread that writes a report and
then sleeps 10 ms. It folds a new report into a queued one only when the
enable bits match, so the game's rumble (120 calls a second while driving)
and trigger reports queue behind each other. Three of six forced crashes, those
during hard braking or just after, left the triggers stiff: the release went into the queue
and the process was gone before it was written. The crash path now writes
the slack report to the device through its own handle (USB report `0x02`,
or `0x31` with its CRC over Bluetooth) before and after the SDL path.

**Driving or not.** The trigger effects run only while the player's car
exists with driver type 0 and the gameplay camera is on. The car's control
word `+0x3B78` reads 1 while the player drives in the base game, so it
cannot be used for this.

**v4.** The title-update build reads the same values from the same
offsets (a route from the v4 seed: 700-800 RPM idle, 7300 RPM redline,
gears, the ABS flag), finds the player's car through the driver type, and
the pad showed the same effects and the release on quit.

## Risks and open questions

- **Which motor is which.** The guest path swaps the motors on kernels before
  2.0.5611; the SDK reports a newer kernel, so no swap, but what each motor
  carries is a guess until DS-2.0.
- **Bluetooth report rate.** 78 bytes per report; sending every frame when
  nothing changed stalls some stacks. The dirty flag and the 16 ms floor are
  the mitigation; DS-8 measures.
- **Steam ownership.** Nothing the game does can take the device back from
  Steam Input; the player has to turn it off per game. The notice must be
  clear, and the site's player page gets the step.
- **Third-party "PS5-type" pads** may accept the trigger bytes and do
  nothing, or worse. Gate the effects on vendor `0x054c` and PIDs `0x0ce6`
  and `0x0df2`; let other PS5-type pads have glyphs and rumble only.
- **Physics offsets in v4** move by 16 bytes after `0x3B00`; DS-3.0 records
  both.
- **Legal.** The glyphs are our own drawings of generic shapes; no Sony art.
  The protocol notes above are our analysis of public references, not Sony
  documentation.

## References

- SDL3 `src/joystick/hidapi/SDL_hidapi_ps5.c` (vendored): `DS5EffectsState_t`,
  `HIDAPI_DriverPS5_SendJoystickEffect`, rumble and LED paths.
- SDL3 wiki: `SDL_SendGamepadEffect`, `SDL_HINT_JOYSTICK_ENHANCED_REPORTS`,
  `SDL_HINT_JOYSTICK_HIDAPI_PS5_PLAYER_LED`, `SDL_GetGamepadButtonLabel`.
- Linux `drivers/hid/hid-playstation.c`: output report IDs and sizes, valid
  flag bits, Bluetooth CRC seeds.
- John "Nielk1" Klein, `TriggerEffectGenerator` (MIT): trigger effect modes
  and byte layouts.
- `nowrep/dualsensectl`: Linux CLI for the same reports; udev rule.
- Microsoft `GameInput/companion/DualSense/DualSenseHelper.h` (MIT): the
  USB-only Windows helper, not used as code; the source for the trigger
  enable bits and the simple trigger modes.
- Valve, Steamworks "Steam Input: getting started for developers": native and
  Steam Input coexistence.
- Community Forza bridges (`RacingDSX`, `forza_trigger.py`): effect design
  and thresholds for a racing game.
