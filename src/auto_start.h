#pragma once

// START IN SINGLE PLAYER (#617): with a profile on disk, press A on the
// Press Start screen and again on SINGLE PLAYER, through a synthetic pad on
// user 0, so the game loads straight into the player's career. Each press is
// confirmed by the files the game opens next (the profile after Press Start,
// the world after SINGLE PLAYER) and retried a few times; anything else ends
// it, leaving the menus to the player.

#include <filesystem>
#include <memory>
#include <string_view>

#include <rex/input/input_driver.h>

namespace pinyon_shift::auto_start {

// Called once the paths are known. Arms the sequence when the setting is on
// and `user_root` holds a ForzaProfile; returns whether it did.
bool Configure(const std::filesystem::path& user_root);

// Whether Configure armed it, so the input factory adds the driver.
bool Armed();

std::unique_ptr<rex::input::InputDriver> CreateDriver();

// From the guest file-open observer (lowercase guest paths).
void ObserveFileOpened(std::string_view guest_path);

}  // namespace pinyon_shift::auto_start
