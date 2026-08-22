#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <cstdint>
#include <new>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/neon/neon.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/neon/neon.frag.spv";
constexpr uint32_t kWallpaperBinding = 0;
constexpr uint32_t kContourBinding = 1;
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
};

static_assert(sizeof(CanvasParams) == 32);

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
        2,
        0,
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

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNeonNative_nativeSetState(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat progress,
    jfloat dimLevel,
    jfloat lineWidth,
    jfloat scrollOffsetX,
    jfloat scrollWindowX
) {
    CanvasHandle* canvas = fromHandle(handle);
    if (canvas == nullptr) return;
    const CanvasParams params{
        progress,
        dimLevel,
        atmo::vulkan::surfaceAspectRatio(canvas->engine),
        canvas->reverse ? 1.0F : 0.0F,
        lineWidth,
        scrollOffsetX,
        scrollWindowX,
        kLineMaximum
    };
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
