#include "../src/netplay_state_hash.h"
#include <cassert>

int main() {
    using libretrodroid::netplayStateHash;
    constexpr size_t misc = 37 + 128 * 96 * 3 + 0x200000 + 0x80000 + 0xf000;
    std::vector<uint8_t> original(0x440000);
    std::memcpy(original.data(), "STv4 PCSXra test", 15);
    original[32] = 0x06; original[34] = 0x41; original[35] = 0x8b;
    std::memcpy(original.data() + misc, "MISC", 4);
    auto other = original;
    std::memcpy(other.data() + 48, "different compiler", 18);
    other[misc + 36] = 91;
    assert(netplayStateHash(original) == netplayStateHash(other));
    libretrodroid::StateDiagnostic firstBlocks, secondBlocks;
    assert(netplayStateHash(original, &firstBlocks) == netplayStateHash(other, &secondBlocks));
    assert(firstBlocks == secondBlocks);
    other[37 + 128 * 96 * 3 + 1234] ^= 1;
    netplayStateHash(other, &secondBlocks);
    assert(firstBlocks[0] != secondBlocks[0]);
    for (size_t i = 1; i < firstBlocks.size(); ++i) assert(firstBlocks[i] == secondBlocks[i]);
    assert(netplayStateHash(original) != netplayStateHash(other));
    other = original;
    other[40] = 1;
    assert(netplayStateHash(original) != netplayStateHash(other));
    other = original;
    other[misc + 4] = 1;
    assert(netplayStateHash(original) != netplayStateHash(other));
    original[32] = 7;
    other = original;
    other[48] = 1;
    assert(netplayStateHash(original) != netplayStateHash(other));
    assert(netplayStateHash({1, 2}) != netplayStateHash({1, 3}));
}
