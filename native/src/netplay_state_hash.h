#ifndef LIBRETRODROID_NETPLAY_STATE_HASH_H
#define LIBRETRODROID_NETPLAY_STATE_HASH_H

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace libretrodroid {
constexpr size_t STATE_DIAGNOSTIC_BLOCK_SIZE = 65536;
constexpr size_t STATE_DIAGNOSTIC_BLOCKS = 128;
using StateDiagnostic = std::array<uint64_t, STATE_DIAGNOSTIC_BLOCKS>;
inline uint64_t netplayStateHash(const std::vector<uint8_t>& bytes, StateDiagnostic* blocks = nullptr) {
    if (blocks) blocks->fill(1469598103934665603ULL);
    constexpr size_t headerSize = 32 + 4 + 1;
    constexpr size_t buildInfo = headerSize + 3 + 8;
    constexpr size_t misc = headerSize + 128 * 96 * 3 + 0x200000 + 0x80000 + 0xf000;
    constexpr size_t saveCounter = misc + 9 * 4;
    const bool pcsx = bytes.size() > saveCounter + 4
        && std::memcmp(bytes.data(), "STv4 PCSXra ", 11) == 0
        && bytes[32] == 0x06 && bytes[33] == 0x00 && bytes[34] == 0x41 && bytes[35] == 0x8b
        && bytes[misc] == 'M' && bytes[misc + 1] == 'I' && bytes[misc + 2] == 'S' && bytes[misc + 3] == 'C';
    uint64_t hash = 1469598103934665603ULL;
    auto append = [&](size_t from, size_t to) {
        for (size_t i = from; i < to; ++i) {
            hash ^= bytes[i];
            hash *= 1099511628211ULL;
            if (blocks && i / STATE_DIAGNOSTIC_BLOCK_SIZE < blocks->size()) {
                auto& block = (*blocks)[i / STATE_DIAGNOSTIC_BLOCK_SIZE];
                block ^= bytes[i];
                block *= 1099511628211ULL;
            }
        }
    };
    if (pcsx) {
        append(0, buildInfo);
        append(buildInfo + 64, saveCounter);
        append(saveCounter + 4, bytes.size());
    } else {
        append(0, bytes.size());
    }
    return hash;
}
}
#endif
