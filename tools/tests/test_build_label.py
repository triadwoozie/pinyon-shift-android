"""The window title and the F3 overlay name the release and commits (#545)."""

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SDK = ROOT / "thirdparty/shiftglue-sdk"


def read(path):
    return path.read_text(encoding="utf-8")


class BuildLabelTests(unittest.TestCase):
    def test_the_sdk_shows_the_host_label(self):
        overlay = read(SDK / "src/ui/overlay/debug_overlay.cpp")
        self.assertIn("ImGui::TextUnformatted(label.c_str());", overlay)
        self.assertNotIn("TextUnformatted(REXGLUE_BUILD_STAMP)", overlay)
        app = read(SDK / "src/ui/rex_app.cpp")
        self.assertIn("rex::ui::GetBuildLabel()", app)
        self.assertNotIn("rexglue-v", read(SDK / "include/rex/version.h.in"))

    def test_the_game_sets_it_before_the_window(self):
        app = read(ROOT / "src/pinyon_shift_app.cpp")
        setup = app[app.index("void PinyonShiftApp::OnPreSetup("):]
        self.assertIn("rex::ui::SetBuildLabel(pinyon_shift::diagnostics::BuildLabel());",
                      setup[:setup.index("\n}\n")])

    def test_the_label_names_version_commits_and_build_time(self):
        diagnostics = read(ROOT / "src/pinyon_shift_diagnostics.cpp")
        label = diagnostics[diagnostics.index("std::string BuildLabel() {"):]
        label = label[:label.index("\n}\n")]
        for part in ('"Pinyon Shift "', "PINYON_SHIFT_VERSION", '" | ShiftGlue "',
                     "build.created_utc", "kPlatformName"):
            self.assertIn(part, label)
        self.assertIn('{"build_label", BuildLabel()}', diagnostics)
        cmake = read(ROOT / "cmake/PinyonShiftRexGlue.cmake")
        self.assertIn('string(JSON PINYON_SHIFT_VERSION GET "${PINYON_SHIFT_RELEASE_JSON}" version)',
                      cmake)
        self.assertIn('PINYON_SHIFT_VERSION="${PINYON_SHIFT_VERSION}"', cmake)


if __name__ == "__main__":
    unittest.main()
