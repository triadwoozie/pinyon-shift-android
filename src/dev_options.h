#pragma once

namespace pinyon_shift::dev_options {

// Quality-of-life switches from FH1's developer launch options (#477). The
// retail game never parses its command line into the options object, so the
// host writes the chosen byte flags itself. Called from the guest file-open
// observer (early boot) and once per title frame; cheap when nothing is on.
void Update();

}  // namespace pinyon_shift::dev_options
