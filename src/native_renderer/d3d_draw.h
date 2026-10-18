#pragma once

// D3D-level draws (docs/D3D_RENDERER_RESEARCH.md DL-1.2): the game side of
// rex/system/d3d_draw.h. Hooks on the D3D runtime's indexed draw call write
// a marker into the device's command buffer where the call's packets start
// and, when the call returns, describe the draw from the device's state
// (the registers it flushes from its shadow, the shader and literal-constant
// loads of its shader bind, the draw packet's words) for the command
// processor, which draws the description in place of those packets.
//
// pinyon_shift_d3d_draw (hot): 0 off (every hook returns at once), 1 verify,
// 2 draw. Base-disc build only (the hooks are in the base analysis config).

namespace pinyon_shift::native_renderer {

// Called at the source-frame boundary (VdSwap): logs the game side's counts
// every 600 frames while they are non-zero.
void D3dDrawFrame();

}  // namespace pinyon_shift::native_renderer
