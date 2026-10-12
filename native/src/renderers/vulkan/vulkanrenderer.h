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

#ifndef LIBRETRODROID_VULKANRENDERER_H
#define LIBRETRODROID_VULKANRENDERER_H

#if defined(__ANDROID__)

#include <memory>

#include "../renderer.h"
#include "../es3/es3utils.h"

namespace libretrodroid {

/**
 * The GL side of a Vulkan core's picture: the hardware buffer VulkanHost copies each frame into, wrapped as a
 * texture for the shader chain and as a framebuffer for whoever reads the frame back. The buffer's first row is
 * the picture's top one, as a software frame's is.
 */
class VulkanRenderer: public Renderer {
public:
    VulkanRenderer();
    ~VulkanRenderer() override;

    uintptr_t getTexture() override;
    uintptr_t getFramebuffer() override;
    void onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) override;
    void setPixelFormat(int pixelFormat) override;
    void updateRenderedResolution(unsigned int width, unsigned int height) override;
    bool rendersInVideoCallback() override;
    void setShaders(ShaderManager::Chain shaders) override;
    PassData getPassData(unsigned int layer) override;

private:
    /** Wraps the host's current buffer; false when the driver refuses it. */
    bool wrapBuffer();
    void releaseImage();

    unsigned int texture = 0;
    unsigned int framebuffer = 0;
    void* image = nullptr;
    unsigned wrappedGeneration = 0;
    bool isDirty = true;

    ShaderManager::Chain shaders;
    std::unique_ptr<ES3Utils::Framebuffers> framebuffers = std::make_unique<ES3Utils::Framebuffers>();
};

}

#endif
#endif
