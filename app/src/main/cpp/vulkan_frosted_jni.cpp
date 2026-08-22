#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <cstdint>
#include <new>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/frosted/frosted.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/frosted/frosted.frag.spv";
constexpr uint32_t kSharpBinding = 0;
constexpr uint32_t kBlurredBinding = 1;

struct FrostedParams {
    float progress = 0.0F;
    float dimLevel = 0.2F;
    float aspectRatio = 1.0F;
    float drawerBlur = 0.0F;
    float enableNoise = 0.0F;
    float noiseScale = 2000.0F;
    float noiseStrength = 0.06F;
    float padding0 = 0.0F;
    float scrollOffsetX = 0.5F;
    float scrollWindowX = 1.0F;
    float padding1 = 0.0F;
    float padding2 = 0.0F;
};

static_assert(sizeof(FrostedParams) == 48);

struct FrostedHandle {
    atmo::vulkan::OnePassHandle engine = nullptr;
};

FrostedHandle* fromHandle(jlong handle) {
    return reinterpret_cast<FrostedHandle*>(
        static_cast<intptr_t>(handle)
    );
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject assetManager
) {
    const atmo::vulkan::OnePassConfig config{
        "Atmo Frosted",
        kVertexShader,
        kFragmentShader,
        2,
        0,
        sizeof(FrostedParams),
        atmo::vulkan::kNoUniformBinding,
        0,
        1U << kSharpBinding
    };
    atmo::vulkan::OnePassHandle engine =
        atmo::vulkan::createOnePass(env, assetManager, config);
    if (engine == nullptr) return 0;
    auto* handle = new (std::nothrow) FrostedHandle{engine};
    if (handle == nullptr) {
        atmo::vulkan::destroyOnePass(env, engine);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeSetSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    FrostedHandle* frosted = fromHandle(handle);
    if (frosted == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    return atmo::vulkan::setSurface(
        frosted->engine,
        env,
        surface,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeGetApiVersion(
    JNIEnv*,
    jobject,
    jlong handle
) {
    FrostedHandle* frosted = fromHandle(handle);
    return frosted == nullptr
        ? 0
        : static_cast<jint>(
            atmo::vulkan::apiVersion(frosted->engine)
        );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeUploadSharp(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    FrostedHandle* frosted = fromHandle(handle);
    return frosted != nullptr &&
        atmo::vulkan::uploadBitmap(
            frosted->engine,
            env,
            bitmap,
            kSharpBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeUploadBlurred(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    FrostedHandle* frosted = fromHandle(handle);
    return frosted != nullptr &&
        atmo::vulkan::uploadBitmap(
            frosted->engine,
            env,
            bitmap,
            kBlurredBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeSetState(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat progress,
    jfloat dimLevel,
    jboolean enableNoise,
    jfloat noiseScale,
    jfloat noiseStrength,
    jfloat drawerBlur,
    jfloat scrollOffsetX,
    jfloat scrollWindowX
) {
    FrostedHandle* frosted = fromHandle(handle);
    if (frosted == nullptr) return;
    const FrostedParams params{
        progress,
        dimLevel,
        atmo::vulkan::surfaceAspectRatio(frosted->engine),
        drawerBlur,
        enableNoise == JNI_TRUE ? 1.0F : 0.0F,
        noiseScale,
        noiseStrength,
        0.0F,
        scrollOffsetX,
        scrollWindowX,
        0.0F,
        0.0F
    };
    atmo::vulkan::setPushConstants(
        frosted->engine,
        &params,
        sizeof(params)
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeRender(
    JNIEnv*,
    jobject,
    jlong handle
) {
    FrostedHandle* frosted = fromHandle(handle);
    return frosted == nullptr
        ? -1
        : atmo::vulkan::render(frosted->engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeDestroySurface(
    JNIEnv*,
    jobject,
    jlong handle
) {
    FrostedHandle* frosted = fromHandle(handle);
    if (frosted != nullptr) {
        atmo::vulkan::destroySurface(frosted->engine);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanFrostedNative_nativeDestroy(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    FrostedHandle* frosted = fromHandle(handle);
    if (frosted == nullptr) return;
    atmo::vulkan::destroyOnePass(env, frosted->engine);
    delete frosted;
}
