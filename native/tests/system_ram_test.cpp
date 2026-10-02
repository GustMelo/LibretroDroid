#include "../src/system_ram.h"
#include <cassert>
#include <limits>

int main() {
    int8_t ram[] = {1, 2, -1, 4};
    assert(copySystemRam(nullptr, 4, 0, 4, -1, 0).empty());
    assert(copySystemRam(ram, 4, 4, 1, -1, 0).empty());
    assert(copySystemRam(ram, 4, std::numeric_limits<size_t>::max(), 4, -1, 0).empty());
    assert(copySystemRam(ram, 4, 0, 0, -1, 0).empty());
    assert(copySystemRam(ram, 4, 0, 65537, -1, 0).empty());
    assert(copySystemRam(ram, 4, 0, 4, 4, 0).empty());
    assert(copySystemRam(ram, 4, 0, 4, 2, 254).empty());
    assert(copySystemRam(ram, 4, 0, 4, -2, 0).empty());
    assert(copySystemRam(ram, 4, 0, 4, -1, 256).empty());
    auto copy = copySystemRam(ram, 4, 1, 65536, 2, 255);
    assert((copy == std::vector<int8_t>{2, -1, 4}));
    ram[1] = 9;
    assert(copy[0] == 2);
}
