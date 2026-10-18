---
title: Threads and frame timing
section: Reverse engineering
order: 4
description: Which guest threads Forza Horizon runs and what each does, how its main loop steps the simulation on the vblank, how the render thread follows it, how frames reach the display through fences and swap interrupts, and which functions are hot.
---

# Threads and frame timing

Forza Horizon splits a frame between two threads. The **main thread** runs the simulation and game logic and records what should be drawn as deferred commands ([The executable](game-executable.md#classes)); the **render thread** replays those commands into D3D and builds the GPU's command buffer. FMOD's mixer thread, the game's own audio thread and a pool of job workers that mostly run car physics do the rest. The console ran this at 30 frames a second on a 60 Hz display, and the timing rules below were written for that.

{{diagram:game-frame-timing|One 30 fps frame on the console. The simulation steps every second vblank; the render thread draws the step the simulation finished and idles until the next one; the GPU executes it and the frame is shown on the following whole vblank.}}

The parts that matter when changing the frame rate:

- The simulation is paced by the **vblank**, not by the present. Its callback raises the step event every second vblank, so a faster present alone does not make the game run more steps.
- The simulation's time step is measured from a millisecond clock, with a snap to 1/59.94 s for the common case and a single clamp for very long frames. A long stall is integrated as one large step.
- The render thread does not wait on an event when it has nothing new to draw: it loops through a zero, alertable `SleepEx`.
- The present waits for the GPU fence of the previous frame, so the main thread spends much of its time blocked there when the GPU is the bottleneck.

How Pinyon Shift changes this for 60 fps and higher is under [Above 30 fps](runtime.md#above-30-fps) on the Runtime page; this page describes the game as shipped.

## Thread map

{{findings:threads/Thread map}}

## Main loop

{{findings:threads/Main loop}}

## Frame pacing

{{findings:threads/Frame pacing}}

## Present and fences

{{findings:threads/Present and fences}}

## Hot functions

These are the guest functions that take the most time in thread-sampler, WPR and `simpleperf` profiles of races and free roam on desktop and Android ([Methods and tools](game-methods.md#profilers)). Knowing them tells a performance investigation where generated code matters.

{{findings:threads/Hot functions}}
