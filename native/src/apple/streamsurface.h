#ifndef LIBRETRODROID_STREAMSURFACE_H
#define LIBRETRODROID_STREAMSURFACE_H

#include <EGL/egl.h>

#include <functional>

// CoreVideo's own header brings in MacTypes, whose Rect collides with the engine's.
typedef struct __CVBuffer *CVPixelBufferRef;

namespace libretrodroid {

/**
 * The frame for a stream without leaving the GPU: rendered into a BGRA pixel buffer backed by an IOSurface, which
 * the hardware encoder reads as it is. Against StreamCapture there is no read-back, no colour conversion on the CPU
 * and no frame of delay: the buffer handed over holds the frame just rendered.
 */
class StreamSurface {
public:
    using Deliver = std::function<void(CVPixelBufferRef buffer)>;

    /** False when this display cannot render into an IOSurface; the caller then falls back to StreamCapture. */
    bool capture(EGLDisplay display, EGLConfig config, int width, int height, const Deliver &deliver);
    void release(EGLDisplay display);

private:
    /** Enough for the frames an encoder holds at 60 fps; with all of them held the frame is skipped. */
    static constexpr int SLOTS = 4;

    struct Slot {
        CVPixelBufferRef buffer = nullptr;
        EGLSurface surface = EGL_NO_SURFACE;
        unsigned texture = 0;
        unsigned framebuffer = 0;
    };

    bool prepare(EGLDisplay display, EGLConfig config, int width, int height);
    bool prepare(Slot &slot, EGLDisplay display, EGLConfig config, unsigned target);

    Slot slots[SLOTS];
    int width = 0;
    int height = 0;
    int next = 0;
    bool unavailable = false;
};

}

#endif
