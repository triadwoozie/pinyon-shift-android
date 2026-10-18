#pragma once

namespace pinyon_shift::hitch {

// Called by the title's frame hook on its main thread. On Windows, when
// pinyon_shift_hitch_sample_ms is set and a frame runs longer, a sampler
// thread logs where that frame's time went by guest function ("Hitch:"
// lines). Elsewhere it does nothing.
void NoteFrame();

}  // namespace pinyon_shift::hitch
