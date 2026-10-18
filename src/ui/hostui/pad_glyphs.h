#pragma once

#include <cstdint>
#include <vector>

namespace pinyon_shift::hostui {

// Face-button marks for the host UI's help bar when the pad in hand is a
// PlayStation pad (FB-3.5): our own drawings of generic shapes, a light mark
// on a dark disc, in the place of the disc's Xbox A and B badges.
enum class PadGlyph { kCross, kCircle, kSquare, kTriangle };

// Square RGBA image, straight alpha, `size` pixels a side.
std::vector<uint8_t> RasterizePadGlyph(PadGlyph glyph, int size);

}  // namespace pinyon_shift::hostui
