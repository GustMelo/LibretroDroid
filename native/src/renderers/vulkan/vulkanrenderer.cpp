/*
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#if defined(__ANDROID__)

#ifndef EGL_EGLEXT_PROTOTYPES
#define EGL_EGLEXT_PROTOTYPES
#endif
#ifndef GL_GLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#endif

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>

#include "vulkanrenderer.h"

#include "../../libretro-common/include/libretro.h"
#include "../../log.h"
#include "../../vulkan/vulkanhost.h"

namespace libretrodroid {

VulkanRenderer::VulkanRenderer() {
    glGenTextures(1, &texture);
    glGenFramebuffers(1, &framebuffer);
}

VulkanRenderer::~VulkanRenderer() {
    for (auto& pass : *framebuffers) {
        ES3Utils::deleteFramebuffer(std::move(pass));
    }
    glDeleteFramebuffers(1, &framebuffer);
    glDeleteTextures(1, &texture);
    releaseImage();
}

void VulkanRenderer::releaseImage() {
    if (image != nullptr) eglDestroyImageKHR(eglGetCurrentDisplay(), static_cast<EGLImageKHR>(image));
    image = nullptr;
}

void VulkanRenderer::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    // A Vulkan core has no pixels to hand over: anything but the marker for "the image I set" is not a frame.
    if (data != RETRO_HW_FRAME_BUFFER_VALID) return;

    VulkanHost& host = VulkanHost::getInstance();
    if (!host.present(width, height)) return;

    bool resized = host.getBufferGeneration() != wrappedGeneration;
    if (resized && !wrapBuffer()) return;
    if (resized || isDirty) {
        for (auto& pass : *framebuffers) {
            ES3Utils::deleteFramebuffer(std::move(pass));
        }
        framebuffers = ES3Utils::buildShaderPasses(width, height, shaders);
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, shaders.linearTexture ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, shaders.linearTexture ? GL_LINEAR : GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        isDirty = false;
    }

    Renderer::onNewFrame(data, width, height, pitch);
}

bool VulkanRenderer::wrapBuffer() {
    VulkanHost& host = VulkanHost::getInstance();
    releaseImage();

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(host.getBuffer());
    const EGLint attributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR created = eglCreateImageKHR(
        eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attributes);
    if (created == EGL_NO_IMAGE_KHR) {
        LOGE("Vulkan: GL could not wrap the frame buffer (EGL error 0x%x)", eglGetError());
        return false;
    }
    image = created;

    // A texture bound to an image can't be bound to another: each buffer gets a fresh one.
    glDeleteTextures(1, &texture);
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, static_cast<GLeglImageOES>(image));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    GLint bound = 0;
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &bound);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(bound));

    wrappedGeneration = host.getBufferGeneration();
    return true;
}

// Whoever asks is about to sample or read the buffer: the frame must have reached it.
uintptr_t VulkanRenderer::getTexture() {
    VulkanHost::getInstance().finishFrame();
    return texture;
}

uintptr_t VulkanRenderer::getFramebuffer() {
    VulkanHost::getInstance().finishFrame();
    return framebuffer;
}

void VulkanRenderer::setPixelFormat(int pixelFormat) {}

void VulkanRenderer::updateRenderedResolution(unsigned int width, unsigned int height) {}

bool VulkanRenderer::rendersInVideoCallback() {
    return false;
}

void VulkanRenderer::setShaders(ShaderManager::Chain newShaders) {
    shaders = std::move(newShaders);
    isDirty = true;
}

Renderer::PassData VulkanRenderer::getPassData(unsigned int layer) {
    PassData result;

    if (layer < framebuffers->size()) {
        result.framebuffer = framebuffers->at(layer)->framebuffer;
        result.width = framebuffers->at(layer)->width;
        result.height = framebuffers->at(layer)->height;
    }

    if (layer > 0 && layer < framebuffers->size() + 1) {
        result.texture = framebuffers->at(layer - 1)->texture;
    }

    return result;
}

}

#endif
