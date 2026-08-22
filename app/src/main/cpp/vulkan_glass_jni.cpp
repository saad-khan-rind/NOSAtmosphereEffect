#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <cstdint>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/glass/glass.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/glass/glass.frag.spv";
constexpr uint32_t kWallpaperBinding = 0;
constexpr uint32_t kSubjectMaskBinding = 1;

struct GlassParams {
    float progress = 0.0F;
    float lineCount = 28.0F;
    float lineThickness = 0.775F;
    float transitionStyle = 0.0F;
    float scrollOffsetX = 0.5F;
    float scrollWindowX = 1.0F;
    float dimLevel = 0.0F;
    float padding0 = 0.0F;
    float backgroundOnly = 0.0F;
    float hasSubject = 0.0F;
    float padding1 = 0.0F;
    float padding2 = 0.0F;
};

static_assert(sizeof(GlassParams) == 48);

atmo::vulkan::OnePassHandle fromHandle(jlong handle) {
    return reinterpret_cast<atmo::vulkan::OnePassHandle>(
        static_cast<intptr_t>(handle)
    );
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject assetManager
) {
    if (assetManager == nullptr) return 0;
    const atmo::vulkan::OnePassConfig config{
        "Atmo Glass",
        kVertexShader,
        kFragmentShader,
        2,
        1U << kSubjectMaskBinding,
        sizeof(GlassParams)
    };
    return static_cast<jlong>(reinterpret_cast<intptr_t>(
        atmo::vulkan::createOnePass(env, assetManager, config)
    ));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeSetSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    if (engine == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    return atmo::vulkan::setSurface(
        engine,
        env,
        surface,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeGetApiVersion(
    JNIEnv*,
    jobject,
    jlong handle
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    return engine == nullptr
        ? 0
        : static_cast<jint>(atmo::vulkan::apiVersion(engine));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeUploadWallpaper(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    return engine != nullptr &&
        atmo::vulkan::uploadBitmap(
            engine,
            env,
            bitmap,
            kWallpaperBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeUploadMask(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    return engine != nullptr &&
        atmo::vulkan::uploadBitmap(
            engine,
            env,
            bitmap,
            kSubjectMaskBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeClearMask(
    JNIEnv*,
    jobject,
    jlong handle
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    return engine != nullptr &&
        atmo::vulkan::clearTexture(engine, kSubjectMaskBinding)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeSetState(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat progress,
    jfloat lineCount,
    jfloat lineThickness,
    jfloat transitionStyle,
    jfloat scrollOffsetX,
    jfloat scrollWindowX,
    jfloat dimLevel,
    jboolean backgroundOnly,
    jboolean hasSubject
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    if (engine == nullptr) return JNI_FALSE;
    const bool isolateBackground = backgroundOnly == JNI_TRUE;
    const GlassParams params{
        progress,
        lineCount,
        lineThickness,
        transitionStyle,
        scrollOffsetX,
        scrollWindowX,
        dimLevel,
        0.0F,
        isolateBackground ? 1.0F : 0.0F,
        isolateBackground && hasSubject == JNI_TRUE ? 1.0F : 0.0F,
        0.0F,
        0.0F
    };
    return atmo::vulkan::setPushConstants(engine, &params, sizeof(params))
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeRender(
    JNIEnv*,
    jobject,
    jlong handle
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    return engine == nullptr ? -1 : atmo::vulkan::render(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeDestroySurface(
    JNIEnv*,
    jobject,
    jlong handle
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    if (engine != nullptr) {
        atmo::vulkan::destroySurface(engine);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeDestroy(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    atmo::vulkan::destroyOnePass(env, fromHandle(handle));
}
