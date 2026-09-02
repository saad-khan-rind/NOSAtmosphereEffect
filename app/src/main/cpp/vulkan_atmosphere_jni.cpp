#include "vulkan_one_pass_engine.h"

#include <android/asset_manager_jni.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <new>

namespace {

constexpr char kVertexShader[] =
    "shaders/vulkan/atmosphere/atmosphere.vert.spv";
constexpr char kFragmentShader[] =
    "shaders/vulkan/atmosphere/atmosphere.frag.spv";
constexpr uint32_t kSharpBinding = 0;
constexpr uint32_t kBlurredBinding = 1;
constexpr uint32_t kSubjectMaskBinding = 2;
constexpr uint32_t kClockBinding = 3;
constexpr uint32_t kUniformBinding = 4;
constexpr int32_t kMaximumBlobs = 16;

struct alignas(16) AtmosphereParams {
    float render[4]{};
    float noise[4]{};
    float glass[4]{};
    float viewport[4]{};
    float misc[4]{};
    int32_t blobMeta[4]{};
    float blobColors[kMaximumBlobs][4]{};
    float blobPositionsAndSizes[kMaximumBlobs][4]{};
    // clockRect: centerX, top, heightFraction, textureAspect (all in the
    // screen-locked vEffectCoord space the shader already uses for glass
    // ribs — see atmosphere.frag).
    //
    // clockMeta: opacity (with the lock fade already folded in by the host),
    // "a face has been uploaded", "depth enabled AND a subject mask exists",
    // unused. The second slot is NOT the user's on/off toggle: unwritten
    // optional bindings hold an opaque-black clear texture, so the shader
    // needs to know whether real content has landed yet.
    //
    // Appended at the end, after the existing static_assert'd layout, so
    // none of the offsets above shift.
    float clockRect[4]{};
    float clockMeta[4]{};
};

static_assert(offsetof(AtmosphereParams, render) == 0);
static_assert(offsetof(AtmosphereParams, noise) == 16);
static_assert(offsetof(AtmosphereParams, glass) == 32);
static_assert(offsetof(AtmosphereParams, viewport) == 48);
static_assert(offsetof(AtmosphereParams, misc) == 64);
static_assert(offsetof(AtmosphereParams, blobMeta) == 80);
static_assert(offsetof(AtmosphereParams, blobColors) == 96);
static_assert(
    offsetof(AtmosphereParams, blobPositionsAndSizes) == 352
);
static_assert(offsetof(AtmosphereParams, clockRect) == 608);
static_assert(offsetof(AtmosphereParams, clockMeta) == 624);
static_assert(sizeof(AtmosphereParams) == 640);

struct AtmosphereHandle {
    atmo::vulkan::OnePassHandle engine = nullptr;
    bool reverse = false;
    AtmosphereParams latestParams{};
    bool hasLatestParams = false;
    bool surfaceReady = false;
};

AtmosphereHandle* fromHandle(jlong handle) {
    return reinterpret_cast<AtmosphereHandle*>(
        static_cast<intptr_t>(handle)
    );
}

bool readBlobArrays(
    JNIEnv* env,
    jfloatArray javaColors,
    jfloatArray javaPositions,
    jfloatArray javaSizes,
    jint requestedCount,
    AtmosphereParams& params
) {
    if (javaColors == nullptr ||
        javaPositions == nullptr ||
        javaSizes == nullptr) {
        return false;
    }
    const jsize colorLength = env->GetArrayLength(javaColors);
    const jsize positionLength = env->GetArrayLength(javaPositions);
    const jsize sizeLength = env->GetArrayLength(javaSizes);
    const int32_t count = std::max(
        0,
        std::min({
            static_cast<int32_t>(requestedCount),
            kMaximumBlobs,
            static_cast<int32_t>(colorLength / 3),
            static_cast<int32_t>(positionLength / 2),
            static_cast<int32_t>(sizeLength)
        })
    );

    std::array<jfloat, kMaximumBlobs * 3> colors{};
    std::array<jfloat, kMaximumBlobs * 2> positions{};
    std::array<jfloat, kMaximumBlobs> sizes{};
    if (count > 0) {
        env->GetFloatArrayRegion(
            javaColors,
            0,
            count * 3,
            colors.data()
        );
        env->GetFloatArrayRegion(
            javaPositions,
            0,
            count * 2,
            positions.data()
        );
        env->GetFloatArrayRegion(
            javaSizes,
            0,
            count,
            sizes.data()
        );
        if (env->ExceptionCheck()) return false;
    }

    params.blobMeta[0] = count;
    for (int32_t index = 0; index < count; ++index) {
        params.blobColors[index][0] = colors[index * 3];
        params.blobColors[index][1] = colors[index * 3 + 1];
        params.blobColors[index][2] = colors[index * 3 + 2];
        params.blobPositionsAndSizes[index][0] =
            positions[index * 2];
        params.blobPositionsAndSizes[index][1] =
            positions[index * 2 + 1];
        params.blobPositionsAndSizes[index][2] = sizes[index];
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeCreate(
    JNIEnv* env,
    jobject,
    jobject assetManager,
    jboolean reverse
) {
    if (assetManager == nullptr) return 0;
    const atmo::vulkan::OnePassConfig config{
        "Atmo Atmosphere",
        kVertexShader,
        kFragmentShader,
        4,
        (1U << kSubjectMaskBinding) | (1U << kClockBinding),
        0,
        kUniformBinding,
        sizeof(AtmosphereParams)
    };
    atmo::vulkan::OnePassHandle engine =
        atmo::vulkan::createOnePass(env, assetManager, config);
    if (engine == nullptr) return 0;
    auto* handle = new (std::nothrow) AtmosphereHandle{
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
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeSetSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    if (atmosphere == nullptr || width <= 0 || height <= 0) {
        return JNI_FALSE;
    }
    atmosphere->surfaceReady = false;
    const bool surfaceCreated = atmo::vulkan::setSurface(
        atmosphere->engine,
        env,
        surface,
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height)
    );
    if (!surfaceCreated) return JNI_FALSE;

    atmosphere->surfaceReady = true;
    atmosphere->latestParams.render[2] =
        atmo::vulkan::surfaceAspectRatio(atmosphere->engine);
    if (
        atmosphere->hasLatestParams &&
        !atmo::vulkan::setUniformData(
            atmosphere->engine,
            &atmosphere->latestParams,
            sizeof(atmosphere->latestParams)
        )
    ) {
        atmosphere->surfaceReady = false;
        atmo::vulkan::destroySurface(atmosphere->engine);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeGetApiVersion(
    JNIEnv*,
    jobject,
    jlong handle
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    return atmosphere == nullptr
        ? 0
        : static_cast<jint>(
            atmo::vulkan::apiVersion(atmosphere->engine)
        );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeUploadWallpaper(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    return atmosphere != nullptr &&
        atmo::vulkan::uploadBitmap(
            atmosphere->engine,
            env,
            bitmap,
            kSharpBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeUploadBlurred(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    return atmosphere != nullptr &&
        atmo::vulkan::uploadBitmap(
            atmosphere->engine,
            env,
            bitmap,
            kBlurredBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeUploadMask(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    return atmosphere != nullptr &&
        atmo::vulkan::uploadBitmap(
            atmosphere->engine,
            env,
            bitmap,
            kSubjectMaskBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeClearMask(
    JNIEnv*,
    jobject,
    jlong handle
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    return atmosphere != nullptr &&
        atmo::vulkan::clearTexture(
            atmosphere->engine,
            kSubjectMaskBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeUploadClock(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bitmap
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    return atmosphere != nullptr &&
        atmo::vulkan::uploadBitmap(
            atmosphere->engine,
            env,
            bitmap,
            kClockBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeClearClock(
    JNIEnv*,
    jobject,
    jlong handle
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    return atmosphere != nullptr &&
        atmo::vulkan::clearTexture(
            atmosphere->engine,
            kClockBinding
        )
        ? JNI_TRUE
        : JNI_FALSE;
}

/**
 * Not tied to a handle: the buffer is global, so it still returns the reason
 * after an engine has been torn down — which is exactly when it matters.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeDrainDiagnostics(
    JNIEnv* env,
    jobject
) {
    return env->NewStringUTF(atmo::vulkan::drainDiagnostics().c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeSetState(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloat progress,
    jfloat dimLevel,
    jboolean noiseEnabled,
    jfloat noiseScale,
    jfloat noiseStrength,
    jfloat saturation,
    jfloat contrast,
    jboolean glassEnabled,
    jfloat glassLineCount,
    jfloat glassLineThickness,
    jfloat scrollOffsetX,
    jfloat scrollWindowX,
    jboolean backgroundOnly,
    jboolean hasSubject,
    jfloat drawerBlur,
    jfloat clockCenterX,
    jfloat clockTop,
    jfloat clockHeightFraction,
    jfloat clockTextureAspect,
    jfloat clockOpacity,
    jboolean clockUploaded,
    jboolean clockDepth,
    jfloatArray blobColors,
    jfloatArray blobPositions,
    jfloatArray blobSizes,
    jint blobCount
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    if (atmosphere == nullptr) return JNI_FALSE;

    const bool glass = glassEnabled == JNI_TRUE;
    const bool isolateBackground =
        glass && backgroundOnly == JNI_TRUE;
    AtmosphereParams params{};
    params.render[0] = progress;
    params.render[1] = dimLevel;
    params.render[2] =
        atmo::vulkan::surfaceAspectRatio(atmosphere->engine);
    params.noise[0] = noiseEnabled == JNI_TRUE ? 1.0F : 0.0F;
    params.noise[1] = noiseScale;
    params.noise[2] = noiseStrength;
    params.noise[3] = saturation;
    params.glass[0] = contrast;
    params.glass[1] = glass ? 1.0F : 0.0F;
    params.glass[2] = glassLineCount;
    params.glass[3] = glassLineThickness;
    params.viewport[0] = scrollOffsetX;
    params.viewport[1] = scrollWindowX;
    params.viewport[2] = isolateBackground ? 1.0F : 0.0F;
    params.viewport[3] =
        isolateBackground && hasSubject == JNI_TRUE
            ? 1.0F
            : 0.0F;
    params.misc[0] = atmosphere->reverse ? 1.0F : 0.0F;
    params.misc[1] = drawerBlur;
    params.clockRect[0] = clockCenterX;
    params.clockRect[1] = clockTop;
    params.clockRect[2] = clockHeightFraction;
    params.clockRect[3] = clockTextureAspect;
    params.clockMeta[0] = clockOpacity;
    params.clockMeta[1] = clockUploaded == JNI_TRUE ? 1.0F : 0.0F;
    // Depth is the clock's own setting and only needs a subject mask — it is
    // deliberately not gated on the Glass effect's background-only mode the
    // way viewport[3] above is.
    params.clockMeta[2] =
        clockDepth == JNI_TRUE && hasSubject == JNI_TRUE ? 1.0F : 0.0F;

    if (!readBlobArrays(
            env,
            blobColors,
            blobPositions,
            blobSizes,
            blobCount,
            params
    )) {
        return JNI_FALSE;
    }
    atmosphere->latestParams = params;
    atmosphere->hasLatestParams = true;
    if (!atmosphere->surfaceReady) {
        return JNI_TRUE;
    }
    return atmo::vulkan::setUniformData(
        atmosphere->engine,
        &atmosphere->latestParams,
        sizeof(atmosphere->latestParams)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeRender(
    JNIEnv*,
    jobject,
    jlong handle
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    return atmosphere == nullptr
        ? -1
        : atmo::vulkan::render(atmosphere->engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeDestroySurface(
    JNIEnv*,
    jobject,
    jlong handle
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    if (atmosphere != nullptr) {
        atmosphere->surfaceReady = false;
        atmo::vulkan::destroySurface(atmosphere->engine);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_app_nosatmosphereeffect_renderer_vulkan_VulkanAtmosphereNative_nativeDestroy(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    AtmosphereHandle* atmosphere = fromHandle(handle);
    if (atmosphere == nullptr) return;
    atmo::vulkan::destroyOnePass(env, atmosphere->engine);
    delete atmosphere;
}
