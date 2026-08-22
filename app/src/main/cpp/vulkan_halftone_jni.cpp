#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <cstdint>
#include <new>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/halftone/halftone.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/halftone/halftone.frag.spv";
constexpr uint32_t kWallpaperBinding = 0;
constexpr uint32_t kSubjectMaskBinding = 1;

struct HalftoneParams {
    float progress = 0.0F;
    float dimLevel = 0.0F;
    float aspectRatio = 1.0F;
    float reverse = 0.0F;
    float dotSize = 12.0F;
    float grayscale = 0.0F;
    float backgroundOnly = 0.0F;
    float hasSubject = 0.0F;
    float scrollOffsetX = 0.5F;
    float scrollWindowX = 1.0F;
    float padding0 = 0.0F;
    float padding1 = 0.0F;
};

static_assert(sizeof(HalftoneParams) == 48);

struct HalftoneHandle {
    atmo::vulkan::OnePassHandle engine = nullptr;
    bool reverse = false;
};

HalftoneHandle* fromHandle(jlong handle) {
    return reinterpret_cast<HalftoneHandle*>(
        static_cast<intptr_t>(handle)
    );
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject assetManager,
    jboolean reverse
) {
    if (assetManager == nullptr) return 0;
    const atmo::vulkan::OnePassConfig config{
        "Atmo Halftone",
        kVertexShader,
        kFragmentShader,
        2,
        1U << kSubjectMaskBinding,
        sizeof(HalftoneParams)
    };
    atmo::vulkan::OnePassHandle engine =
        atmo::vulkan::createOnePass(env, assetManager, config);
    if (engine == nullptr) return 0;
    auto* handle = new (std::nothrow) HalftoneHandle{
        engine,
        reverse == JNI_TRUE
    };
    if (handle == nullptr) {
        atmo::vulkan::destroyOnePass(env, engine);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeSetSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    HalftoneHandle* halftone = fromHandle(handle);
    if (halftone == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    return atmo::vulkan::setSurface(
        halftone->engine,
        env,
        surface,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeGetApiVersion(
    JNIEnv*,
    jobject,
    jlong handle
) {
    HalftoneHandle* halftone = fromHandle(handle);
    return halftone == nullptr
        ? 0
        : static_cast<jint>(
            atmo::vulkan::apiVersion(halftone->engine)
        );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeUploadWallpaper(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    HalftoneHandle* halftone = fromHandle(handle);
    return halftone != nullptr &&
        atmo::vulkan::uploadBitmap(
            halftone->engine,
            env,
            bitmap,
            kWallpaperBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeUploadSubjectMask(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    HalftoneHandle* halftone = fromHandle(handle);
    return halftone != nullptr &&
        atmo::vulkan::uploadBitmap(
            halftone->engine,
            env,
            bitmap,
            kSubjectMaskBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeClearSubjectMask(
    JNIEnv*,
    jobject,
    jlong handle
) {
    HalftoneHandle* halftone = fromHandle(handle);
    return halftone != nullptr &&
        atmo::vulkan::clearTexture(
            halftone->engine,
            kSubjectMaskBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeSetState(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat progress,
    jfloat dimLevel,
    jfloat dotSize,
    jboolean grayscale,
    jboolean backgroundOnly,
    jboolean hasSubject,
    jfloat scrollOffsetX,
    jfloat scrollWindowX
) {
    HalftoneHandle* halftone = fromHandle(handle);
    if (halftone == nullptr) return;
    const bool isolateBackground = backgroundOnly == JNI_TRUE;
    const HalftoneParams params{
        progress,
        dimLevel,
        atmo::vulkan::surfaceAspectRatio(halftone->engine),
        halftone->reverse ? 1.0F : 0.0F,
        dotSize,
        grayscale == JNI_TRUE ? 1.0F : 0.0F,
        isolateBackground ? 1.0F : 0.0F,
        isolateBackground && hasSubject == JNI_TRUE ? 1.0F : 0.0F,
        scrollOffsetX,
        scrollWindowX,
        0.0F,
        0.0F
    };
    atmo::vulkan::setPushConstants(
        halftone->engine,
        &params,
        sizeof(params)
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeRender(
    JNIEnv*,
    jobject,
    jlong handle
) {
    HalftoneHandle* halftone = fromHandle(handle);
    return halftone == nullptr
        ? -1
        : atmo::vulkan::render(halftone->engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeDestroySurface(
    JNIEnv*,
    jobject,
    jlong handle
) {
    HalftoneHandle* halftone = fromHandle(handle);
    if (halftone != nullptr) {
        atmo::vulkan::destroySurface(halftone->engine);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanHalftoneNative_nativeDestroy(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    HalftoneHandle* halftone = fromHandle(handle);
    if (halftone == nullptr) return;
    atmo::vulkan::destroyOnePass(env, halftone->engine);
    delete halftone;
}
