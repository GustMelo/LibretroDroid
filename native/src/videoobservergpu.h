#ifndef LIBRETRODROID_VIDEOOBSERVERGPU_H
#define LIBRETRODROID_VIDEOOBSERVERGPU_H
#include <GLES3/gl3.h>
#include "videoobservation.h"

namespace libretrodroid {
/** One readback in flight. A zero-time fence poll prevents mapping an unfinished GPU transfer. */
class VideoObserverGpu {
public:
    ~VideoObserverGpu() { release(); }
    void capture(unsigned framebuffer, unsigned w, unsigned h, float aspect, unsigned rotation, bool bottomUp) {
        GLint previousBuffer = 0, previousFramebuffer = 0, alignment = 0;
        glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &previousBuffer);
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, &previousFramebuffer);
        glGetIntegerv(GL_PACK_ALIGNMENT, &alignment);
        if (fence) {
            const auto ready = glClientWaitSync(fence, 0, 0);
            if (ready == GL_ALREADY_SIGNALED || ready == GL_CONDITION_SATISFIED) {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
                auto* pixels = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, static_cast<GLsizeiptr>(width) * height * 4, GL_MAP_READ_BIT);
                if (pixels) {
                    VideoObservation::instance().publish(pixels, width, height, width * 4,
                        VideoObservation::RGBA8888, savedAspect, savedRotation, savedBottomUp);
                    glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                }
                glDeleteSync(fence);
                fence = nullptr;
            } else if (ready == GL_WAIT_FAILED) {
                glDeleteSync(fence);
                fence = nullptr;
            }
        }
        if (!fence && VideoObservation::instance().takeRequest()) {
            if (!buffer) glGenBuffers(1, &buffer);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
            glBufferData(GL_PIXEL_PACK_BUFFER, static_cast<GLsizeiptr>(w) * h * 4, nullptr, GL_STREAM_READ);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glPixelStorei(GL_PACK_ALIGNMENT, 1);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
            fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            glFlush();
            width = w; height = h; savedAspect = aspect; savedRotation = rotation; savedBottomUp = bottomUp;
        }
        glPixelStorei(GL_PACK_ALIGNMENT, alignment);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, previousBuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, previousFramebuffer);
    }
    void release() {
        if (fence) glDeleteSync(fence);
        if (buffer) glDeleteBuffers(1, &buffer);
        fence = nullptr; buffer = 0;
    }
private:
    GLuint buffer = 0;
    GLsync fence = nullptr;
    unsigned width = 0, height = 0, savedRotation = 0;
    float savedAspect = 1;
    bool savedBottomUp = false;
};
}
#endif
