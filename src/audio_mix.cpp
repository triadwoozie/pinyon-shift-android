#include "audio_mix.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string_view>

#include <fmt/format.h>

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/logging.h>

#include "pinyon_shift_diagnostics.h"

// Percent of the title's own level. The title reads the files once, at start.
#define MIX_CVAR(name, what)                                                              \
  REXCVAR_DEFINE_INT32(name, 100, "Audio",                                                \
                       "Volume of " what ", in percent of the game's own level (0 to 200)") \
      .range(0, 200)                                                                      \
      .lifecycle(rex::cvar::Lifecycle::kRequiresRestart)
MIX_CVAR(pinyon_shift_mix_engine, "the player's engine");
MIX_CVAR(pinyon_shift_mix_opponents, "other cars' engines");
MIX_CVAR(pinyon_shift_mix_tyres, "tyres");
MIX_CVAR(pinyon_shift_mix_wind, "wind");
MIX_CVAR(pinyon_shift_mix_ambience, "ambience (crowds, traffic, the world)");
MIX_CVAR(pinyon_shift_mix_music, "the game's music and radio");
#undef MIX_CVAR
REXCVAR_DEFINE_BOOL(pinyon_shift_mix_full_dynamic_range, false, "Audio",
                    "Bypass the game's output compressor: loud sounds keep their peaks, for "
                    "headphones and good speakers")
    .lifecycle(rex::cvar::Lifecycle::kRequiresRestart);

namespace pinyon_shift::audio_mix {
namespace {

constexpr std::string_view kConfig = "AudioEngineConfig.xml";
constexpr std::string_view kSnapshots = "AudioMixerSnapshots.xml";

// A category's elements in the snapshots of the Gameplay group (FreeRoam,
// Race, Paused...): destvolume is the level the category fades to. Groups
// (Gameplay, Location, Speed, Radio...) are active together and their
// levels multiply, so only the Gameplay group, one snapshot at a time, is
// scaled: scaling every group's engine level by half made it near silent.
// The AudioEngineConfig.xml levels (gamePlayerEngine...) have no audible
// effect in free roam: every one at 0 measured the same as the defaults.
struct Category {
  int percent;
  std::vector<std::string_view> elements;
};

std::vector<Category> Categories() {
  return {
      {REXCVAR_GET(pinyon_shift_mix_engine), {"FocusCar"}},
      {REXCVAR_GET(pinyon_shift_mix_opponents), {"OppCar"}},
      {REXCVAR_GET(pinyon_shift_mix_tyres), {"Tire"}},
      {REXCVAR_GET(pinyon_shift_mix_wind), {"CarWind"}},
      {REXCVAR_GET(pinyon_shift_mix_ambience),
       {"AmbientStatic", "AmbientDynamic", "HomespaceAmbientDynamic"}},
      {REXCVAR_GET(pinyon_shift_mix_music), {"GameMusic", "HomespaceMusic"}},
  };
}

bool LevelsAtDefaults() {
  for (const auto& category : Categories()) {
    if (category.percent != 100) return false;
  }
  return true;
}

bool IsSpace(char c) { return std::isspace(static_cast<unsigned char>(c)) != 0; }

// The value of `name="..."` (spaces allowed around '=') in [from, to), as
// offsets of the text between the quotes, or npos.
std::pair<size_t, size_t> FindAttribute(const std::string& text, std::string_view name,
                                        size_t from = 0, size_t to = std::string::npos) {
  to = std::min(to, text.size());
  for (size_t at = text.find(name, from); at != std::string::npos && at < to;
       at = text.find(name, at + 1)) {
    // A whole attribute name: whitespace before, '=' after.
    if (at == 0 || !IsSpace(text[at - 1])) continue;
    size_t i = at + name.size();
    while (i < to && IsSpace(text[i])) ++i;
    if (i >= to || text[i] != '=') continue;
    ++i;
    while (i < to && IsSpace(text[i])) ++i;
    if (i >= to || (text[i] != '"' && text[i] != '\'')) continue;
    const size_t close = text.find(text[i], i + 1);
    if (close == std::string::npos || close >= to) break;
    return {i + 1, close};
  }
  return {std::string::npos, std::string::npos};
}

void SetAttribute(std::string& text, std::string_view name, const std::string& value) {
  const auto [begin, end] = FindAttribute(text, name);
  if (begin == std::string::npos) {
    REXLOG_WARN("Audio mix: {} has no {}", kConfig, name);
    return;
  }
  text.replace(begin, end - begin, value);
}

std::optional<std::string> ReadFile(const std::filesystem::path& path) {
  std::ifstream file(path, std::ios::binary);
  if (!file) return std::nullopt;
  return std::string(std::istreambuf_iterator<char>(file), {});
}

}  // namespace

std::string ApplyLevels(std::string snapshots) {
  const auto categories = Categories();
  size_t changed = 0;
  bool gameplay = false;
  for (size_t open = snapshots.find('<'); open != std::string::npos;
       open = snapshots.find('<', open + 1)) {
    size_t name_end = open + 1;
    while (name_end < snapshots.size() &&
           std::isalnum(static_cast<unsigned char>(snapshots[name_end]))) {
      ++name_end;
    }
    const std::string_view name(snapshots.data() + open + 1, name_end - open - 1);
    const size_t close = snapshots.find('>', name_end);
    if (name.empty() || close == std::string::npos) continue;
    // A snapshot names its group; its categories follow it.
    if (const auto [begin, end] = FindAttribute(snapshots, "group", name_end, close);
        begin != std::string::npos) {
      gameplay = std::string_view(snapshots).substr(begin, end - begin) == "Gameplay";
      continue;
    }
    if (!gameplay) continue;
    for (const auto& category : categories) {
      if (std::find(category.elements.begin(), category.elements.end(), name) ==
          category.elements.end()) {
        continue;
      }
      if (category.percent == 100) break;
      const auto [begin, end] = FindAttribute(snapshots, "destvolume", name_end, close);
      if (begin == std::string::npos) break;
      const double level = std::strtod(snapshots.substr(begin, end - begin).c_str(), nullptr);
      snapshots.replace(begin, end - begin,
                        fmt::format("{:.6f}", level * category.percent / 100.0));
      ++changed;
      break;
    }
  }
  if (!changed && !LevelsAtDefaults()) {
    REXLOG_WARN("Audio mix: {} has no category levels", kSnapshots);
  }
  return snapshots;
}

std::string ApplyCompressor(std::string config) {
  if (REXCVAR_GET(pinyon_shift_mix_full_dynamic_range)) {
    // Both sets: the title picks the TV one for a TV output.
    SetAttribute(config, "compressorBypass", "true");
    SetAttribute(config, "compressorBypassTV", "true");
  }
  return config;
}

std::optional<std::filesystem::path> Prepare(const std::filesystem::path& state_root,
                                             const std::vector<std::filesystem::path>& sources) {
  const bool levels = !LevelsAtDefaults();
  const bool compressor = REXCVAR_GET(pinyon_shift_mix_full_dynamic_range);
  if (!levels && !compressor) return std::nullopt;
  const auto root = state_root / "generated" / "audio-mix";
  std::error_code error;
  // Only the files the current settings change.
  std::filesystem::remove_all(root, error);
  bool wrote = false;
  const auto generate = [&](std::string_view file, std::string (*apply)(std::string)) {
    const std::filesystem::path relative = std::filesystem::path("media") / "audio" / file;
    for (const auto& source : sources) {
      auto text = ReadFile(source / relative);
      if (!text) continue;
      std::filesystem::create_directories((root / relative).parent_path(), error);
      const std::string changed = apply(std::move(*text));
      std::ofstream out(root / relative, std::ios::binary | std::ios::trunc);
      out.write(changed.data(), std::streamsize(changed.size()));
      out.close();
      if (out) {
        wrote = true;
        diagnostics::RecordEvent("audio.mix.file",
                                 {{"source", rex::path_to_utf8(source / relative)}});
      } else {
        REXLOG_WARN("Audio mix: cannot write {}", rex::path_to_utf8(root / relative));
      }
      return;
    }
    REXLOG_WARN("Audio mix: no {} to change; the game's is used", file);
  };
  if (levels) generate(kSnapshots, &ApplyLevels);
  if (compressor) generate(kConfig, &ApplyCompressor);
  if (!wrote) return std::nullopt;
  diagnostics::RecordEvent(
      "audio.mix.applied",
      {{"engine", std::to_string(REXCVAR_GET(pinyon_shift_mix_engine))},
       {"opponents", std::to_string(REXCVAR_GET(pinyon_shift_mix_opponents))},
       {"tyres", std::to_string(REXCVAR_GET(pinyon_shift_mix_tyres))},
       {"wind", std::to_string(REXCVAR_GET(pinyon_shift_mix_wind))},
       {"ambience", std::to_string(REXCVAR_GET(pinyon_shift_mix_ambience))},
       {"music", std::to_string(REXCVAR_GET(pinyon_shift_mix_music))},
       {"full_dynamic_range", compressor ? "1" : "0"}});
  return root;
}

}  // namespace pinyon_shift::audio_mix
