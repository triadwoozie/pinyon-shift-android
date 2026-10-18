#include "ui/hostui/pad_glyphs.h"

#include <algorithm>
#include <cmath>

namespace pinyon_shift::hostui {

namespace {

float SegmentDistance(float px, float py, float ax, float ay, float bx, float by) {
  const float vx = bx - ax, vy = by - ay;
  const float t = std::clamp(((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy), 0.0f, 1.0f);
  return std::hypot(px - (ax + t * vx), py - (ay + t * vy));
}

// Distance from the point to the mark's centre line, in units of the disc's
// radius (the disc spans -1..1).
float MarkDistance(PadGlyph glyph, float x, float y) {
  constexpr float kHalf = 0.42f;
  switch (glyph) {
    case PadGlyph::kCross:
      return std::min(SegmentDistance(x, y, -kHalf, -kHalf, kHalf, kHalf),
                      SegmentDistance(x, y, -kHalf, kHalf, kHalf, -kHalf));
    case PadGlyph::kCircle:
      return std::abs(std::hypot(x, y) - 0.45f);
    case PadGlyph::kSquare: {
      constexpr float s = 0.4f;
      return std::min({SegmentDistance(x, y, -s, -s, s, -s), SegmentDistance(x, y, s, -s, s, s),
                       SegmentDistance(x, y, s, s, -s, s), SegmentDistance(x, y, -s, s, -s, -s)});
    }
    case PadGlyph::kTriangle: {
      // Point up; y grows downward in the image.
      const float top_y = -0.5f, base_y = 0.32f, half_base = 0.5f;
      return std::min({SegmentDistance(x, y, 0.0f, top_y, half_base, base_y),
                       SegmentDistance(x, y, half_base, base_y, -half_base, base_y),
                       SegmentDistance(x, y, -half_base, base_y, 0.0f, top_y)});
    }
  }
  return 1.0f;
}

}  // namespace

std::vector<uint8_t> RasterizePadGlyph(PadGlyph glyph, int size) {
  size = std::max(size, 8);
  std::vector<uint8_t> rgba(size_t(size) * size_t(size) * 4, 0);
  const float pixel = 2.0f / float(size);  // one pixel in disc units
  constexpr float kStroke = 0.075f;        // half the mark's line width
  for (int py = 0; py < size; ++py) {
    for (int px = 0; px < size; ++px) {
      const float x = (float(px) + 0.5f) * pixel - 1.0f;
      const float y = (float(py) + 0.5f) * pixel - 1.0f;
      // Coverage with a one-pixel soft edge.
      const float disc = std::clamp((0.96f - std::hypot(x, y)) / pixel + 0.5f, 0.0f, 1.0f);
      const float mark =
          std::clamp((kStroke - MarkDistance(glyph, x, y)) / pixel + 0.5f, 0.0f, 1.0f) * disc;
      // Dark grey disc, near-white mark.
      const float shade = 0.16f + (0.94f - 0.16f) * mark;
      uint8_t* out = &rgba[(size_t(py) * size_t(size) + size_t(px)) * 4];
      const uint8_t value = uint8_t(std::lround(shade * 255.0f));
      out[0] = value;
      out[1] = value;
      out[2] = value;
      out[3] = uint8_t(std::lround(disc * 255.0f));
    }
  }
  return rgba;
}

}  // namespace pinyon_shift::hostui
