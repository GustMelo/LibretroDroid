#include "streamsurface.h"

#include <CoreVideo/CoreVideo.h>

#include <EGL/eglext.h>
#include <EGL/eglext_angle.h>
#include <GLES3/gl3.h>

#include <cstring>

#include "libretrodroid.h"
#include "log.h"

namespace libretrodroid {

namespace {
// From GL_EXT_texture_format_BGRA8888 and GL_ANGLE_texture_rectangle.
constexpr unsigned BGRA = 0x80E1;
constexpr unsigned TEXTURE_RECTANGLE = 0x84F5;
}

void StreamSurface::release(EGLDisplay display) {
    for (Slot &slot : slots) {
        if (slot.framebuffer) glDeleteFramebuffers(1, &slot.framebuffer);
        if (slot.texture) glDeleteTextures(1, &slot.texture);
        if (slot.surface != EGL_NO_SURFACE) eglDestroySurface(display, slot.surface);
        if (slot.buffer) CVPixelBufferRelease(slot.buffer);
        slot = Slot {};
    }
    width = 0;
    height = 0;
    next = 0;
}

bool StreamSurface::prepare(Slot &slot, EGLDisplay display, EGLConfig config, unsigned target) {
    const void *keys[] = { kCVPixelBufferIOSurfacePropertiesKey, kCVPixelBufferMetalCompatibilityKey };
    CFDictionaryRef empty = CFDictionaryCreate(kCFAllocatorDefault, nullptr, nullptr, 0,
                                               &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    const void *values[] = { empty, kCFBooleanTrue };
    CFDictionaryRef attributes = CFDictionaryCreate(kCFAllocatorDefault, keys, values, 2,
                                                    &kCFTypeDictionaryKeyCallBacks, &kCFTypeDictionaryValueCallBacks);
    CVReturn created = CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attributes, &slot.buffer);
    CFRelease(attributes);
    CFRelease(empty);
    IOSurfaceRef surface = created == kCVReturnSuccess ? CVPixelBufferGetIOSurface(slot.buffer) : nullptr;
    if (!surface) return false;

    const EGLint surfaceAttributes[] = {
        EGL_WIDTH, width,
        EGL_HEIGHT, height,
        EGL_IOSURFACE_PLANE_ANGLE, 0,
        EGL_TEXTURE_TARGET, target == GL_TEXTURE_2D ? EGL_TEXTURE_2D : EGL_TEXTURE_RECTANGLE_ANGLE,
        EGL_TEXTURE_INTERNAL_FORMAT_ANGLE, BGRA,
        EGL_TEXTURE_FORMAT, EGL_TEXTURE_RGBA,
        EGL_TEXTURE_TYPE_ANGLE, GL_UNSIGNED_BYTE,
        EGL_NONE,
    };
    slot.surface = eglCreatePbufferFromClientBuffer(display, EGL_IOSURFACE_ANGLE, surface, config, surfaceAttributes);
    if (slot.surface == EGL_NO_SURFACE) return false;

    glGenTextures(1, &slot.texture);
    glBindTexture(target, slot.texture);
    bool bound = eglBindTexImage(display, slot.surface, EGL_BACK_BUFFER) == EGL_TRUE;
    glBindTexture(target, 0);
    if (!bound) return false;

    glGenFramebuffers(1, &slot.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, slot.framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, target, slot.texture, 0);
    bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    return complete;
}

bool StreamSurface::prepare(EGLDisplay display, EGLConfig config, int w, int h) {
    if (w == width && h == height && slots[0].framebuffer) return true;
    release(display);
    const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (!extensions || !strstr(extensions, "EGL_ANGLE_iosurface_client_buffer")) return false;
    EGLint eglTarget = EGL_TEXTURE_2D;
    eglGetConfigAttrib(display, config, EGL_BIND_TO_TEXTURE_TARGET_ANGLE, &eglTarget);
    const unsigned target = eglTarget == EGL_TEXTURE_RECTANGLE_ANGLE ? TEXTURE_RECTANGLE : GL_TEXTURE_2D;
    width = w;
    height = h;
    for (Slot &slot : slots) {
        if (!prepare(slot, display, config, target)) {
            LOGE("stream: no IOSurface target (EGL 0x%x)", eglGetError());
            release(display);
            return false;
        }
    }
    return true;
}

bool StreamSurface::capture(EGLDisplay display, EGLConfig config, int w, int h, const Deliver &deliver) {
    if (unavailable || w <= 0 || h <= 0 || !deliver) return false;
    if (!prepare(display, config, w, h)) {
        unavailable = true;
        return false;
    }
    // A buffer the encoder still holds is not drawn over: the next free one, or no frame this time.
    for (int tried = 0; tried < SLOTS; tried++) {
        Slot &slot = slots[next];
        next = (next + 1) % SLOTS;
        if (CFGetRetainCount(slot.buffer) != 1) continue;
        LibretroDroid::getInstance().renderTo(slot.framebuffer, width, height);
        // The encoder reads the surface from another process: the picture has to be in it before it is handed over.
        glFinish();
        deliver(slot.buffer);
        break;
    }
    return true;
}

}
