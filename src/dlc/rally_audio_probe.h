#pragma once

namespace rex { struct RuntimeConfig; }
namespace pinyon_shift::rally {
// Private render-test capture of native PCM before SDL output (eight seconds,
// or PINYON_SHIFT_AUDIO_PROBE_SECONDS) and of each captured frame's submit time.
void ConfigureAudioProbe(rex::RuntimeConfig& config);
void BeginAudioProbeCapture();
void FlushAudioProbeCapture();
}
