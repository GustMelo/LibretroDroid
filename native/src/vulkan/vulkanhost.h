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

#ifndef LIBRETRODROID_VULKANHOST_H
#define LIBRETRODROID_VULKANHOST_H

#if defined(__ANDROID__)

#include <mutex>
#include <vector>

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include "libretro_vulkan.h"

namespace libretrodroid {

/**
 * The frontend half of libretro's Vulkan hardware rendering, for a core that draws with Vulkan (a PlayStation 2).
 *
 * The engine keeps drawing the screen with OpenGL ES: shaders, layout, captures and streams stay what they are.
 * The core hands over each frame as a Vulkan image (set_image); present() copies it into an AHardwareBuffer,
 * which the GL side samples as an ordinary texture (VulkanRenderer). One GPU copy per frame, no readback.
 *
 * The device is the core's when it negotiates one (create_device) and this host's otherwise; either way it is
 * created with the hardware-buffer extensions the copy needs, and outlives the GL surface.
 */
class VulkanHost {
public:
    static VulkanHost& getInstance() {
        static VulkanHost instance;
        return instance;
    }
    VulkanHost(VulkanHost const&) = delete;
    void operator=(VulkanHost const&) = delete;

    /** Whether this device can run a Vulkan core here: a Vulkan 1.1 GPU that shares hardware buffers. Asked once. */
    bool isSupported();

    /** Creates the instance and the device, through [negotiation] when the core set one. False when it can't. */
    bool createDevice(const struct retro_hw_render_context_negotiation_interface_vulkan* negotiation);

    bool hasDevice() const { return device != VK_NULL_HANDLE; }

    /** What RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE answers; null until there is a device. */
    const struct retro_hw_render_interface_vulkan* getInterface() const { return hasDevice() ? &renderInterface : nullptr; }

    /**
     * Copies the image the core last set, [width] x [height], into the shared buffer and waits for the GPU to
     * finish, so GL may sample it right after. False when the core set none or the copy failed.
     */
    bool present(unsigned width, unsigned height);

    /** The buffer present() fills, for GL to wrap; null before the first frame. */
    AHardwareBuffer* getBuffer() const { return target.buffer; }

    /** Changes whenever getBuffer() is a new buffer (the first frame, a new frame size). */
    unsigned getBufferGeneration() const { return target.generation; }

    /** Waits for the GPU and releases everything. Call after the core let go of the device (retro_deinit). */
    void destroy();

private:
    VulkanHost() = default;

    struct Target {
        AHardwareBuffer* buffer = nullptr;
        VkImage image = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        unsigned width = 0;
        unsigned height = 0;
        unsigned generation = 0;
    };

    struct Frame {
        bool valid = false;
        VkImage image = VK_NULL_HANDLE;
        VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImageSubresourceRange range {};
        std::vector<VkSemaphore> waits;
    };

    bool createInstance(const VkApplicationInfo* coreInfo);
    bool pickGpu();
    bool createOwnDevice();
    bool createFrameObjects();
    bool ensureTarget(unsigned width, unsigned height);
    void destroyTarget();
    void recordCopy(unsigned width, unsigned height);

    static void setImage(void* handle, const struct retro_vulkan_image* image, uint32_t semaphoreCount,
        const VkSemaphore* semaphores, uint32_t srcQueueFamily);
    static uint32_t getSyncIndex(void* handle);
    static uint32_t getSyncIndexMask(void* handle);
    static void setCommandBuffers(void* handle, uint32_t count, const VkCommandBuffer* buffers);
    static void waitSyncIndex(void* handle);
    static void lockQueue(void* handle);
    static void unlockQueue(void* handle);
    static void setSignalSemaphore(void* handle, VkSemaphore semaphore);

    int supported = -1;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    retro_vulkan_destroy_device_t destroyCoreDevice = nullptr;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getHardwareBufferProperties = nullptr;

    struct retro_hw_render_interface_vulkan renderInterface {};

    std::mutex queueMutex;
    Frame frame;
    std::vector<VkCommandBuffer> coreCommands;
    VkSemaphore signalSemaphore = VK_NULL_HANDLE;
    Target target;
};

}

#endif
#endif
