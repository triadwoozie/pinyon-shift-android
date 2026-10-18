#pragma once

// D3D source census (docs/D3D_RENDERER_RESEARCH.md DL-0.1 and DL-0.2): the
// game-side observers behind the census hooks in
// config/rexglue/analysis/main-xex.toml. They count the title's D3D draw
// entry points by the buffer the device writes into, the engine device
// class's draw and state slots, the display lists' life (runs, fixups,
// origin, changes between runs) and the device's buffer switches, and mark
// in rex/system/d3d_census.h where each call's packets start, so the command
// processor can tag every drawn draw by the path that produced it.
//
// Observation only: the hooks read guest memory and registers and never
// write either. Every hook returns at once while pinyon_shift_d3d_census is
// off. Base-disc build only (the v4 build has no census hooks).

#include <cstdint>

namespace pinyon_shift::native_renderer {

// Called at the source-frame boundary (VdSwap): applies the cvar and logs a
// window every pinyon_shift_d3d_census_window frames.
void D3dCensusFrame();

// The indexed title draw wrapper's entry, whose hook it shares with the
// PERF-11 probe: draw entry 5.
void D3dCensusDrawEntry(uint32_t entry, uint32_t r1, uint32_t device, uint32_t lr);

}  // namespace pinyon_shift::native_renderer
