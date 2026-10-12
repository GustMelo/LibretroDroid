#include "../src/originalframe.h"
#include <cassert>

using libretrodroid::OriginalFrame;
int main() {
    OriginalFrame frame;
    const uint16_t rgb565[] {0xf800, 0x07e0, 0xdead, 0x001f, 0xffff, 0xbeef};
    frame.capture(rgb565, 2, 2, 6, RETRO_PIXEL_FORMAT_RGB565);
    assert(frame.software && frame.bytes.empty()); // Observing support alone never copies pixels.
    frame.setEnabled(true);
    frame.capture(rgb565, 2, 2, 6, RETRO_PIXEL_FORMAT_RGB565);
    assert(frame.bytes.size() == 12 && frame.bytes[0] == 2 && frame.bytes[2] == 2);
    assert(frame.bytes[4] == 0 && frame.bytes[5] == 0xf8 && frame.bytes[8] == 0x1f && frame.bytes[9] == 0);
    const auto owned = frame.bytes;
    frame.capture(nullptr, 2, 2, 6, RETRO_PIXEL_FORMAT_RGB565);
    assert(frame.bytes == owned);
    frame.capture(RETRO_HW_FRAME_BUFFER_VALID, 2, 2, 6, RETRO_PIXEL_FORMAT_RGB565);
    assert(!frame.software);
    const uint32_t xrgb[] {0x00ff0000, 0x0000ff00, 0x000000ff};
    frame.capture(xrgb, 3, 1, 12, RETRO_PIXEL_FORMAT_XRGB8888);
    assert(frame.bytes[4] == 0 && frame.bytes[5] == 0xf8 && frame.bytes[6] == 0xe0 && frame.bytes[7] == 7);
    assert(frame.bytes[8] == 0x1f && frame.bytes[9] == 0);
    const uint16_t rgb1555[] {0x7c00, 0x03e0, 0x001f};
    frame.capture(rgb1555, 3, 1, 6, RETRO_PIXEL_FORMAT_0RGB1555);
    assert(frame.bytes[5] == 0xf8 && frame.bytes[6] == 0xe0 && frame.bytes[7] == 7);
    assert(rgb1555[0] == 0x7c00); // Never mutate core-owned memory.
    frame.capture(rgb565, 1025, 1, 2050, RETRO_PIXEL_FORMAT_RGB565);
    assert(frame.bytes.empty());
    frame.setEnabled(false);
    assert(frame.bytes.empty());
}
