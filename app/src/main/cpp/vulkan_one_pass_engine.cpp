#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <deque>
#include <mutex>
#include <limits>
#include <optional>
#include <string>
#include <vector>

namespace atmo::vulkan {
namespace {

constexpr char kLogTag[] = "AtmoVulkan";
constexpr uint32_t kMaximumTextureBindings = 8;
constexpr uint32_t kMaximumPushConstantBytes = 128;
constexpr uint32_t kVulkanApi14 =
    VK_MAKE_API_VERSION(0, 1, 4, 0);
constexpr std::array<uint32_t, 4> kSupportedCoreApiVersions{
    kVulkanApi14,
    VK_API_VERSION_1_3,
    VK_API_VERSION_1_2,
    VK_API_VERSION_1_1
};

// Every native failure funnels through logError, so it is also the one place
// worth capturing from. logcat is fine for development, but the whole point of
// the in-app diagnostics screen is that a user hitting a Vulkan fallback on a
// device we do not have can read the actual reason without adb.
std::mutex& diagnosticsMutex() {
    static std::mutex mutex;
    return mutex;
}

std::deque<std::string>& diagnosticsBuffer() {
    static std::deque<std::string> buffer;
    return buffer;
}

constexpr size_t kMaximumDiagnosticEntries = 64;

void logError(const std::string& message) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", message.c_str());
    std::lock_guard<std::mutex> guard(diagnosticsMutex());
    std::deque<std::string>& buffer = diagnosticsBuffer();
    buffer.push_back(message);
    while (buffer.size() > kMaximumDiagnosticEntries) {
        buffer.pop_front();
    }
}

bool hasExtension(
    const std::vector<VkExtensionProperties>& extensions,
    const char* required
) {
    return std::any_of(
        extensions.begin(),
        extensions.end(),
        [required](const VkExtensionProperties& extension) {
            return std::strcmp(extension.extensionName, required) == 0;
        }
    );
}

std::vector<VkExtensionProperties> instanceExtensions() {
    uint32_t count = 0;
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr) != VK_SUCCESS) {
        return {};
    }
    std::vector<VkExtensionProperties> result(count);
    if (count > 0 &&
        vkEnumerateInstanceExtensionProperties(nullptr, &count, result.data()) != VK_SUCCESS) {
        return {};
    }
    result.resize(count);
    return result;
}

std::vector<VkExtensionProperties> deviceExtensions(VkPhysicalDevice device) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) {
        return {};
    }
    std::vector<VkExtensionProperties> result(count);
    if (count > 0 &&
        vkEnumerateDeviceExtensionProperties(
            device,
            nullptr,
            &count,
            result.data()
        ) != VK_SUCCESS) {
        return {};
    }
    result.resize(count);
    return result;
}

uint32_t supportedCoreApiVersion(uint32_t advertisedVersion) {
    if (VK_API_VERSION_VARIANT(advertisedVersion) != 0) {
        return 0;
    }
    for (uint32_t supported : kSupportedCoreApiVersions) {
        if (advertisedVersion >= supported) return supported;
    }
    return 0;
}

uint32_t loaderCoreApiVersion() {
    auto enumerateVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion")
    );
    if (enumerateVersion == nullptr) return 0;
    uint32_t version = VK_API_VERSION_1_0;
    if (enumerateVersion(&version) != VK_SUCCESS) return 0;
    return supportedCoreApiVersion(version);
}

uint32_t probeVulkanRuntime() {
    const uint32_t loaderVersion = loaderCoreApiVersion();
    if (loaderVersion < VK_API_VERSION_1_1) return 0;
    const auto extensions = instanceExtensions();
    if (!hasExtension(extensions, VK_KHR_SURFACE_EXTENSION_NAME) ||
        !hasExtension(extensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
        return 0;
    }

    VkApplicationInfo applicationInfo{
        VK_STRUCTURE_TYPE_APPLICATION_INFO
    };
    applicationInfo.pApplicationName = "Atmo Engine probe";
    applicationInfo.applicationVersion = 1;
    applicationInfo.pEngineName = "Atmo Engine";
    applicationInfo.engineVersion = 1;
    applicationInfo.apiVersion = loaderVersion;

    VkInstanceCreateInfo instanceInfo{
        VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
    };
    instanceInfo.pApplicationInfo = &applicationInfo;

    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&instanceInfo, nullptr, &instance) != VK_SUCCESS) {
        return 0;
    }

    uint32_t deviceCount = 0;
    uint32_t supportedVersion = 0;
    if (vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr) == VK_SUCCESS &&
        deviceCount > 0) {
        std::vector<VkPhysicalDevice> devices(deviceCount);
        if (vkEnumeratePhysicalDevices(
                instance,
                &deviceCount,
                devices.data()
            ) == VK_SUCCESS) {
            for (VkPhysicalDevice device : devices) {
                VkPhysicalDeviceProperties properties{};
                vkGetPhysicalDeviceProperties(device, &properties);
                const uint32_t deviceVersion =
                    supportedCoreApiVersion(properties.apiVersion);
                if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ||
                    deviceVersion < VK_API_VERSION_1_1) {
                    continue;
                }
                const auto availableDeviceExtensions = deviceExtensions(device);
                if (!hasExtension(
                        availableDeviceExtensions,
                        VK_KHR_SWAPCHAIN_EXTENSION_NAME
                    )) {
                    continue;
                }

                uint32_t queueCount = 0;
                vkGetPhysicalDeviceQueueFamilyProperties(
                    device,
                    &queueCount,
                    nullptr
                );
                std::vector<VkQueueFamilyProperties> queues(queueCount);
                vkGetPhysicalDeviceQueueFamilyProperties(
                    device,
                    &queueCount,
                    queues.data()
                );
                const bool hasGraphicsQueue = std::any_of(
                    queues.begin(),
                    queues.end(),
                    [](const VkQueueFamilyProperties& queue) {
                        return queue.queueCount > 0 &&
                            (queue.queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0;
                    }
                );
                if (hasGraphicsQueue) {
                    supportedVersion = std::max(
                        supportedVersion,
                        std::min(loaderVersion, deviceVersion)
                    );
                }
            }
        }
    }

    vkDestroyInstance(instance, nullptr);
    return supportedVersion;
}

struct TextureResource {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t mipLevels = 1;
    bool ready = false;
};

class OnePassEngineImpl {
public:
    OnePassEngineImpl(
        JNIEnv* env,
        jobject assetManager,
        const OnePassConfig& config
    ) : assets_(AAssetManager_fromJava(env, assetManager)),
        // A raw AAssetManager* is only guaranteed valid for as long as the
        // Java AssetManager object backing it is reachable. This engine
        // reuses assets_ across every setSurface() call for its entire
        // lifetime (see createPipeline(), called on every swapchain
        // rebuild) -- so without something keeping that Java object
        // rooted, it can become eligible for GC shortly after this
        // constructor returns, leaving assets_ dangling. A JNI global
        // reference keeps it alive for exactly as long as this engine
        // needs it; released in releaseAssetManagerRef().
        assetManagerRef_(
            assetManager == nullptr ? nullptr : env->NewGlobalRef(assetManager)
        ),
        label_(config.label == nullptr ? "effect" : config.label),
        vertexShaderAsset_(
            config.vertexShaderAsset == nullptr
                ? ""
                : config.vertexShaderAsset
        ),
        fragmentShaderAsset_(
            config.fragmentShaderAsset == nullptr
                ? ""
                : config.fragmentShaderAsset
        ),
        optionalTextureMask_(config.optionalTextureMask),
        uniformBinding_(config.uniformBinding),
        uniformData_(config.uniformSize, 0),
        mipmappedTextureMask_(config.mipmappedTextureMask),
        pushConstants_(config.pushConstantSize, 0),
        textures_(config.textureBindingCount) {
    }

    ~OnePassEngineImpl() {
        destroySurface();
    }

    // Releases the JNI global reference retained in the constructor. Must
    // be called (with a valid JNIEnv for the calling thread) before this
    // engine is deleted; the destructor can't do this itself since global
    // ref deletion requires a JNIEnv, which isn't available there.
    void releaseAssetManagerRef(JNIEnv* env) {
        if (assetManagerRef_ != nullptr && env != nullptr) {
            env->DeleteGlobalRef(assetManagerRef_);
        }
        assetManagerRef_ = nullptr;
        assets_ = nullptr;
    }

    bool isConfigured() const {
        return assets_ != nullptr &&
            !vertexShaderAsset_.empty() &&
            !fragmentShaderAsset_.empty() &&
            !textures_.empty() &&
            textures_.size() <= kMaximumTextureBindings &&
            pushConstants_.size() <= kMaximumPushConstantBytes &&
            pushConstants_.size() % sizeof(uint32_t) == 0 &&
            (optionalTextureMask_ >> textures_.size()) == 0 &&
            (mipmappedTextureMask_ >> textures_.size()) == 0 &&
            (
                (
                    uniformBinding_ == kNoUniformBinding &&
                    uniformData_.empty()
                ) ||
                (
                    uniformBinding_ != kNoUniformBinding &&
                    uniformBinding_ >= textures_.size() &&
                    !uniformData_.empty()
                )
            );
    }

    bool setSurface(
        JNIEnv* env,
        jobject javaSurface,
        uint32_t requestedWidth,
        uint32_t requestedHeight
    ) {
        destroySurface();
        window_ = ANativeWindow_fromSurface(env, javaSurface);
        if (window_ == nullptr) {
            logError("ANativeWindow_fromSurface returned null");
            return false;
        }
        requestedWidth_ = requestedWidth;
        requestedHeight_ = requestedHeight;

        if (!createNegotiatedInstanceAndSelectDevice() ||
            !createDevice() ||
            !createSwapchain() ||
            !createDescriptorResources() ||
            !createRenderPass() ||
            !createPipeline() ||
            !createFramebuffers() ||
            !createCommandResources() ||
            !createSyncResources()) {
            destroySurface();
            return false;
        }
        for (uint32_t binding = 0;
             binding < textures_.size();
             ++binding) {
            const uint32_t bindingBit = 1U << binding;
            if ((optionalTextureMask_ & bindingBit) != 0 &&
                !clearTexture(binding)) {
                logError(
                    label_ + " surface setup failed: clearing optional texture binding " +
                    std::to_string(binding)
                );
                destroySurface();
                return false;
            }
        }
        return true;
    }

    uint32_t apiVersion() const {
        return apiVersion_;
    }

    float surfaceAspectRatio() const {
        return extent_.height == 0
            ? 1.0F
            : static_cast<float>(extent_.width) /
                static_cast<float>(extent_.height);
    }

    void destroySurface() {
        if (device_ != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device_);
            for (TextureResource& texture : textures_) {
                destroyTexture(texture);
            }
            if (uniformBuffer_ != VK_NULL_HANDLE) {
                vkDestroyBuffer(device_, uniformBuffer_, nullptr);
            }
            if (uniformMemory_ != VK_NULL_HANDLE) {
                vkFreeMemory(device_, uniformMemory_, nullptr);
            }
            if (imageAvailable_ != VK_NULL_HANDLE) {
                vkDestroySemaphore(device_, imageAvailable_, nullptr);
            }
            for (VkSemaphore semaphore : renderFinishedSemaphores_) {
                if (semaphore != VK_NULL_HANDLE) {
                    vkDestroySemaphore(device_, semaphore, nullptr);
                }
            }
            if (renderFence_ != VK_NULL_HANDLE) {
                vkDestroyFence(device_, renderFence_, nullptr);
            }
            if (commandPool_ != VK_NULL_HANDLE) {
                vkDestroyCommandPool(device_, commandPool_, nullptr);
            }
            for (VkFramebuffer framebuffer : framebuffers_) {
                vkDestroyFramebuffer(device_, framebuffer, nullptr);
            }
            if (pipeline_ != VK_NULL_HANDLE) {
                vkDestroyPipeline(device_, pipeline_, nullptr);
            }
            if (pipelineLayout_ != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
            }
            if (renderPass_ != VK_NULL_HANDLE) {
                vkDestroyRenderPass(device_, renderPass_, nullptr);
            }
            if (descriptorPool_ != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
            }
            if (descriptorSetLayout_ != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(
                    device_,
                    descriptorSetLayout_,
                    nullptr
                );
            }
            for (VkImageView imageView : swapchainImageViews_) {
                vkDestroyImageView(device_, imageView, nullptr);
            }
            if (swapchain_ != VK_NULL_HANDLE) {
                vkDestroySwapchainKHR(device_, swapchain_, nullptr);
            }
            vkDestroyDevice(device_, nullptr);
        }
        if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
        }
        if (instance_ != VK_NULL_HANDLE) {
            vkDestroyInstance(instance_, nullptr);
        }
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
        }

        window_ = nullptr;
        instance_ = VK_NULL_HANDLE;
        surface_ = VK_NULL_HANDLE;
        physicalDevice_ = VK_NULL_HANDLE;
        device_ = VK_NULL_HANDLE;
        instanceApiVersion_ = 0;
        apiVersion_ = 0;
        queue_ = VK_NULL_HANDLE;
        swapchain_ = VK_NULL_HANDLE;
        swapchainImages_.clear();
        swapchainImageViews_.clear();
        framebuffers_.clear();
        descriptorSetLayout_ = VK_NULL_HANDLE;
        descriptorPool_ = VK_NULL_HANDLE;
        descriptorSet_ = VK_NULL_HANDLE;
        renderPass_ = VK_NULL_HANDLE;
        pipelineLayout_ = VK_NULL_HANDLE;
        pipeline_ = VK_NULL_HANDLE;
        commandPool_ = VK_NULL_HANDLE;
        commandBuffer_ = VK_NULL_HANDLE;
        imageAvailable_ = VK_NULL_HANDLE;
        renderFinishedSemaphores_.clear();
        renderFence_ = VK_NULL_HANDLE;
        uniformBuffer_ = VK_NULL_HANDLE;
        uniformMemory_ = VK_NULL_HANDLE;
        extent_ = {};
    }

    bool uploadBitmap(
        JNIEnv* env,
        jobject bitmap,
        uint32_t binding
    ) {
        if (device_ == VK_NULL_HANDLE || commandPool_ == VK_NULL_HANDLE) {
            return false;
        }
        if (binding >= textures_.size()) {
            logError(label_ + " texture binding is out of range");
            return false;
        }

        AndroidBitmapInfo bitmapInfo{};
        if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
            bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            bitmapInfo.width == 0 ||
            bitmapInfo.height == 0) {
            logError(label_ + " requires an RGBA_8888 bitmap");
            return false;
        }

        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physicalDevice_, &properties);
        if (bitmapInfo.width > properties.limits.maxImageDimension2D ||
            bitmapInfo.height > properties.limits.maxImageDimension2D) {
            logError("Wallpaper texture exceeds maxImageDimension2D");
            return false;
        }

        const VkDeviceSize rowSize =
            static_cast<VkDeviceSize>(bitmapInfo.width) * 4U;
        const VkDeviceSize bufferSize =
            rowSize * static_cast<VkDeviceSize>(bitmapInfo.height);
        VkBuffer stagingBuffer = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
        if (!createBuffer(
                bufferSize,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                stagingBuffer,
                stagingMemory
            )) {
            return false;
        }

        void* sourcePixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &sourcePixels) !=
            ANDROID_BITMAP_RESULT_SUCCESS) {
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            vkFreeMemory(device_, stagingMemory, nullptr);
            return false;
        }

        void* mapped = nullptr;
        bool copied = vkMapMemory(
            device_,
            stagingMemory,
            0,
            bufferSize,
            0,
            &mapped
        ) == VK_SUCCESS;
        if (copied) {
            const auto* source = static_cast<const uint8_t*>(sourcePixels);
            auto* destination = static_cast<uint8_t*>(mapped);
            for (uint32_t row = 0; row < bitmapInfo.height; ++row) {
                std::memcpy(
                    destination + row * rowSize,
                    source + row * bitmapInfo.stride,
                    static_cast<size_t>(rowSize)
                );
            }
            vkUnmapMemory(device_, stagingMemory);
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        if (!copied) {
            vkDestroyBuffer(device_, stagingBuffer, nullptr);
            vkFreeMemory(device_, stagingMemory, nullptr);
            return false;
        }

        const bool installed = installStagedTexture(
            stagingBuffer,
            bitmapInfo.width,
            bitmapInfo.height,
            binding
        );
        vkDestroyBuffer(device_, stagingBuffer, nullptr);
        vkFreeMemory(device_, stagingMemory, nullptr);
        return installed;
    }

    bool clearTexture(uint32_t binding) {
        if (device_ == VK_NULL_HANDLE || commandPool_ == VK_NULL_HANDLE) {
            return false;
        }
        if (binding >= textures_.size()) return false;
        constexpr std::array<uint8_t, 4> transparentBlack{0, 0, 0, 255};
        VkBuffer stagingBuffer = VK_NULL_HANDLE;
        VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
        if (!createBuffer(
                transparentBlack.size(),
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                stagingBuffer,
                stagingMemory
            )) {
            return false;
        }
        void* mapped = nullptr;
        const bool mappedSuccessfully = vkMapMemory(
            device_,
            stagingMemory,
            0,
            transparentBlack.size(),
            0,
            &mapped
        ) == VK_SUCCESS;
        if (mappedSuccessfully) {
            std::memcpy(
                mapped,
                transparentBlack.data(),
                transparentBlack.size()
            );
            vkUnmapMemory(device_, stagingMemory);
        }
        const bool installed = mappedSuccessfully && installStagedTexture(
            stagingBuffer,
            1,
            1,
            binding
        );
        vkDestroyBuffer(device_, stagingBuffer, nullptr);
        vkFreeMemory(device_, stagingMemory, nullptr);
        return installed;
    }

    bool setPushConstants(const void* data, size_t size) {
        if (data == nullptr || size != pushConstants_.size()) return false;
        std::memcpy(pushConstants_.data(), data, size);
        return true;
    }

    bool setUniformData(const void* data, size_t size) {
        if (data == nullptr ||
            size != uniformData_.size() ||
            uniformMemory_ == VK_NULL_HANDLE) {
            return false;
        }
        if (renderFence_ != VK_NULL_HANDLE &&
            vkWaitForFences(
                device_,
                1,
                &renderFence_,
                VK_TRUE,
                std::numeric_limits<uint64_t>::max()
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkWaitForFences");
            return false;
        }
        void* mapped = nullptr;
        if (vkMapMemory(
                device_,
                uniformMemory_,
                0,
                size,
                0,
                &mapped
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkMapMemory");
            return false;
        }
        std::memcpy(mapped, data, size);
        vkUnmapMemory(device_, uniformMemory_);
        if (data != uniformData_.data()) {
            std::memcpy(uniformData_.data(), data, size);
        }
        return true;
    }

    int render() {
        if (device_ == VK_NULL_HANDLE ||
            swapchain_ == VK_NULL_HANDLE) {
            return -1;
        }
        const bool descriptorsReady = std::all_of(
            textures_.begin(),
            textures_.end(),
            [](const TextureResource& texture) {
                return texture.ready;
            }
        );
        if (!descriptorsReady) return -1;
        if (vkWaitForFences(
                device_,
                1,
                &renderFence_,
                VK_TRUE,
                std::numeric_limits<uint64_t>::max()
            ) != VK_SUCCESS) {
            return -1;
        }

        uint32_t imageIndex = 0;
        const VkResult acquireResult = vkAcquireNextImageKHR(
            device_,
            swapchain_,
            std::numeric_limits<uint64_t>::max(),
            imageAvailable_,
            VK_NULL_HANDLE,
            &imageIndex
        );
        if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) return 1;
        if (acquireResult != VK_SUCCESS &&
            acquireResult != VK_SUBOPTIMAL_KHR) {
            return -1;
        }
        if (imageIndex >= framebuffers_.size() ||
            imageIndex >= renderFinishedSemaphores_.size()) {
            return -1;
        }
        const VkSemaphore renderFinished =
            renderFinishedSemaphores_[imageIndex];
        if (renderFinished == VK_NULL_HANDLE) return -1;

        if (vkResetFences(device_, 1, &renderFence_) != VK_SUCCESS ||
            vkResetCommandBuffer(commandBuffer_, 0) != VK_SUCCESS ||
            !recordRenderCommands(imageIndex)) {
            return -1;
        }

        constexpr VkPipelineStageFlags waitStage =
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submitInfo.waitSemaphoreCount = 1;
        submitInfo.pWaitSemaphores = &imageAvailable_;
        submitInfo.pWaitDstStageMask = &waitStage;
        submitInfo.commandBufferCount = 1;
        submitInfo.pCommandBuffers = &commandBuffer_;
        submitInfo.signalSemaphoreCount = 1;
        submitInfo.pSignalSemaphores = &renderFinished;
        if (vkQueueSubmit(queue_, 1, &submitInfo, renderFence_) != VK_SUCCESS) {
            return -1;
        }

        VkPresentInfoKHR presentInfo{
            VK_STRUCTURE_TYPE_PRESENT_INFO_KHR
        };
        presentInfo.waitSemaphoreCount = 1;
        presentInfo.pWaitSemaphores = &renderFinished;
        presentInfo.swapchainCount = 1;
        presentInfo.pSwapchains = &swapchain_;
        presentInfo.pImageIndices = &imageIndex;
        const VkResult presentResult = vkQueuePresentKHR(queue_, &presentInfo);
        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR) return 1;
        if (presentResult != VK_SUCCESS &&
            presentResult != VK_SUBOPTIMAL_KHR) {
            return -1;
        }
        return 0;
    }

private:
    bool createNegotiatedInstanceAndSelectDevice() {
        const uint32_t loaderVersion = loaderCoreApiVersion();
        if (loaderVersion < VK_API_VERSION_1_1) {
            logError("Vulkan 1.1 loader is unavailable");
            return false;
        }

        if (!createInstance(VK_API_VERSION_1_1) ||
            !createAndroidSurface() ||
            !selectPhysicalDevice(loaderVersion)) {
            destroyNegotiationInstance();
            logError(label_ + " surface setup failed: instance/surface/device selection");
            return false;
        }

        const uint32_t negotiatedVersion = apiVersion_;
        if (negotiatedVersion == VK_API_VERSION_1_1) {
            return true;
        }

        destroyNegotiationInstance();
        if (!createInstance(negotiatedVersion) ||
            !createAndroidSurface() ||
            !selectPhysicalDevice(negotiatedVersion) ||
            apiVersion_ != negotiatedVersion) {
            destroyNegotiationInstance();
            logError("The negotiated Vulkan API version became unavailable");
            return false;
        }
        return true;
    }

    void destroyNegotiationInstance() {
        if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
        }
        if (instance_ != VK_NULL_HANDLE) {
            vkDestroyInstance(instance_, nullptr);
        }
        instance_ = VK_NULL_HANDLE;
        surface_ = VK_NULL_HANDLE;
        physicalDevice_ = VK_NULL_HANDLE;
        instanceApiVersion_ = 0;
        apiVersion_ = 0;
        queueFamily_ = 0;
    }

    bool createInstance(uint32_t requestedApiVersion) {
        const uint32_t loaderVersion = loaderCoreApiVersion();
        if (requestedApiVersion < VK_API_VERSION_1_1 ||
            requestedApiVersion > loaderVersion ||
            supportedCoreApiVersion(requestedApiVersion) != requestedApiVersion) {
            logError("Unsupported Vulkan instance API version requested");
            return false;
        }
        const auto extensions = instanceExtensions();
        if (!hasExtension(extensions, VK_KHR_SURFACE_EXTENSION_NAME) ||
            !hasExtension(extensions, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
            logError("Required Android Vulkan surface extensions are unavailable");
            return false;
        }
        const std::array<const char*, 2> requiredExtensions{
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
        };

        VkApplicationInfo applicationInfo{
            VK_STRUCTURE_TYPE_APPLICATION_INFO
        };
        applicationInfo.pApplicationName = "Atmo Engine";
        applicationInfo.applicationVersion = 1;
        applicationInfo.pEngineName = label_.c_str();
        applicationInfo.engineVersion = 1;
        applicationInfo.apiVersion = requestedApiVersion;

        VkInstanceCreateInfo createInfo{
            VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO
        };
        createInfo.pApplicationInfo = &applicationInfo;
        createInfo.enabledExtensionCount =
            static_cast<uint32_t>(requiredExtensions.size());
        createInfo.ppEnabledExtensionNames = requiredExtensions.data();
        if (vkCreateInstance(&createInfo, nullptr, &instance_) != VK_SUCCESS) {
            logError("vkCreateInstance failed");
            return false;
        }
        instanceApiVersion_ = requestedApiVersion;
        return true;
    }

    bool createAndroidSurface() {
        VkAndroidSurfaceCreateInfoKHR surfaceInfo{
            VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR
        };
        surfaceInfo.window = window_;
        if (vkCreateAndroidSurfaceKHR(
                instance_,
                &surfaceInfo,
                nullptr,
                &surface_
            ) != VK_SUCCESS) {
            logError("vkCreateAndroidSurfaceKHR failed");
            return false;
        }
        return true;
    }

    bool selectPhysicalDevice(uint32_t negotiationCeiling) {
        uint32_t deviceCount = 0;
        if (vkEnumeratePhysicalDevices(
                instance_,
                &deviceCount,
                nullptr
            ) != VK_SUCCESS ||
            deviceCount == 0) {
            logError("No Vulkan physical device is available");
            return false;
        }
        std::vector<VkPhysicalDevice> devices(deviceCount);
        if (vkEnumeratePhysicalDevices(
                instance_,
                &deviceCount,
                devices.data()
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkEnumeratePhysicalDevices");
            return false;
        }

        VkPhysicalDevice selectedDevice = VK_NULL_HANDLE;
        uint32_t selectedQueueFamily = 0;
        uint32_t selectedApiVersion = 0;
        uint32_t selectedDeviceScore = 0;

        for (VkPhysicalDevice candidate : devices) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(candidate, &properties);
            const uint32_t deviceApiVersion =
                supportedCoreApiVersion(properties.apiVersion);
            if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ||
                deviceApiVersion < VK_API_VERSION_1_1) {
                continue;
            }
            const auto extensions = deviceExtensions(candidate);
            if (!hasExtension(extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                continue;
            }

            uint32_t queueCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(
                candidate,
                &queueCount,
                nullptr
            );
            std::vector<VkQueueFamilyProperties> queues(queueCount);
            vkGetPhysicalDeviceQueueFamilyProperties(
                candidate,
                &queueCount,
                queues.data()
            );
            std::optional<uint32_t> presentQueueFamily;
            for (uint32_t index = 0; index < queueCount; ++index) {
                VkBool32 supportsPresent = VK_FALSE;
                const VkResult supportResult =
                    vkGetPhysicalDeviceSurfaceSupportKHR(
                        candidate,
                        index,
                        surface_,
                        &supportsPresent
                    );
                if (supportResult == VK_SUCCESS &&
                    queues[index].queueCount > 0 &&
                    (queues[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 &&
                    supportsPresent == VK_TRUE) {
                    presentQueueFamily = index;
                    break;
                }
            }
            if (!presentQueueFamily.has_value()) continue;

            const uint32_t candidateApiVersion = std::min(
                negotiationCeiling,
                deviceApiVersion
            );
            const uint32_t deviceScore =
                properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 3U :
                properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2U :
                1U;
            if (candidateApiVersion > selectedApiVersion ||
                (candidateApiVersion == selectedApiVersion &&
                 deviceScore > selectedDeviceScore)) {
                selectedDevice = candidate;
                selectedQueueFamily = *presentQueueFamily;
                selectedApiVersion = candidateApiVersion;
                selectedDeviceScore = deviceScore;
            }
        }

        if (selectedDevice != VK_NULL_HANDLE &&
            selectedApiVersion >= VK_API_VERSION_1_1) {
            physicalDevice_ = selectedDevice;
            queueFamily_ = selectedQueueFamily;
            apiVersion_ = selectedApiVersion;
            return true;
        }
        logError("No Vulkan queue can render and present the wallpaper surface");
        return false;
    }

    bool createDevice() {
        if (apiVersion_ < VK_API_VERSION_1_1 ||
            apiVersion_ != instanceApiVersion_) {
            logError("Vulkan device creation attempted before API negotiation");
            return false;
        }
        constexpr float priority = 1.0F;
        VkDeviceQueueCreateInfo queueInfo{
            VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO
        };
        queueInfo.queueFamilyIndex = queueFamily_;
        queueInfo.queueCount = 1;
        queueInfo.pQueuePriorities = &priority;

        constexpr const char* requiredExtension =
            VK_KHR_SWAPCHAIN_EXTENSION_NAME;
        VkDeviceCreateInfo deviceInfo{
            VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO
        };
        deviceInfo.queueCreateInfoCount = 1;
        deviceInfo.pQueueCreateInfos = &queueInfo;
        deviceInfo.enabledExtensionCount = 1;
        deviceInfo.ppEnabledExtensionNames = &requiredExtension;
        if (vkCreateDevice(
                physicalDevice_,
                &deviceInfo,
                nullptr,
                &device_
            ) != VK_SUCCESS) {
            logError("vkCreateDevice failed");
            return false;
        }
        vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
        return queue_ != VK_NULL_HANDLE;
    }

    bool createSwapchain() {
        VkSurfaceCapabilitiesKHR capabilities{};
        if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                physicalDevice_,
                surface_,
                &capabilities
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
            return false;
        }

        uint32_t formatCount = 0;
        if (vkGetPhysicalDeviceSurfaceFormatsKHR(
                physicalDevice_,
                surface_,
                &formatCount,
                nullptr
            ) != VK_SUCCESS ||
            formatCount == 0) {
            logError(label_ + " surface setup failed: no surface formats");
            return false;
        }
        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        if (vkGetPhysicalDeviceSurfaceFormatsKHR(
                physicalDevice_,
                surface_,
                &formatCount,
                formats.data()
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkGetPhysicalDeviceSurfaceFormatsKHR");
            return false;
        }
        auto preferred = std::find_if(
            formats.begin(),
            formats.end(),
            [](const VkSurfaceFormatKHR& format) {
                return (
                    format.format == VK_FORMAT_R8G8B8A8_UNORM ||
                    format.format == VK_FORMAT_B8G8R8A8_UNORM
                ) && format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
            }
        );
        const VkSurfaceFormatKHR selectedFormat =
            preferred != formats.end() ? *preferred : formats.front();
        swapchainFormat_ = selectedFormat.format;

        if (capabilities.currentExtent.width !=
            std::numeric_limits<uint32_t>::max()) {
            extent_ = capabilities.currentExtent;
        } else {
            extent_.width = std::clamp(
                requestedWidth_,
                capabilities.minImageExtent.width,
                capabilities.maxImageExtent.width
            );
            extent_.height = std::clamp(
                requestedHeight_,
                capabilities.minImageExtent.height,
                capabilities.maxImageExtent.height
            );
        }
        if (extent_.width == 0 || extent_.height == 0) {
            logError(label_ + " surface setup failed: swapchain extent is empty");
            return false;
        }

        uint32_t imageCount = capabilities.minImageCount + 1;
        if (capabilities.maxImageCount > 0) {
            imageCount = std::min(imageCount, capabilities.maxImageCount);
        }
        VkCompositeAlphaFlagBitsKHR compositeAlpha =
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
        const std::array<VkCompositeAlphaFlagBitsKHR, 4> alphaModes{
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR
        };
        for (VkCompositeAlphaFlagBitsKHR mode : alphaModes) {
            if ((capabilities.supportedCompositeAlpha & mode) != 0) {
                compositeAlpha = mode;
                break;
            }
        }

        VkSwapchainCreateInfoKHR swapchainInfo{
            VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR
        };
        swapchainInfo.surface = surface_;
        swapchainInfo.minImageCount = imageCount;
        swapchainInfo.imageFormat = selectedFormat.format;
        swapchainInfo.imageColorSpace = selectedFormat.colorSpace;
        swapchainInfo.imageExtent = extent_;
        swapchainInfo.imageArrayLayers = 1;
        swapchainInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        swapchainInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        // Always prefer an identity preTransform over blindly forwarding
        // capabilities.currentTransform. Nothing downstream in this engine
        // pre-rotates its vertices/UVs to compensate for a non-identity
        // transform, so if currentTransform is ever queried while it's
        // transiently non-identity (e.g. right as a foreground landscape
        // app like a video player hands back to a portrait-locked home
        // screen), that rotated transform gets baked into the swapchain
        // and never gets a chance to correct itself — the wallpaper is
        // then stuck presenting as landscape until the whole engine is
        // torn down and rebuilt (e.g. by switching render backends).
        // Android's ANativeWindow-backed surfaces support IDENTITY
        // virtually universally, so this is a no-op in the common case
        // and only changes behavior for the stuck-rotation scenario.
        swapchainInfo.preTransform =
            (capabilities.supportedTransforms &
             VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) != 0
                ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                : capabilities.currentTransform;
        swapchainInfo.compositeAlpha = compositeAlpha;
        swapchainInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        swapchainInfo.clipped = VK_TRUE;
        if (vkCreateSwapchainKHR(
                device_,
                &swapchainInfo,
                nullptr,
                &swapchain_
            ) != VK_SUCCESS) {
            logError("vkCreateSwapchainKHR failed");
            return false;
        }

        uint32_t actualImageCount = 0;
        if (vkGetSwapchainImagesKHR(
                device_,
                swapchain_,
                &actualImageCount,
                nullptr
            ) != VK_SUCCESS ||
            actualImageCount == 0) {
            logError(label_ + " surface setup failed: swapchain image count query");
            return false;
        }
        swapchainImages_.resize(actualImageCount);
        if (vkGetSwapchainImagesKHR(
                device_,
                swapchain_,
                &actualImageCount,
                swapchainImages_.data()
            ) != VK_SUCCESS ||
            actualImageCount == 0) {
            logError(label_ + " surface setup failed: swapchain image fetch");
            return false;
        }
        swapchainImages_.resize(actualImageCount);

        swapchainImageViews_.reserve(actualImageCount);
        for (VkImage image : swapchainImages_) {
            VkImageViewCreateInfo viewInfo{
                VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
            };
            viewInfo.image = image;
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = swapchainFormat_;
            viewInfo.subresourceRange.aspectMask =
                VK_IMAGE_ASPECT_COLOR_BIT;
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.layerCount = 1;
            VkImageView view = VK_NULL_HANDLE;
            if (vkCreateImageView(
                    device_,
                    &viewInfo,
                    nullptr,
                    &view
                ) != VK_SUCCESS) {
                logError(label_ + " surface setup failed: vkCreateImageView");
                return false;
            }
            swapchainImageViews_.push_back(view);
        }
        return !swapchainImageViews_.empty();
    }

    bool createDescriptorResources() {
        std::vector<VkDescriptorSetLayoutBinding> bindings;
        bindings.resize(textures_.size());
        for (uint32_t index = 0; index < bindings.size(); ++index) {
            bindings[index].binding = index;
            bindings[index].descriptorType =
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            bindings[index].descriptorCount = 1;
            bindings[index].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
        }
        if (!uniformData_.empty()) {
            VkDescriptorSetLayoutBinding uniformBinding{};
            uniformBinding.binding = uniformBinding_;
            uniformBinding.descriptorType =
                VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            uniformBinding.descriptorCount = 1;
            uniformBinding.stageFlags =
                VK_SHADER_STAGE_VERTEX_BIT |
                VK_SHADER_STAGE_FRAGMENT_BIT;
            bindings.push_back(uniformBinding);
        }

        VkDescriptorSetLayoutCreateInfo layoutInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
        };
        layoutInfo.bindingCount =
            static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();
        if (vkCreateDescriptorSetLayout(
                device_,
                &layoutInfo,
                nullptr,
                &descriptorSetLayout_
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkCreateDescriptorSetLayout");
            return false;
        }

        std::vector<VkDescriptorPoolSize> poolSizes;
        VkDescriptorPoolSize samplerPool{};
        samplerPool.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        samplerPool.descriptorCount =
            static_cast<uint32_t>(textures_.size());
        poolSizes.push_back(samplerPool);
        if (!uniformData_.empty()) {
            VkDescriptorPoolSize uniformPool{};
            uniformPool.type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            uniformPool.descriptorCount = 1;
            poolSizes.push_back(uniformPool);
        }
        VkDescriptorPoolCreateInfo poolInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO
        };
        poolInfo.maxSets = 1;
        poolInfo.poolSizeCount =
            static_cast<uint32_t>(poolSizes.size());
        poolInfo.pPoolSizes = poolSizes.data();
        if (vkCreateDescriptorPool(
                device_,
                &poolInfo,
                nullptr,
                &descriptorPool_
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkCreateDescriptorPool");
            return false;
        }

        VkDescriptorSetAllocateInfo allocateInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO
        };
        allocateInfo.descriptorPool = descriptorPool_;
        allocateInfo.descriptorSetCount = 1;
        allocateInfo.pSetLayouts = &descriptorSetLayout_;
        if (vkAllocateDescriptorSets(
                device_,
                &allocateInfo,
                &descriptorSet_
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkAllocateDescriptorSets");
            return false;
        }
        if (uniformData_.empty()) return true;

        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physicalDevice_, &properties);
        if (uniformData_.size() >
            properties.limits.maxUniformBufferRange) {
            logError(label_ + " uniform data exceeds the device limit");
            return false;
        }
        if (!createBuffer(
                uniformData_.size(),
                VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                    VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                uniformBuffer_,
                uniformMemory_
            ) ||
            !setUniformData(
                uniformData_.data(),
                uniformData_.size()
            )) {
            logError(label_ + " surface setup failed: uniform buffer creation");
            return false;
        }

        VkDescriptorBufferInfo bufferInfo{};
        bufferInfo.buffer = uniformBuffer_;
        bufferInfo.offset = 0;
        bufferInfo.range = uniformData_.size();
        VkWriteDescriptorSet descriptorWrite{
            VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
        };
        descriptorWrite.dstSet = descriptorSet_;
        descriptorWrite.dstBinding = uniformBinding_;
        descriptorWrite.descriptorCount = 1;
        descriptorWrite.descriptorType =
            VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        descriptorWrite.pBufferInfo = &bufferInfo;
        vkUpdateDescriptorSets(device_, 1, &descriptorWrite, 0, nullptr);
        return true;
    }

    bool createRenderPass() {
        VkAttachmentDescription attachment{};
        attachment.format = swapchainFormat_;
        attachment.samples = VK_SAMPLE_COUNT_1_BIT;
        attachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        attachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        attachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        attachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        attachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        attachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference colorReference{};
        colorReference.attachment = 0;
        colorReference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorReference;

        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask =
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask =
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask =
            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo renderPassInfo{
            VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO
        };
        renderPassInfo.attachmentCount = 1;
        renderPassInfo.pAttachments = &attachment;
        renderPassInfo.subpassCount = 1;
        renderPassInfo.pSubpasses = &subpass;
        renderPassInfo.dependencyCount = 1;
        renderPassInfo.pDependencies = &dependency;
        return vkCreateRenderPass(
            device_,
            &renderPassInfo,
            nullptr,
            &renderPass_
        ) == VK_SUCCESS;
    }

    std::vector<uint32_t> readShaderAsset(const char* path) const {
        if (assets_ == nullptr) return {};
        AAsset* asset = AAssetManager_open(
            assets_,
            path,
            AASSET_MODE_STREAMING
        );
        if (asset == nullptr) return {};
        const off_t length = AAsset_getLength(asset);
        if (length <= 0 || length % 4 != 0) {
            AAsset_close(asset);
            return {};
        }
        std::vector<uint32_t> code(
            static_cast<size_t>(length) / sizeof(uint32_t)
        );
        const int64_t read = AAsset_read(
            asset,
            code.data(),
            static_cast<size_t>(length)
        );
        AAsset_close(asset);
        if (read != length) return {};
        return code;
    }

    VkShaderModule createShaderModule(
        const std::vector<uint32_t>& code
    ) const {
        if (code.empty()) return VK_NULL_HANDLE;
        VkShaderModuleCreateInfo moduleInfo{
            VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO
        };
        moduleInfo.codeSize = code.size() * sizeof(uint32_t);
        moduleInfo.pCode = code.data();
        VkShaderModule module = VK_NULL_HANDLE;
        if (vkCreateShaderModule(
                device_,
                &moduleInfo,
                nullptr,
                &module
            ) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        return module;
    }

    bool createPipeline() {
        if (vertexCode_.empty()) {
            vertexCode_ = readShaderAsset(vertexShaderAsset_.c_str());
        }
        if (fragmentCode_.empty()) {
            fragmentCode_ = readShaderAsset(fragmentShaderAsset_.c_str());
        }
        const VkShaderModule vertexModule =
            createShaderModule(vertexCode_);
        const VkShaderModule fragmentModule =
            createShaderModule(fragmentCode_);
        if (vertexModule == VK_NULL_HANDLE ||
            fragmentModule == VK_NULL_HANDLE) {
            if (vertexModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, vertexModule, nullptr);
            }
            if (fragmentModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentModule, nullptr);
            }
            logError("Unable to load " + label_ + " SPIR-V shaders");
            return false;
        }

        const std::array<VkPipelineShaderStageCreateInfo, 2> stages{{
            {
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_VERTEX_BIT,
                vertexModule,
                "main",
                nullptr
            },
            {
                VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_FRAGMENT_BIT,
                fragmentModule,
                "main",
                nullptr
            }
        }};

        VkPipelineVertexInputStateCreateInfo vertexInput{
            VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO
        };
        VkPipelineInputAssemblyStateCreateInfo inputAssembly{
            VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO
        };
        inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

        VkViewport viewport{};
        viewport.width = static_cast<float>(extent_.width);
        viewport.height = static_cast<float>(extent_.height);
        viewport.minDepth = 0.0F;
        viewport.maxDepth = 1.0F;
        VkRect2D scissor{{0, 0}, extent_};
        VkPipelineViewportStateCreateInfo viewportState{
            VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO
        };
        viewportState.viewportCount = 1;
        viewportState.pViewports = &viewport;
        viewportState.scissorCount = 1;
        viewportState.pScissors = &scissor;

        VkPipelineRasterizationStateCreateInfo rasterizer{
            VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO
        };
        rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
        rasterizer.cullMode = VK_CULL_MODE_NONE;
        rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        rasterizer.lineWidth = 1.0F;

        VkPipelineMultisampleStateCreateInfo multisampling{
            VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO
        };
        multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineColorBlendAttachmentState colorBlendAttachment{};
        colorBlendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT |
            VK_COLOR_COMPONENT_G_BIT |
            VK_COLOR_COMPONENT_B_BIT |
            VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo colorBlending{
            VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO
        };
        colorBlending.attachmentCount = 1;
        colorBlending.pAttachments = &colorBlendAttachment;

        VkPushConstantRange pushConstants{};
        pushConstants.stageFlags =
            VK_SHADER_STAGE_VERTEX_BIT |
            VK_SHADER_STAGE_FRAGMENT_BIT;
        pushConstants.size =
            static_cast<uint32_t>(pushConstants_.size());
        VkPipelineLayoutCreateInfo pipelineLayoutInfo{
            VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO
        };
        pipelineLayoutInfo.setLayoutCount = 1;
        pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout_;
        pipelineLayoutInfo.pushConstantRangeCount =
            pushConstants_.empty() ? 0U : 1U;
        pipelineLayoutInfo.pPushConstantRanges =
            pushConstants_.empty() ? nullptr : &pushConstants;
        if (vkCreatePipelineLayout(
                device_,
                &pipelineLayoutInfo,
                nullptr,
                &pipelineLayout_
            ) != VK_SUCCESS) {
            vkDestroyShaderModule(device_, vertexModule, nullptr);
            vkDestroyShaderModule(device_, fragmentModule, nullptr);
            logError(label_ + " surface setup failed: vkCreatePipelineLayout");
            return false;
        }

        VkGraphicsPipelineCreateInfo pipelineInfo{
            VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO
        };
        pipelineInfo.stageCount =
            static_cast<uint32_t>(stages.size());
        pipelineInfo.pStages = stages.data();
        pipelineInfo.pVertexInputState = &vertexInput;
        pipelineInfo.pInputAssemblyState = &inputAssembly;
        pipelineInfo.pViewportState = &viewportState;
        pipelineInfo.pRasterizationState = &rasterizer;
        pipelineInfo.pMultisampleState = &multisampling;
        pipelineInfo.pColorBlendState = &colorBlending;
        pipelineInfo.layout = pipelineLayout_;
        pipelineInfo.renderPass = renderPass_;
        pipelineInfo.subpass = 0;
        const VkResult result = vkCreateGraphicsPipelines(
            device_,
            VK_NULL_HANDLE,
            1,
            &pipelineInfo,
            nullptr,
            &pipeline_
        );
        vkDestroyShaderModule(device_, vertexModule, nullptr);
        vkDestroyShaderModule(device_, fragmentModule, nullptr);
        return result == VK_SUCCESS;
    }

    bool createFramebuffers() {
        framebuffers_.reserve(swapchainImageViews_.size());
        for (VkImageView imageView : swapchainImageViews_) {
            VkFramebufferCreateInfo framebufferInfo{
                VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO
            };
            framebufferInfo.renderPass = renderPass_;
            framebufferInfo.attachmentCount = 1;
            framebufferInfo.pAttachments = &imageView;
            framebufferInfo.width = extent_.width;
            framebufferInfo.height = extent_.height;
            framebufferInfo.layers = 1;
            VkFramebuffer framebuffer = VK_NULL_HANDLE;
            if (vkCreateFramebuffer(
                    device_,
                    &framebufferInfo,
                    nullptr,
                    &framebuffer
                ) != VK_SUCCESS) {
                logError(label_ + " surface setup failed: vkCreateFramebuffer");
                return false;
            }
            framebuffers_.push_back(framebuffer);
        }
        return !framebuffers_.empty();
    }

    bool createCommandResources() {
        VkCommandPoolCreateInfo poolInfo{
            VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO
        };
        poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        poolInfo.queueFamilyIndex = queueFamily_;
        if (vkCreateCommandPool(
                device_,
                &poolInfo,
                nullptr,
                &commandPool_
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkCreateCommandPool");
            return false;
        }

        VkCommandBufferAllocateInfo allocateInfo{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
        };
        allocateInfo.commandPool = commandPool_;
        allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocateInfo.commandBufferCount = 1;
        return vkAllocateCommandBuffers(
            device_,
            &allocateInfo,
            &commandBuffer_
        ) == VK_SUCCESS;
    }

    bool createSyncResources() {
        VkSemaphoreCreateInfo semaphoreInfo{
            VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO
        };
        VkFenceCreateInfo fenceInfo{
            VK_STRUCTURE_TYPE_FENCE_CREATE_INFO
        };
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateSemaphore(
                device_,
                &semaphoreInfo,
                nullptr,
                &imageAvailable_
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkCreateSemaphore");
            return false;
        }

        renderFinishedSemaphores_.assign(
            swapchainImages_.size(),
            VK_NULL_HANDLE
        );
        for (VkSemaphore& semaphore : renderFinishedSemaphores_) {
            if (vkCreateSemaphore(
                    device_,
                    &semaphoreInfo,
                    nullptr,
                    &semaphore
                ) != VK_SUCCESS) {
                logError(label_ + " surface setup failed: vkCreateSemaphore");
                return false;
            }
        }

        return !renderFinishedSemaphores_.empty() &&
            vkCreateFence(
                device_,
                &fenceInfo,
                nullptr,
                &renderFence_
            ) == VK_SUCCESS;
    }

    std::optional<uint32_t> findMemoryType(
        uint32_t allowedTypes,
        VkMemoryPropertyFlags requiredProperties
    ) const {
        VkPhysicalDeviceMemoryProperties properties{};
        vkGetPhysicalDeviceMemoryProperties(
            physicalDevice_,
            &properties
        );
        for (uint32_t index = 0;
             index < properties.memoryTypeCount;
             ++index) {
            if ((allowedTypes & (1U << index)) != 0 &&
                (properties.memoryTypes[index].propertyFlags &
                    requiredProperties) == requiredProperties) {
                return index;
            }
        }
        return std::nullopt;
    }

    bool createBuffer(
        VkDeviceSize size,
        VkBufferUsageFlags usage,
        VkMemoryPropertyFlags memoryProperties,
        VkBuffer& buffer,
        VkDeviceMemory& memory
    ) {
        VkBufferCreateInfo bufferInfo{
            VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
        };
        bufferInfo.size = size;
        bufferInfo.usage = usage;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(
                device_,
                &bufferInfo,
                nullptr,
                &buffer
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkCreateBuffer");
            return false;
        }

        VkMemoryRequirements requirements{};
        vkGetBufferMemoryRequirements(device_, buffer, &requirements);
        const auto memoryType = findMemoryType(
            requirements.memoryTypeBits,
            memoryProperties
        );
        if (!memoryType.has_value()) {
            vkDestroyBuffer(device_, buffer, nullptr);
            buffer = VK_NULL_HANDLE;
            return false;
        }
        VkMemoryAllocateInfo allocation{
            VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
        };
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = *memoryType;
        if (vkAllocateMemory(
                device_,
                &allocation,
                nullptr,
                &memory
            ) != VK_SUCCESS) {
            vkDestroyBuffer(device_, buffer, nullptr);
            buffer = VK_NULL_HANDLE;
            return false;
        }
        if (vkBindBufferMemory(device_, buffer, memory, 0) != VK_SUCCESS) {
            vkDestroyBuffer(device_, buffer, nullptr);
            vkFreeMemory(device_, memory, nullptr);
            buffer = VK_NULL_HANDLE;
            memory = VK_NULL_HANDLE;
            return false;
        }
        return true;
    }

    bool createTextureImage(TextureResource& texture) {
        VkImageCreateInfo imageInfo{
            VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO
        };
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        imageInfo.extent = {texture.width, texture.height, 1};
        imageInfo.mipLevels = texture.mipLevels;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage =
            VK_IMAGE_USAGE_TRANSFER_DST_BIT |
            VK_IMAGE_USAGE_SAMPLED_BIT;
        if (texture.mipLevels > 1) {
            imageInfo.usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        }
        imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vkCreateImage(
                device_,
                &imageInfo,
                nullptr,
                &texture.image
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkCreateImage");
            return false;
        }

        VkMemoryRequirements requirements{};
        vkGetImageMemoryRequirements(
            device_,
            texture.image,
            &requirements
        );
        const auto memoryType = findMemoryType(
            requirements.memoryTypeBits,
            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
        );
        if (!memoryType.has_value()) return false;
        VkMemoryAllocateInfo allocation{
            VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO
        };
        allocation.allocationSize = requirements.size;
        allocation.memoryTypeIndex = *memoryType;
        if (vkAllocateMemory(
                device_,
                &allocation,
                nullptr,
                &texture.memory
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkAllocateMemory");
            return false;
        }
        return vkBindImageMemory(
            device_,
            texture.image,
            texture.memory,
            0
        ) == VK_SUCCESS;
    }

    bool installStagedTexture(
        VkBuffer stagingBuffer,
        uint32_t width,
        uint32_t height,
        uint32_t binding
    ) {
        if (binding >= textures_.size() || width == 0 || height == 0) {
            return false;
        }
        TextureResource replacement{};
        replacement.width = width;
        replacement.height = height;
        if ((mipmappedTextureMask_ & (1U << binding)) != 0) {
            VkFormatProperties formatProperties{};
            vkGetPhysicalDeviceFormatProperties(
                physicalDevice_,
                VK_FORMAT_R8G8B8A8_UNORM,
                &formatProperties
            );
            constexpr VkFormatFeatureFlags requiredFeatures =
                VK_FORMAT_FEATURE_BLIT_SRC_BIT |
                VK_FORMAT_FEATURE_BLIT_DST_BIT |
                VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
            if ((formatProperties.optimalTilingFeatures & requiredFeatures) !=
                requiredFeatures) {
                logError(label_ + " requires linear mipmap blits");
                return false;
            }
            replacement.mipLevels =
                static_cast<uint32_t>(
                    std::floor(
                        std::log2(
                            static_cast<double>(std::max(width, height))
                        )
                    )
                ) + 1U;
        }
        if (!createTextureImage(replacement) ||
            !copyBufferToTexture(stagingBuffer, replacement) ||
            !createTextureViewAndSampler(replacement)) {
            destroyTexture(replacement);
            return false;
        }
        if (vkDeviceWaitIdle(device_) != VK_SUCCESS) {
            destroyTexture(replacement);
            return false;
        }

        TextureResource& texture = textures_[binding];
        destroyTexture(texture);
        texture = replacement;
        texture.ready = true;

        VkDescriptorImageInfo imageInfo{};
        imageInfo.sampler = texture.sampler;
        imageInfo.imageView = texture.view;
        imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        VkWriteDescriptorSet descriptorWrite{
            VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET
        };
        descriptorWrite.dstSet = descriptorSet_;
        descriptorWrite.dstBinding = binding;
        descriptorWrite.descriptorCount = 1;
        descriptorWrite.descriptorType =
            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        descriptorWrite.pImageInfo = &imageInfo;
        vkUpdateDescriptorSets(device_, 1, &descriptorWrite, 0, nullptr);
        return true;
    }

    VkCommandBuffer beginSingleUseCommands() {
        VkCommandBuffer command = VK_NULL_HANDLE;
        VkCommandBufferAllocateInfo allocation{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
        };
        allocation.commandPool = commandPool_;
        allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocation.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(
                device_,
                &allocation,
                &command
            ) != VK_SUCCESS) {
            return VK_NULL_HANDLE;
        }
        VkCommandBufferBeginInfo beginInfo{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
        };
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(command, &beginInfo) != VK_SUCCESS) {
            vkFreeCommandBuffers(device_, commandPool_, 1, &command);
            return VK_NULL_HANDLE;
        }
        return command;
    }

    bool finishSingleUseCommands(VkCommandBuffer command) {
        if (vkEndCommandBuffer(command) != VK_SUCCESS) {
            vkFreeCommandBuffers(device_, commandPool_, 1, &command);
            return false;
        }
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1;
        submit.pCommandBuffers = &command;
        const bool success =
            vkQueueSubmit(queue_, 1, &submit, VK_NULL_HANDLE) == VK_SUCCESS &&
            vkQueueWaitIdle(queue_) == VK_SUCCESS;
        vkFreeCommandBuffers(device_, commandPool_, 1, &command);
        return success;
    }

    bool copyBufferToTexture(
        VkBuffer source,
        TextureResource& texture
    ) {
        VkCommandBuffer command = beginSingleUseCommands();
        if (command == VK_NULL_HANDLE) return false;

        VkImageMemoryBarrier toTransfer{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
        };
        toTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.image = texture.image;
        toTransfer.subresourceRange.aspectMask =
            VK_IMAGE_ASPECT_COLOR_BIT;
        toTransfer.subresourceRange.levelCount = texture.mipLevels;
        toTransfer.subresourceRange.layerCount = 1;
        toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &toTransfer
        );

        VkBufferImageCopy copy{};
        copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        copy.imageSubresource.layerCount = 1;
        copy.imageExtent = {texture.width, texture.height, 1};
        vkCmdCopyBufferToImage(
            command,
            source,
            texture.image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &copy
        );

        int32_t mipWidth = static_cast<int32_t>(texture.width);
        int32_t mipHeight = static_cast<int32_t>(texture.height);
        for (uint32_t level = 1;
             level < texture.mipLevels;
             ++level) {
            VkImageMemoryBarrier toSource{
                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
            };
            toSource.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toSource.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            toSource.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toSource.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toSource.image = texture.image;
            toSource.subresourceRange.aspectMask =
                VK_IMAGE_ASPECT_COLOR_BIT;
            toSource.subresourceRange.baseMipLevel = level - 1;
            toSource.subresourceRange.levelCount = 1;
            toSource.subresourceRange.layerCount = 1;
            toSource.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            toSource.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(
                command,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                0,
                nullptr,
                0,
                nullptr,
                1,
                &toSource
            );

            const int32_t nextWidth = std::max(1, mipWidth / 2);
            const int32_t nextHeight = std::max(1, mipHeight / 2);
            VkImageBlit blit{};
            blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            blit.srcSubresource.mipLevel = level - 1;
            blit.srcSubresource.layerCount = 1;
            blit.srcOffsets[1] = {mipWidth, mipHeight, 1};
            blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            blit.dstSubresource.mipLevel = level;
            blit.dstSubresource.layerCount = 1;
            blit.dstOffsets[1] = {nextWidth, nextHeight, 1};
            vkCmdBlitImage(
                command,
                texture.image,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                texture.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                1,
                &blit,
                VK_FILTER_LINEAR
            );

            VkImageMemoryBarrier sourceToShader{
                VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
            };
            sourceToShader.oldLayout =
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            sourceToShader.newLayout =
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            sourceToShader.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            sourceToShader.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            sourceToShader.image = texture.image;
            sourceToShader.subresourceRange.aspectMask =
                VK_IMAGE_ASPECT_COLOR_BIT;
            sourceToShader.subresourceRange.baseMipLevel = level - 1;
            sourceToShader.subresourceRange.levelCount = 1;
            sourceToShader.subresourceRange.layerCount = 1;
            sourceToShader.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            sourceToShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            vkCmdPipelineBarrier(
                command,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                0,
                0,
                nullptr,
                0,
                nullptr,
                1,
                &sourceToShader
            );
            mipWidth = nextWidth;
            mipHeight = nextHeight;
        }

        VkImageMemoryBarrier lastLevelToShader{
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER
        };
        lastLevelToShader.oldLayout =
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        lastLevelToShader.newLayout =
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        lastLevelToShader.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        lastLevelToShader.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        lastLevelToShader.image = texture.image;
        lastLevelToShader.subresourceRange.aspectMask =
            VK_IMAGE_ASPECT_COLOR_BIT;
        lastLevelToShader.subresourceRange.baseMipLevel =
            texture.mipLevels - 1;
        lastLevelToShader.subresourceRange.levelCount = 1;
        lastLevelToShader.subresourceRange.layerCount = 1;
        lastLevelToShader.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        lastLevelToShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,
            0,
            nullptr,
            0,
            nullptr,
            1,
            &lastLevelToShader
        );
        return finishSingleUseCommands(command);
    }

    bool createTextureViewAndSampler(TextureResource& texture) {
        VkImageViewCreateInfo viewInfo{
            VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO
        };
        viewInfo.image = texture.image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
        viewInfo.subresourceRange.aspectMask =
            VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.levelCount = texture.mipLevels;
        viewInfo.subresourceRange.layerCount = 1;
        if (vkCreateImageView(
                device_,
                &viewInfo,
                nullptr,
                &texture.view
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkCreateImageView");
            return false;
        }

        VkSamplerCreateInfo samplerInfo{
            VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO
        };
        samplerInfo.magFilter = VK_FILTER_LINEAR;
        samplerInfo.minFilter = VK_FILTER_LINEAR;
        samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
        samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        samplerInfo.minLod = 0.0F;
        samplerInfo.maxLod =
            static_cast<float>(texture.mipLevels - 1);
        return vkCreateSampler(
            device_,
            &samplerInfo,
            nullptr,
            &texture.sampler
        ) == VK_SUCCESS;
    }

    void destroyTexture(TextureResource& texture) {
        texture.ready = false;
        if (device_ == VK_NULL_HANDLE) return;
        if (texture.sampler != VK_NULL_HANDLE) {
            vkDestroySampler(device_, texture.sampler, nullptr);
        }
        if (texture.view != VK_NULL_HANDLE) {
            vkDestroyImageView(device_, texture.view, nullptr);
        }
        if (texture.image != VK_NULL_HANDLE) {
            vkDestroyImage(device_, texture.image, nullptr);
        }
        if (texture.memory != VK_NULL_HANDLE) {
            vkFreeMemory(device_, texture.memory, nullptr);
        }
        texture = {};
    }

    bool recordRenderCommands(uint32_t imageIndex) {
        VkCommandBufferBeginInfo beginInfo{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO
        };
        if (vkBeginCommandBuffer(
                commandBuffer_,
                &beginInfo
            ) != VK_SUCCESS) {
            logError(label_ + " surface setup failed: vkBeginCommandBuffer");
            return false;
        }
        VkClearValue clearColor{};
        clearColor.color = {{0.0F, 0.0F, 0.0F, 1.0F}};
        VkRenderPassBeginInfo renderPassInfo{
            VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO
        };
        renderPassInfo.renderPass = renderPass_;
        renderPassInfo.framebuffer = framebuffers_[imageIndex];
        renderPassInfo.renderArea.extent = extent_;
        renderPassInfo.clearValueCount = 1;
        renderPassInfo.pClearValues = &clearColor;
        vkCmdBeginRenderPass(
            commandBuffer_,
            &renderPassInfo,
            VK_SUBPASS_CONTENTS_INLINE
        );
        vkCmdBindPipeline(
            commandBuffer_,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipeline_
        );
        vkCmdBindDescriptorSets(
            commandBuffer_,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipelineLayout_,
            0,
            1,
            &descriptorSet_,
            0,
            nullptr
        );
        if (!pushConstants_.empty()) {
            vkCmdPushConstants(
                commandBuffer_,
                pipelineLayout_,
                VK_SHADER_STAGE_VERTEX_BIT |
                    VK_SHADER_STAGE_FRAGMENT_BIT,
                0,
                static_cast<uint32_t>(pushConstants_.size()),
                pushConstants_.data()
            );
        }
        vkCmdDraw(commandBuffer_, 3, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer_);
        return vkEndCommandBuffer(commandBuffer_) == VK_SUCCESS;
    }

    AAssetManager* assets_ = nullptr;
    jobject assetManagerRef_ = nullptr;
    std::string label_;
    std::string vertexShaderAsset_;
    std::string fragmentShaderAsset_;
    std::vector<uint32_t> vertexCode_;
    std::vector<uint32_t> fragmentCode_;
    uint32_t optionalTextureMask_ = 0;
    uint32_t uniformBinding_ = kNoUniformBinding;
    std::vector<uint8_t> uniformData_;
    uint32_t mipmappedTextureMask_ = 0;
    std::vector<uint8_t> pushConstants_;
    ANativeWindow* window_ = nullptr;
    uint32_t requestedWidth_ = 0;
    uint32_t requestedHeight_ = 0;
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    uint32_t instanceApiVersion_ = 0;
    uint32_t apiVersion_ = 0;
    VkDevice device_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkQueue queue_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{};
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainImageViews_;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    std::vector<VkFramebuffer> framebuffers_;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkSemaphore imageAvailable_ = VK_NULL_HANDLE;
    std::vector<VkSemaphore> renderFinishedSemaphores_;
    VkFence renderFence_ = VK_NULL_HANDLE;
    VkBuffer uniformBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory uniformMemory_ = VK_NULL_HANDLE;
    std::vector<TextureResource> textures_;
};

OnePassEngineImpl* engineFromHandle(OnePassHandle handle) {
    return static_cast<OnePassEngineImpl*>(handle);
}

}  // namespace

uint32_t probeRuntime() {
    return probeVulkanRuntime();
}

OnePassHandle createOnePass(
    JNIEnv* env,
    jobject assetManager,
    const OnePassConfig& config
) {
    auto* engine = new OnePassEngineImpl(env, assetManager, config);
    if (!engine->isConfigured()) {
        engine->releaseAssetManagerRef(env);
        delete engine;
        return nullptr;
    }
    return engine;
}

bool setSurface(
    OnePassHandle handle,
    JNIEnv* env,
    jobject surface,
    uint32_t width,
    uint32_t height
) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    return engine != nullptr &&
        env != nullptr &&
        surface != nullptr &&
        width > 0 &&
        height > 0 &&
        engine->setSurface(env, surface, width, height);
}

uint32_t apiVersion(OnePassHandle handle) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    return engine == nullptr ? 0 : engine->apiVersion();
}

float surfaceAspectRatio(OnePassHandle handle) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    return engine == nullptr ? 1.0F : engine->surfaceAspectRatio();
}

bool uploadBitmap(
    OnePassHandle handle,
    JNIEnv* env,
    jobject bitmap,
    uint32_t binding
) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    return engine != nullptr &&
        env != nullptr &&
        bitmap != nullptr &&
        engine->uploadBitmap(env, bitmap, binding);
}

bool clearTexture(
    OnePassHandle handle,
    uint32_t binding
) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    return engine != nullptr && engine->clearTexture(binding);
}

bool setPushConstants(
    OnePassHandle handle,
    const void* data,
    size_t size
) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    return engine != nullptr && engine->setPushConstants(data, size);
}

bool setUniformData(
    OnePassHandle handle,
    const void* data,
    size_t size
) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    return engine != nullptr && engine->setUniformData(data, size);
}

int render(OnePassHandle handle) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    return engine == nullptr ? -1 : engine->render();
}

void destroySurface(OnePassHandle handle) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    if (engine != nullptr) engine->destroySurface();
}

void destroyOnePass(JNIEnv* env, OnePassHandle handle) {
    OnePassEngineImpl* engine = engineFromHandle(handle);
    if (engine != nullptr) {
        engine->releaseAssetManagerRef(env);
    }
    delete engine;
}

std::string drainDiagnostics() {
    std::lock_guard<std::mutex> guard(diagnosticsMutex());
    std::deque<std::string>& buffer = diagnosticsBuffer();
    std::string joined;
    for (const std::string& entry : buffer) {
        joined += entry;
        joined += '\n';
    }
    buffer.clear();
    return joined;
}

}  // namespace atmo::vulkan
