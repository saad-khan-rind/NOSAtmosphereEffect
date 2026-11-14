#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <cstddef>
#include <cstdint>
#include <new>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/duo/duo.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/duo/duo.frag.spv";
// The lock screen's photo, and the home screen's.
constexpr uint32_t kLockBinding = 0;
constexpr uint32_t kHomeBinding = 1;

// Matches DuoParams in shaders/duo: render, then scroll.
struct DuoParams {
    float progress = 0.0F;
    float dimLevel = 0.0F;
    float aspectRatio = 1.0F;
    // DuoStyle's ordinal.
    float style = 0.0F;
    float scrollOffsetX = 0.5F;
    // The share of each photo's width on screen.
    float lockWindowX = 1.0F;
    float homeWindowX = 1.0F;
    // A wake transition's zoom on the wallpaper; 1 when none.
    float wallpaperZoom = 1.0F;
};

static_assert(offsetof(DuoParams, scrollOffsetX) == 16);
static_assert(sizeof(DuoParams) == 32);

struct DuoHandle {
    atmo::vulkan::OnePassHandle engine = nullptr;
};

DuoHandle* fromHandle(jlong handle) {
    return reinterpret_cast<DuoHandle*>(
        static_cast<intptr_t>(handle)
    );
}

jboolean upload(JNIEnv* env, jlong handle, jobject bitmap, uint32_t binding) {
    DuoHandle* duo = fromHandle(handle);
    return duo != nullptr &&
        atmo::vulkan::uploadBitmap(duo->engine, env, bitmap, binding)
        ? JNI_TRUE
        : JNI_FALSE;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject assetManager
) {
    if (assetManager == nullptr) return 0;
    const atmo::vulkan::OnePassConfig config{
        "Atmo Duo",
        kVertexShader,
        kFragmentShader,
        2,
        // The home photo arrives after the lock photo: until then its
        // binding holds the engine's 1x1 clear texture.
        (1U << kHomeBinding),
        sizeof(DuoParams)
    };
    atmo::vulkan::OnePassHandle engine =
        atmo::vulkan::createOnePass(env, assetManager, config);
    if (engine == nullptr) return 0;
    auto* handle = new (std::nothrow) DuoHandle{engine};
    if (handle == nullptr) {
        atmo::vulkan::destroyOnePass(env, engine);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeSetSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    DuoHandle* duo = fromHandle(handle);
    if (duo == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    return atmo::vulkan::setSurface(
        duo->engine,
        env,
        surface,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeGetApiVersion(
    JNIEnv*,
    jobject,
    jlong handle
) {
    DuoHandle* duo = fromHandle(handle);
    return duo == nullptr
        ? 0
        : static_cast<jint>(atmo::vulkan::apiVersion(duo->engine));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeUploadWallpaper(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    return upload(env, handle, bitmap, kLockBinding);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeUploadHome(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    return upload(env, handle, bitmap, kHomeBinding);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeSetState(
    JNIEnv*,
    jobject,
    jlong handle,
    jfloat progress,
    jfloat dimLevel,
    jint style,
    jfloat scrollOffsetX,
    jfloat lockWindowX,
    jfloat homeWindowX,
    jfloat wallpaperZoom
) {
    DuoHandle* duo = fromHandle(handle);
    if (duo == nullptr) return;
    const DuoParams params{
        progress,
        dimLevel,
        atmo::vulkan::surfaceAspectRatio(duo->engine),
        static_cast<float>(style),
        scrollOffsetX,
        lockWindowX,
        homeWindowX,
        wallpaperZoom
    };
    atmo::vulkan::setPushConstants(duo->engine, &params, sizeof(params));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeRender(
    JNIEnv*,
    jobject,
    jlong handle
) {
    DuoHandle* duo = fromHandle(handle);
    return duo == nullptr
        ? -1
        : atmo::vulkan::render(duo->engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeDestroySurface(
    JNIEnv*,
    jobject,
    jlong handle
) {
    DuoHandle* duo = fromHandle(handle);
    if (duo != nullptr) {
        atmo::vulkan::destroySurface(duo->engine);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanDuoNative_nativeDestroy(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    DuoHandle* duo = fromHandle(handle);
    if (duo == nullptr) return;
    atmo::vulkan::destroyOnePass(env, duo->engine);
    delete duo;
}
