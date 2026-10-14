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

#include "vulkanhost.h"

#include <algorithm>
#include <cstring>

#include "../log.h"

namespace libretrodroid {

namespace {

// What the copy into a hardware buffer needs beyond Vulkan 1.1. A core creating the device is asked for the same.
const char* const DEVICE_EXTENSIONS[] = {
    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
    VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
};
const uint32_t DEVICE_EXTENSION_COUNT = sizeof(DEVICE_EXTENSIONS) / sizeof(DEVICE_EXTENSIONS[0]);

// What the surface a negotiating core is handed needs. Every Android device with Vulkan has both.
const char* const INSTANCE_EXTENSIONS[] = {
    VK_KHR_SURFACE_EXTENSION_NAME,
    VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
};
const uint32_t INSTANCE_EXTENSION_COUNT = sizeof(INSTANCE_EXTENSIONS) / sizeof(INSTANCE_EXTENSIONS[0]);

// Nothing is ever drawn on the surface: the core asks what it can do and creates its swapchain "on" it.
const int32_t SURFACE_SIZE = 16;

// The copy waits for the frame's own drawing, milliseconds at most; a GPU that hasn't answered by now is gone.
const uint64_t FENCE_TIMEOUT_NS = 1000000000ULL;

VkApplicationInfo hostApplicationInfo() {
    VkApplicationInfo info { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    info.pApplicationName = "LibretroDroid";
    info.pEngineName = "LibretroDroid";
    info.apiVersion = VK_API_VERSION_1_1;
    return info;
}

bool hasExtensions(VkPhysicalDevice gpu) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(gpu, nullptr, &count, nullptr) != VK_SUCCESS) return false;
    std::vector<VkExtensionProperties> available(count);
    if (vkEnumerateDeviceExtensionProperties(gpu, nullptr, &count, available.data()) != VK_SUCCESS) return false;
    return std::all_of(std::begin(DEVICE_EXTENSIONS), std::end(DEVICE_EXTENSIONS), [&](const char* wanted) {
        return std::any_of(available.begin(), available.end(), [&](const VkExtensionProperties& extension) {
            return std::strcmp(extension.extensionName, wanted) == 0;
        });
    });
}

VkImageMemoryBarrier barrier(
    VkImage image,
    VkImageSubresourceRange range,
    VkImageLayout from,
    VkImageLayout to,
    VkAccessFlags fromAccess,
    VkAccessFlags toAccess,
    uint32_t fromFamily = VK_QUEUE_FAMILY_IGNORED,
    uint32_t toFamily = VK_QUEUE_FAMILY_IGNORED
) {
    VkImageMemoryBarrier result { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
    result.srcAccessMask = fromAccess;
    result.dstAccessMask = toAccess;
    result.oldLayout = from;
    result.newLayout = to;
    result.srcQueueFamilyIndex = fromFamily;
    result.dstQueueFamilyIndex = toFamily;
    result.image = image;
    result.subresourceRange = range;
    return result;
}

}

bool VulkanHost::isSupported() {
    if (supported >= 0) return supported == 1;
    supported = createInstance(nullptr) && pickGpu() ? 1 : 0;
    // Only an answer was wanted: the instance for the game is made with what its core asks, in createDevice.
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
    instance = VK_NULL_HANDLE;
    gpu = VK_NULL_HANDLE;
    LOGI("Vulkan hardware rendering %s on this device", supported == 1 ? "is available" : "is not available");
    return supported == 1;
}

bool VulkanHost::createInstance(const VkApplicationInfo* coreInfo) {
    VkApplicationInfo application = coreInfo != nullptr ? *coreInfo : hostApplicationInfo();
    // Hardware buffers and the queue family they are handed over through are Vulkan 1.1.
    application.apiVersion = std::max(application.apiVersion, static_cast<uint32_t>(VK_API_VERSION_1_1));

    VkInstanceCreateInfo info { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    info.pApplicationInfo = &application;
    info.enabledExtensionCount = INSTANCE_EXTENSION_COUNT;
    info.ppEnabledExtensionNames = INSTANCE_EXTENSIONS;
    VkResult result = vkCreateInstance(&info, nullptr, &instance);
    if (result != VK_SUCCESS) {
        LOGE("Vulkan: vkCreateInstance failed (%d)", result);
        instance = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

bool VulkanHost::createSurface() {
    if (AImageReader_new(SURFACE_SIZE, SURFACE_SIZE, AIMAGE_FORMAT_RGBA_8888, 2, &surfaceWindow) != AMEDIA_OK) {
        surfaceWindow = nullptr;
        return false;
    }
    ANativeWindow* window = nullptr;
    if (AImageReader_getWindow(surfaceWindow, &window) != AMEDIA_OK || window == nullptr) return false;

    VkAndroidSurfaceCreateInfoKHR info { VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR };
    info.window = window;
    if (vkCreateAndroidSurfaceKHR(instance, &info, nullptr, &surface) != VK_SUCCESS) {
        surface = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

bool VulkanHost::pickGpu() {
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) != VK_SUCCESS || count == 0) return false;
    std::vector<VkPhysicalDevice> devices(count);
    if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) < VK_SUCCESS) return false;

    for (VkPhysicalDevice candidate : devices) {
        VkPhysicalDeviceProperties properties {};
        vkGetPhysicalDeviceProperties(candidate, &properties);
        if (properties.apiVersion >= VK_API_VERSION_1_1 && hasExtensions(candidate)) {
            gpu = candidate;
            return true;
        }
    }
    return false;
}

bool VulkanHost::createOwnDevice() {
    uint32_t count = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, nullptr);
    std::vector<VkQueueFamilyProperties> families(count);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &count, families.data());
    auto graphics = std::find_if(families.begin(), families.end(), [](const VkQueueFamilyProperties& family) {
        return (family.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0;
    });
    if (graphics == families.end()) return false;
    queueFamily = static_cast<uint32_t>(graphics - families.begin());

    float priority = 1.0F;
    VkDeviceQueueCreateInfo queueInfo { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo info { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queueInfo;
    info.enabledExtensionCount = DEVICE_EXTENSION_COUNT;
    info.ppEnabledExtensionNames = DEVICE_EXTENSIONS;
    if (vkCreateDevice(gpu, &info, nullptr, &device) != VK_SUCCESS) {
        device = VK_NULL_HANDLE;
        return false;
    }
    vkGetDeviceQueue(device, queueFamily, 0, &queue);
    return true;
}

bool VulkanHost::createDevice(const struct retro_hw_render_context_negotiation_interface_vulkan* negotiation) {
    if (hasDevice()) return true;

    const VkApplicationInfo* coreInfo = nullptr;
    if (negotiation != nullptr && negotiation->get_application_info != nullptr) {
        coreInfo = negotiation->get_application_info();
    }
    if (!createInstance(coreInfo) || !pickGpu()) {
        destroy();
        return false;
    }

    bool created;
    if (negotiation != nullptr && negotiation->create_device != nullptr) {
        // The core creates the device, with its own features and extensions plus the ones asked for here.
        struct retro_vulkan_context context {};
        VkPhysicalDeviceFeatures features {};
        created = createSurface() && negotiation->create_device(
            &context, instance, gpu, surface, vkGetInstanceProcAddr,
            const_cast<const char**>(DEVICE_EXTENSIONS), DEVICE_EXTENSION_COUNT, nullptr, 0, &features
        ) && context.device != VK_NULL_HANDLE;
        if (created) {
            gpu = context.gpu;
            device = context.device;
            queue = context.queue;
            queueFamily = context.queue_family_index;
            destroyCoreDevice = negotiation->destroy_device;
        }
    } else if (negotiation != nullptr && negotiation->create_device2 != nullptr) {
        // The second version of the negotiation also creates the instance; no core here asks for it.
        LOGE("Vulkan: the core only negotiates with create_device2, which is not supported");
        created = false;
    } else {
        created = createOwnDevice();
    }

    if (!created || !createFrameObjects()) {
        LOGE("Vulkan: could not create the device");
        destroy();
        return false;
    }

    renderInterface = {};
    renderInterface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    renderInterface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    renderInterface.handle = this;
    renderInterface.instance = instance;
    renderInterface.gpu = gpu;
    renderInterface.device = device;
    renderInterface.get_device_proc_addr = vkGetDeviceProcAddr;
    renderInterface.get_instance_proc_addr = vkGetInstanceProcAddr;
    renderInterface.queue = queue;
    renderInterface.queue_index = queueFamily;
    renderInterface.set_image = &setImage;
    renderInterface.get_sync_index = &getSyncIndex;
    renderInterface.get_sync_index_mask = &getSyncIndexMask;
    renderInterface.set_command_buffers = &setCommandBuffers;
    renderInterface.wait_sync_index = &waitSyncIndex;
    renderInterface.lock_queue = &lockQueue;
    renderInterface.unlock_queue = &unlockQueue;
    renderInterface.set_signal_semaphore = &setSignalSemaphore;

    supported = 1;
    LOGI("Vulkan: device ready (queue family %u)", queueFamily);
    return true;
}

bool VulkanHost::createFrameObjects() {
    getHardwareBufferProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    if (getHardwareBufferProperties == nullptr) {
        LOGE("Vulkan: the device was created without hardware buffer support");
        return false;
    }

    VkCommandPoolCreateInfo poolInfo { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    if (vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool) != VK_SUCCESS) return false;

    VkCommandBufferAllocateInfo bufferInfo { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    bufferInfo.commandPool = commandPool;
    bufferInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    bufferInfo.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(device, &bufferInfo, &commandBuffer) != VK_SUCCESS) return false;

    VkFenceCreateInfo fenceInfo { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    return vkCreateFence(device, &fenceInfo, nullptr, &fence) == VK_SUCCESS;
}

bool VulkanHost::ensureTarget(unsigned width, unsigned height) {
    if (target.buffer != nullptr && target.width == width && target.height == height) return true;
    destroyTarget();

    AHardwareBuffer_Desc description {};
    description.width = width;
    description.height = height;
    description.layers = 1;
    description.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    // Sampled by GL to draw the frame, and a GL framebuffer for whoever reads it back (captures, translation).
    description.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    if (AHardwareBuffer_allocate(&description, &target.buffer) != 0) {
        target.buffer = nullptr;
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID format { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID };
    VkAndroidHardwareBufferPropertiesANDROID properties { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID };
    properties.pNext = &format;
    if (getHardwareBufferProperties(device, target.buffer, &properties) != VK_SUCCESS || properties.memoryTypeBits == 0) {
        destroyTarget();
        return false;
    }

    VkExternalMemoryImageCreateInfo external { VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO };
    external.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imageInfo { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
    imageInfo.pNext = &external;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format.format;
    imageInfo.extent = { width, height, 1 };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vkCreateImage(device, &imageInfo, nullptr, &target.image) != VK_SUCCESS) {
        target.image = VK_NULL_HANDLE;
        destroyTarget();
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID import { VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID };
    import.buffer = target.buffer;
    VkMemoryDedicatedAllocateInfo dedicated { VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO };
    dedicated.pNext = &import;
    dedicated.image = target.image;
    VkMemoryAllocateInfo allocation { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
    allocation.pNext = &dedicated;
    allocation.allocationSize = properties.allocationSize;
    allocation.memoryTypeIndex = static_cast<uint32_t>(__builtin_ctz(properties.memoryTypeBits));
    if (vkAllocateMemory(device, &allocation, nullptr, &target.memory) != VK_SUCCESS) {
        target.memory = VK_NULL_HANDLE;
        destroyTarget();
        return false;
    }
    if (vkBindImageMemory(device, target.image, target.memory, 0) != VK_SUCCESS) {
        destroyTarget();
        return false;
    }

    target.width = width;
    target.height = height;
    target.generation++;
    return true;
}

void VulkanHost::destroyTarget() {
    if (target.image != VK_NULL_HANDLE) vkDestroyImage(device, target.image, nullptr);
    if (target.memory != VK_NULL_HANDLE) vkFreeMemory(device, target.memory, nullptr);
    // GL keeps its own reference for as long as it wraps the buffer.
    if (target.buffer != nullptr) AHardwareBuffer_release(target.buffer);
    target.image = VK_NULL_HANDLE;
    target.memory = VK_NULL_HANDLE;
    target.buffer = nullptr;
    target.width = 0;
    target.height = 0;
}

void VulkanHost::recordCopy(unsigned width, unsigned height) {
    VkImageSubresourceRange color { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
    VkImageSubresourceRange source = frame.range;
    source.levelCount = 1;
    source.layerCount = 1;

    VkCommandBufferBeginInfo begin { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(commandBuffer, &begin);

    // The core's image leaves and comes back in the layout it was handed over in. The buffer comes from whoever
    // read it last (GL, outside Vulkan) with nothing worth keeping: every pixel is written.
    VkImageMemoryBarrier before[] = {
        barrier(frame.image, source, frame.layout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_READ_BIT),
        barrier(target.image, color, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_QUEUE_FAMILY_FOREIGN_EXT, queueFamily),
    };
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 0, nullptr, 0, nullptr, 2, before);

    // A blit rather than a copy: the core's image may be BGRA or 10 bits a channel, the buffer is RGBA8.
    VkImageBlit region {};
    region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, source.baseMipLevel, source.baseArrayLayer, 1 };
    region.srcOffsets[1] = { static_cast<int32_t>(width), static_cast<int32_t>(height), 1 };
    region.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    region.dstOffsets[1] = { static_cast<int32_t>(width), static_cast<int32_t>(height), 1 };
    vkCmdBlitImage(commandBuffer, frame.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        target.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_NEAREST);

    VkImageMemoryBarrier after[] = {
        barrier(frame.image, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, frame.layout,
            VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT),
        barrier(target.image, color, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, 0, queueFamily, VK_QUEUE_FAMILY_FOREIGN_EXT),
    };
    vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 0, nullptr, 0, nullptr, 2, after);

    vkEndCommandBuffer(commandBuffer);
}

bool VulkanHost::present(unsigned width, unsigned height) {
    if (!hasDevice() || !frame.valid || width == 0 || height == 0) return false;
    // The command buffer and the fence are the last copy's until the GPU is done with it.
    finishFrame();
    if (!ensureTarget(width, height)) {
        LOGE("Vulkan: could not allocate a %ux%u frame buffer", width, height);
        return false;
    }

    recordCopy(width, height);

    // What the core left for the frontend to submit goes first, in the same batch.
    std::vector<VkCommandBuffer> commands = coreCommands;
    commands.push_back(commandBuffer);
    coreCommands.clear();
    std::vector<VkPipelineStageFlags> waitStages(frame.waits.size(), VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);

    VkSubmitInfo submit { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    submit.waitSemaphoreCount = static_cast<uint32_t>(frame.waits.size());
    submit.pWaitSemaphores = frame.waits.data();
    submit.pWaitDstStageMask = waitStages.data();
    submit.commandBufferCount = static_cast<uint32_t>(commands.size());
    submit.pCommandBuffers = commands.data();
    if (signalSemaphore != VK_NULL_HANDLE) {
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &signalSemaphore;
    }

    VkResult result;
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        result = vkQueueSubmit(queue, 1, &submit, fence);
    }
    // Semaphores are waited on once.
    frame.waits.clear();
    signalSemaphore = VK_NULL_HANDLE;
    if (result != VK_SUCCESS) {
        LOGE("Vulkan: submitting the frame copy failed (%d)", result);
        return false;
    }

    copyPending = true;
    return true;
}

void VulkanHost::finishFrame() {
    if (!copyPending) return;
    copyPending = false;
    if (vkWaitForFences(device, 1, &fence, VK_TRUE, FENCE_TIMEOUT_NS) != VK_SUCCESS) {
        LOGE("Vulkan: the GPU did not finish the frame copy");
    }
    vkResetFences(device, 1, &fence);
}

void VulkanHost::destroy() {
    if (device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device);
        destroyTarget();
        if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, nullptr);
        if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(device, commandPool, nullptr);
        // A core that created the device may ask to be the one that destroys it.
        if (destroyCoreDevice != nullptr) destroyCoreDevice(); else vkDestroyDevice(device, nullptr);
    }
    if (surface != VK_NULL_HANDLE) vkDestroySurfaceKHR(instance, surface, nullptr);
    if (surfaceWindow != nullptr) AImageReader_delete(surfaceWindow);
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);

    surface = VK_NULL_HANDLE;
    surfaceWindow = nullptr;
    instance = VK_NULL_HANDLE;
    gpu = VK_NULL_HANDLE;
    device = VK_NULL_HANDLE;
    queue = VK_NULL_HANDLE;
    queueFamily = 0;
    destroyCoreDevice = nullptr;
    commandPool = VK_NULL_HANDLE;
    commandBuffer = VK_NULL_HANDLE;
    fence = VK_NULL_HANDLE;
    getHardwareBufferProperties = nullptr;
    renderInterface = {};
    frame = Frame {};
    coreCommands.clear();
    signalSemaphore = VK_NULL_HANDLE;
    copyPending = false;
}

void VulkanHost::setImage(
    void* handle,
    const struct retro_vulkan_image* image,
    uint32_t semaphoreCount,
    const VkSemaphore* semaphores,
    uint32_t srcQueueFamily
) {
    auto host = static_cast<VulkanHost*>(handle);
    // The last image may still be the source of a copy the GPU hasn't made.
    host->finishFrame();
    host->frame = Frame {};
    // A null image withdraws the last one: the core is about to free it.
    if (image == nullptr || image->create_info.image == VK_NULL_HANDLE) return;
    host->frame.valid = true;
    host->frame.image = image->create_info.image;
    host->frame.layout = image->image_layout;
    host->frame.range = image->create_info.subresourceRange;
    if (semaphores != nullptr) host->frame.waits.assign(semaphores, semaphores + semaphoreCount);
}

// One buffer, and its copy is waited for before the next one is queued: there is never more than a frame in
// flight, so the core has a single set of per-frame resources to cycle through and nothing to wait for.
uint32_t VulkanHost::getSyncIndex(void* handle) {
    return 0;
}

uint32_t VulkanHost::getSyncIndexMask(void* handle) {
    return 1;
}

void VulkanHost::waitSyncIndex(void* handle) {
}

void VulkanHost::setCommandBuffers(void* handle, uint32_t count, const VkCommandBuffer* buffers) {
    auto host = static_cast<VulkanHost*>(handle);
    host->coreCommands.assign(buffers, buffers + count);
}

void VulkanHost::lockQueue(void* handle) {
    static_cast<VulkanHost*>(handle)->queueMutex.lock();
}

void VulkanHost::unlockQueue(void* handle) {
    static_cast<VulkanHost*>(handle)->queueMutex.unlock();
}

void VulkanHost::setSignalSemaphore(void* handle, VkSemaphore semaphore) {
    static_cast<VulkanHost*>(handle)->signalSemaphore = semaphore;
}

}

#endif
