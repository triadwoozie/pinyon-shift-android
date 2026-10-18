#pragma once

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace pinyon_shift::audio_mix {

// The audio mixer (#486): the title reads each sound category's level in
// every mix state (free roam, race, paused...) from
// media\audio\AudioMixerSnapshots.xml, and its output compressor from
// media\audio\AudioEngineConfig.xml, when it starts. When a setting is off
// its default, writes copies changed by the settings under the state root
// and returns that root, to be the first game file overlay. `sources` are
// the overlay roots ahead of the disc (a mod's copy is the one changed),
// then the disc. Returns nothing at the defaults, so the title reads its
// own files.
std::optional<std::filesystem::path> Prepare(const std::filesystem::path& state_root,
                                             const std::vector<std::filesystem::path>& sources);

// The files' text with the settings applied; exposed for tests.
std::string ApplyLevels(std::string snapshots);
std::string ApplyCompressor(std::string config);

}  // namespace pinyon_shift::audio_mix
