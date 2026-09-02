#pragma once

#include <jni.h>

#include <android/asset_manager.h>

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

}  // namespace atmo::vulkan
