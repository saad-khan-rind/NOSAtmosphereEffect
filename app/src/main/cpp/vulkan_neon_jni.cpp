#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <cstddef>
#include <cstdint>
#include <new>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/neon/neon.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/neon/neon.frag.spv";
constexpr uint32_t kWallpaperBinding = 0;
constexpr uint32_t kContourBinding = 1;
constexpr uint32_t kClockBinding = 2;
// The mask Sketch already computes, bound a second time for the
// on-screen pass. The contour bake consumes its own copy.
constexpr uint32_t kClockSubjectMaskBinding = 3;
constexpr float kLineMaximum = 6.0F;

struct CanvasParams {
    float progress = 0.0F;
    float dimLevel = 0.0F;
    float aspectRatio = 1.0F;
    float reverse = 0.0F;
    float lineWidth = 1.5F;
    float scrollOffsetX = 0.5F;
    float scrollWindowX = 1.0F;
    float lineMaximum = kLineMaximum;
    // Clock overlay, appended after the existing fields so none of the
    // offsets above shift.
    //
    // clockRect: centerX, top, width, height as screen fractions, with the
    // width already divided by the surface aspect — see
    // atmo::vulkan::writeClockParams.
    //
    // clockMeta: opacity (lock/home fade already folded in by the host), "a
    // face has been uploaded", "depth enabled AND a mask exists", unused.
    float clockRect[4]{};
    float clockMeta[4]{};
};

static_assert(offsetof(CanvasParams, clockRect) == 32);
static_assert(offsetof(CanvasParams, clockMeta) == 48);
static_assert(sizeof(CanvasParams) == 64);

struct CanvasHandle {
    atmo::vulkan::OnePassHandle engine = nullptr;
    bool reverse = false;
};

CanvasHandle* fromHandle(jlong handle) {
    return reinterpret_cast<CanvasHandle*>(
        static_cast<intptr_t>(handle)
    );
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject assetManager,
    jboolean reverse
) {
    if (assetManager == nullptr) return 0;
    const atmo::vulkan::OnePassConfig config{
        "Atmo Canvas Sketch",
        kVertexShader,
        kFragmentShader,
        4,
        // Both extra bindings are optional: each holds the engine's 1x1 clear
        // texture until real content is uploaded.
        (1U << kClockBinding) | (1U << kClockSubjectMaskBinding),
        sizeof(CanvasParams)
    };
    atmo::vulkan::OnePassHandle engine =
        atmo::vulkan::createOnePass(env, assetManager, config);
    if (engine == nullptr) return 0;
    auto* handle = new (std::nothrow) CanvasHandle{
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
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeSetSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    CanvasHandle* canvas = fromHandle(handle);
    if (
        canvas == nullptr ||
        surface == nullptr ||
        width <= 0 ||
        height <= 0
    ) {
        return JNI_FALSE;
    }
    return atmo::vulkan::setSurface(
        canvas->engine,
        env,
        surface,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeGetApiVersion(
    JNIEnv*,
    jobject,
    jlong handle
) {
    CanvasHandle* canvas = fromHandle(handle);
    return canvas == nullptr
        ? 0
        : static_cast<jint>(
            atmo::vulkan::apiVersion(canvas->engine)
        );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeUploadWallpaper(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    CanvasHandle* canvas = fromHandle(handle);
    return canvas != nullptr &&
        bitmap != nullptr &&
        atmo::vulkan::uploadBitmap(
            canvas->engine,
            env,
            bitmap,
            kWallpaperBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeUploadContour(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    CanvasHandle* canvas = fromHandle(handle);
    return canvas != nullptr &&
        bitmap != nullptr &&
        atmo::vulkan::uploadBitmap(
            canvas->engine,
            env,
            bitmap,
            kContourBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeUploadClock(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    CanvasHandle* canvas = fromHandle(handle);
    return canvas != nullptr &&
        atmo::vulkan::uploadBitmap(canvas->engine, env, bitmap, kClockBinding)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeClearClock(
    JNIEnv*,
    jobject,
    jlong handle
) {
    CanvasHandle* canvas = fromHandle(handle);
    return canvas != nullptr &&
        atmo::vulkan::clearTexture(canvas->engine, kClockBinding)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeUploadSubjectMask(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    CanvasHandle* canvas = fromHandle(handle);
    return canvas != nullptr &&
        atmo::vulkan::uploadBitmap(canvas->engine, env, bitmap, kClockSubjectMaskBinding)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeClearSubjectMask(
    JNIEnv*,
    jobject,
    jlong handle
) {
    CanvasHandle* canvas = fromHandle(handle);
    return canvas != nullptr &&
        atmo::vulkan::clearTexture(canvas->engine, kClockSubjectMaskBinding)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeSetState(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat progress,
    jfloat dimLevel,
    jfloat lineWidth,
    jfloat scrollOffsetX,
    jfloat scrollWindowX,
    jfloat clockCenterX,
    jfloat clockTop,
    jfloat clockHeightFraction,
    jfloat clockTextureAspect,
    jfloat clockOpacity,
    jboolean clockUploaded,
    jboolean clockDepth
) {
    CanvasHandle* canvas = fromHandle(handle);
    if (canvas == nullptr) return;
    const float aspect = atmo::vulkan::surfaceAspectRatio(canvas->engine);
    CanvasParams params{
        progress,
        dimLevel,
        aspect,
        canvas->reverse ? 1.0F : 0.0F,
        lineWidth,
        scrollOffsetX,
        scrollWindowX,
        kLineMaximum
    };
    atmo::vulkan::writeClockParams(
        params,
        aspect,
        clockCenterX,
        clockTop,
        clockHeightFraction,
        clockTextureAspect,
        clockOpacity,
        clockUploaded == JNI_TRUE,
        clockDepth == JNI_TRUE
    );
    atmo::vulkan::setPushConstants(
        canvas->engine,
        &params,
        sizeof(params)
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeRender(
    JNIEnv*,
    jobject,
    jlong handle
) {
    CanvasHandle* canvas = fromHandle(handle);
    return canvas == nullptr
        ? -1
        : atmo::vulkan::render(canvas->engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeDestroySurface(
    JNIEnv*,
    jobject,
    jlong handle
) {
    CanvasHandle* canvas = fromHandle(handle);
    if (canvas != nullptr) {
        atmo::vulkan::destroySurface(canvas->engine);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeDestroy(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    CanvasHandle* canvas = fromHandle(handle);
    if (canvas == nullptr) return;
    atmo::vulkan::destroyOnePass(env, canvas->engine);
    delete canvas;
}
