#pragma once

#include <algorithm>
#include <cstdint>
#include <vector>

// Call while holding the core lock. This returns owned bytes, never a pointer into a running core.
inline std::vector<int8_t> copySystemRam(const int8_t* data, size_t size, size_t offset, size_t length,
                                       int visibleOffset, int visibleValue) {
    if (!data || offset >= size || length == 0 || length > 65536 || visibleOffset < -1 ||
        visibleValue < 0 || visibleValue > 255) return {};
    if (visibleOffset >= 0 && (static_cast<size_t>(visibleOffset) >= size ||
        static_cast<uint8_t>(data[visibleOffset]) != visibleValue)) return {};
    length = std::min(length, size - offset);
    return {data + offset, data + offset + length};
}
