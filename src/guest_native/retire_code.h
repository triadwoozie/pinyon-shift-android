#pragma once

namespace rex::memory {
class Memory;
}

namespace pinyon_shift::guest_native {

// pinyon_shift_retire_guest_code (NN-6.7): checks every replacement's guest
// code, then takes access from (and with mode 2 frees) the code bytes.
void RetireGuestCode(rex::memory::Memory* memory);

}  // namespace pinyon_shift::guest_native
