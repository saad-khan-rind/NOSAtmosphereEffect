#pragma once

#include <jni.h>

#include <android/asset_manager.h>

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <string>

namespace atmo::vulkan {

constexpr uint32_t kNoUniformBinding = 0xFFFFFFFFU;

struct OnePassConfig {
    const char* label;
    const char* vertexShaderAsset;
    const char* fragmentShaderAsset;
    uint32_t textureBindingCount;
    uint32_t optionalTextureMask;
    uint32_t pushConstantSize;
    uint32_t uniformBinding = kNoUniformBinding;
    uint32_t uniformSize = 0;
    uint32_t mipmappedTextureMask = 0;
};

using OnePassHandle = void*;

uint32_t probeRuntime();

/**
 * Returns every native error recorded since the last call, newline separated,
 * and clears the buffer. Backs the in-app diagnostics screen.
 */
std::string drainDiagnostics();

OnePassHandle createOnePass(
    JNIEnv* env,
    jobject assetManager,
    const OnePassConfig& config
);

bool setSurface(
    OnePassHandle handle,
    JNIEnv* env,
    jobject surface,
    uint32_t width,
    uint32_t height
);

uint32_t apiVersion(OnePassHandle handle);

float surfaceAspectRatio(OnePassHandle handle);

bool uploadBitmap(
    OnePassHandle handle,
    JNIEnv* env,
    jobject bitmap,
    uint32_t binding
);

bool clearTexture(
    OnePassHandle handle,
    uint32_t binding
);

bool setPushConstants(
    OnePassHandle handle,
    const void* data,
    size_t size
);

bool setUniformData(
    OnePassHandle handle,
    const void* data,
    size_t size
);

int render(OnePassHandle handle);

void destroySurface(OnePassHandle handle);

void destroyOnePass(JNIEnv* env, OnePassHandle handle);

/**
 * Fills the clock overlay fields shared by every effect's push constants or
 * uniform block.
 *
 * [heightFraction] is a fraction of screen height; the width follows from the
 * face bitmap's own pixel aspect divided by the surface aspect, so glyphs are
 * not stretched on any display shape. Doing that division here rather than in
 * each shader is what lets the five push-constant effects carry the clock
 * without also gaining a surface-aspect field they have no other use for.
 *
 * [Params] only has to expose `clockRect[4]` and `clockMeta[4]`.
 */
template <typename Params>
void writeClockParams(
    Params& params,
    float surfaceAspect,
    float centerX,
    float top,
    float heightFraction,
    float textureAspect,
    float opacity,
    bool uploaded,
    bool depth
) {
    const float safeAspect =
        std::isfinite(surfaceAspect) && surfaceAspect > 0.0F
            ? surfaceAspect
            : 1.0F;
    const float safeTextureAspect =
        std::isfinite(textureAspect) && textureAspect > 0.0F
            ? textureAspect
            : 1.0F;
    params.clockRect[0] = centerX;
    params.clockRect[1] = top;
    params.clockRect[2] = heightFraction * safeTextureAspect / safeAspect;
    params.clockRect[3] = heightFraction;
    params.clockMeta[0] = opacity;
    // NOT the user's toggle: unwritten optional bindings hold an opaque-black
    // clear texture, so the shader must know whether real content has landed.
    params.clockMeta[1] = uploaded ? 1.0F : 0.0F;
    params.clockMeta[2] = depth ? 1.0F : 0.0F;
    params.clockMeta[3] = 0.0F;
}

}  // namespace atmo::vulkan
