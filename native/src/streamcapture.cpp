#include "streamcapture.h"

#include <GLES3/gl3.h>

#include "libretrodroid.h"

namespace libretrodroid {

void StreamCapture::release() {
    if (framebuffer) glDeleteFramebuffers(1, &framebuffer);
    if (color) glDeleteTextures(1, &color);
    if (pixels[0]) glDeleteBuffers(2, pixels);
    *this = StreamCapture {};
}

bool StreamCapture::prepare(int w, int h) {
    if (w == width && h == height && framebuffer) return true;
    release();
    glGenTextures(1, &color);
    glBindTexture(GL_TEXTURE_2D, color);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glBindTexture(GL_TEXTURE_2D, 0);
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
    bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glGenBuffers(2, pixels);
    for (GLuint buffer : pixels) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, buffer);
        glBufferData(GL_PIXEL_PACK_BUFFER, static_cast<GLsizeiptr>(w) * h * 4, nullptr, GL_STREAM_READ);
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    width = w;
    height = h;
    if (!complete) release();
    return complete;
}

void StreamCapture::capture(int w, int h, const Deliver &deliver) {
    if (w <= 0 || h <= 0 || !deliver || !prepare(w, h)) return;
    LibretroDroid::getInstance().renderTo(framebuffer, width, height);
    const GLsizeiptr size = static_cast<GLsizeiptr>(width) * height * 4;
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pixels[index]);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    filled[index] = true;
    const int previous = index ^ 1;
    if (filled[previous]) {
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pixels[previous]);
        auto *data = static_cast<const uint8_t *>(glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, size, GL_MAP_READ_BIT));
        if (data) {
            deliver(data, width, height);
            glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        }
    }
    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    index = previous;
}

}
