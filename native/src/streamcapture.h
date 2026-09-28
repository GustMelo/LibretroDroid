#ifndef LIBRETRODROID_STREAMCAPTURE_H
#define LIBRETRODROID_STREAMCAPTURE_H

#include <cstdint>
#include <functional>

namespace libretrodroid {

/**
 * Renders the game a second time into an offscreen framebuffer and reads it back through two pixel buffers, one
 * frame behind, so the GPU never stalls the render thread. The screen keeps playing. GL thread only; shared by
 * the Android (JNI) and Apple (C) bindings.
 */
class StreamCapture {
public:
    /** RGBA rows, top row first; the pointer is only valid during the call. */
    using Deliver = std::function<void(const uint8_t *pixels, int width, int height)>;

    /** Renders the current frame at [width] x [height] (letterboxed) and delivers the previous one. */
    void capture(int width, int height, const Deliver &deliver);
    void release();

private:
    bool prepare(int width, int height);

    unsigned framebuffer = 0;
    unsigned color = 0;
    unsigned pixels[2] {0, 0};
    bool filled[2] {false, false};
    int width = 0;
    int height = 0;
    int index = 0;
};

}

#endif
