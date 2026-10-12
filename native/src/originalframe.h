#ifndef LIBRETRODROID_ORIGINALFRAME_H
#define LIBRETRODROID_ORIGINALFRAME_H

#include <atomic>
#include <cstdint>
#include <cstring>
#include <vector>
#include "libretro.h"

namespace libretrodroid {
/** Owned, top-down RGB565 pixels from a software core. Never reads a GPU framebuffer. Render-thread owned. */
class OriginalFrame {
public:
    static constexpr unsigned MAX_DIMENSION = 1024;
    std::atomic<bool> software{false};
    bool enabled = false;
    unsigned width = 0, height = 0;
    std::vector<uint8_t> bytes;

    void setEnabled(bool value) {
        enabled = value;
        if (!value) { bytes.clear(); width = height = 0; }
    }

    void capture(const void* data, unsigned w, unsigned h, size_t pitch, int format) {
        if (!data) return; // A duplicate keeps the owned previous picture, including after a lost network frame.
        software = data != RETRO_HW_FRAME_BUFFER_VALID;
        if (!software || !enabled) return;
        const size_t sourceBytes = format == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
        if (!w || !h || w > MAX_DIMENSION || h > MAX_DIMENSION || pitch < w * sourceBytes) {
            bytes.clear(); width = height = 0; return;
        }
        width = w; height = h;
        // Four little-endian dimension bytes, followed by tightly packed little-endian RGB565.
        bytes.resize(4 + static_cast<size_t>(w) * h * 2);
        bytes[0] = w; bytes[1] = w >> 8; bytes[2] = h; bytes[3] = h >> 8;
        const auto* source = static_cast<const uint8_t*>(data);
        auto* output = bytes.data() + 4;
        for (unsigned y = 0; y < h; y++) {
            for (unsigned x = 0; x < w; x++) {
                uint16_t pixel;
                if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
                    uint32_t color;
                    std::memcpy(&color, source + y * pitch + x * 4, 4);
                    pixel = ((color >> 8) & 0xf800) | ((color >> 5) & 0x07e0) | ((color >> 3) & 0x001f);
                } else {
                    std::memcpy(&pixel, source + y * pitch + x * 2, 2);
                    if (format == RETRO_PIXEL_FORMAT_0RGB1555)
                        pixel = ((pixel & 0x7c00) << 1) | ((pixel & 0x03e0) << 1) | ((pixel & 0x0200) >> 4) | (pixel & 0x001f);
                }
                *output++ = pixel; *output++ = pixel >> 8;
            }
        }
    }
};
}
#endif
