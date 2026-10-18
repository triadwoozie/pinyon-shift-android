"""The HUD toggle key (#519).

It holds the game's HUD-hidden byte while hud_hidden is set; writing the
nohud launch option at run time does nothing, since the HUD reads it only
before an element first shows.
"""

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


class HudToggleTests(unittest.TestCase):
    def test_the_toggle_holds_the_hud_hidden_byte(self):
        source = read("src/hud_toggle.cpp")
        self.assertIn("FH1_ADDR(0x833023C1u)", source)
        self.assertNotIn("3213", source)
        self.assertIn("{0x833023C1u, 0x833F0F91u}", read("src/fh1_v4_addresses.inc"))

    def test_it_runs_each_frame_and_has_a_key(self):
        self.assertIn("pinyon_shift::hud::Update();", read("src/pinyon_shift_runtime_hooks.cpp"))
        app = read("src/pinyon_shift_app.cpp")
        self.assertIn('RegisterBind("bind_hud", pinyon_shift::config::kHudKeys', app)
        self.assertIn('UnregisterBind("bind_hud")', app)
        self.assertIn('kHudKeys = "F2"', read("src/config/default_keys.h"))
        self.assertIn("src/hud_toggle.cpp", read("CMakeLists.txt"))


if __name__ == "__main__":
    unittest.main()
