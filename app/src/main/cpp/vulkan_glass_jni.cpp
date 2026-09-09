#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <cstddef>
#include <cstdint>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/glass/glass.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/glass/glass.frag.spv";
constexpr uint32_t kWallpaperBinding = 0;
constexpr uint32_t kSubjectMaskBinding = 1;
constexpr uint32_t kClockBinding = 2;

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
    // Clock overlay, appended after the existing fields so none of the
    // offsets above shift.
    //
    // clockRect: centerX, top, width, height as screen fractions, with the
    // width already divided by the surface aspect — see
    // atmo::vulkan::writeClockParams.
    //
    // clockMeta: opacity (lock/home fade already folded in by the host), "a
    // face has been uploaded", "depth enabled AND a mask exists", unused.
    // The depth slot is deliberately NOT gated on backgroundOnly: the clock's
    // depth effect is its own setting and must work with the Glass effect's
    // subject isolation switched off.
    float clockRect[4]{};
    float clockMeta[4]{};
};

static_assert(offsetof(GlassParams, clockRect) == 48);
static_assert(offsetof(GlassParams, clockMeta) == 64);
static_assert(sizeof(GlassParams) == 80);

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
        3,
        // Both the mask and the clock are optional: each holds the engine's
        // 1x1 clear texture until real content lands.
        (1U << kSubjectMaskBinding) | (1U << kClockBinding),
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
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeUploadClock(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    return engine != nullptr &&
        atmo::vulkan::uploadBitmap(engine, env, bitmap, kClockBinding)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanGlassNative_nativeClearClock(
    JNIEnv*,
    jobject,
    jlong handle
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    return engine != nullptr &&
        atmo::vulkan::clearTexture(engine, kClockBinding)
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
    jboolean hasSubject,
    jfloat clockCenterX,
    jfloat clockTop,
    jfloat clockHeightFraction,
    jfloat clockTextureAspect,
    jfloat clockOpacity,
    jboolean clockUploaded,
    jboolean clockDepth
) {
    atmo::vulkan::OnePassHandle engine = fromHandle(handle);
    if (engine == nullptr) return JNI_FALSE;
    const bool isolateBackground = backgroundOnly == JNI_TRUE;
    GlassParams params{
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
    // Depth needs only a mask, so it is gated on hasSubject alone — NOT on
    // isolateBackground the way mask.x/.y above are. The clock's depth effect
    // is its own setting and has to work with Glass's background-only mode
    // switched off.
    atmo::vulkan::writeClockParams(
        params,
        atmo::vulkan::surfaceAspectRatio(engine),
        clockCenterX,
        clockTop,
        clockHeightFraction,
        clockTextureAspect,
        clockOpacity,
        clockUploaded == JNI_TRUE,
        clockDepth == JNI_TRUE && hasSubject == JNI_TRUE
    );
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
