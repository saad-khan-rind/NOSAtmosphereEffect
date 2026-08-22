#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <cstdint>
#include <new>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/colorfill/colorfill.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/colorfill/colorfill.frag.spv";
constexpr uint32_t kWallpaperBinding = 0;

struct ColorFillParams {
    float progress = 0.0F;
    float dimLevel = 0.0F;
    float aspectRatio = 1.0F;
    float reverse = 0.0F;
    float originX = 0.5F;
    float originY = 0.8F;
    float scrollOffsetX = 0.5F;
    float scrollWindowX = 1.0F;
};

static_assert(sizeof(ColorFillParams) == 32);

struct ColorFillHandle {
    atmo::vulkan::OnePassHandle engine = nullptr;
    bool reverse = false;
};

ColorFillHandle* fromHandle(jlong handle) {
    return reinterpret_cast<ColorFillHandle*>(
        static_cast<intptr_t>(handle)
    );
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeProbe(
    JNIEnv*,
    jobject
) {
    return static_cast<jint>(atmo::vulkan::probeRuntime());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject assetManager,
    jboolean reverse
) {
    if (assetManager == nullptr) return 0;
    const atmo::vulkan::OnePassConfig config{
        "Atmo Color Fill",
        kVertexShader,
        kFragmentShader,
        1,
        0,
        sizeof(ColorFillParams)
    };
    atmo::vulkan::OnePassHandle engine =
        atmo::vulkan::createOnePass(env, assetManager, config);
    if (engine == nullptr) return 0;
    auto* handle = new (std::nothrow) ColorFillHandle{
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
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeSetSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    ColorFillHandle* colorFill = fromHandle(handle);
    if (colorFill == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    return atmo::vulkan::setSurface(
        colorFill->engine,
        env,
        surface,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeGetApiVersion(
    JNIEnv*,
    jobject,
    jlong handle
) {
    ColorFillHandle* colorFill = fromHandle(handle);
    return colorFill == nullptr
        ? 0
        : static_cast<jint>(
            atmo::vulkan::apiVersion(colorFill->engine)
        );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeUploadBitmap(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    ColorFillHandle* colorFill = fromHandle(handle);
    return colorFill != nullptr &&
        atmo::vulkan::uploadBitmap(
            colorFill->engine,
            env,
            bitmap,
            kWallpaperBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeSetState(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat progress,
    jfloat dimLevel,
    jfloat originX,
    jfloat originY,
    jfloat scrollOffsetX,
    jfloat scrollWindowX
) {
    ColorFillHandle* colorFill = fromHandle(handle);
    if (colorFill == nullptr) return;
    const ColorFillParams params{
        progress,
        dimLevel,
        atmo::vulkan::surfaceAspectRatio(colorFill->engine),
        colorFill->reverse ? 1.0F : 0.0F,
        originX,
        originY,
        scrollOffsetX,
        scrollWindowX
    };
    atmo::vulkan::setPushConstants(
        colorFill->engine,
        &params,
        sizeof(params)
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeRender(
    JNIEnv*,
    jobject,
    jlong handle
) {
    ColorFillHandle* colorFill = fromHandle(handle);
    return colorFill == nullptr
        ? -1
        : atmo::vulkan::render(colorFill->engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeDestroySurface(
    JNIEnv*,
    jobject,
    jlong handle
) {
    ColorFillHandle* colorFill = fromHandle(handle);
    if (colorFill != nullptr) {
        atmo::vulkan::destroySurface(colorFill->engine);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanNative_nativeDestroy(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    ColorFillHandle* colorFill = fromHandle(handle);
    if (colorFill == nullptr) return;
    atmo::vulkan::destroyOnePass(env, colorFill->engine);
    delete colorFill;
}
