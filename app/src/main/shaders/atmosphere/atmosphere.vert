#version 450

layout(location = 0) out vec2 vTexCoord;
layout(location = 1) out vec2 vEffectCoord;

// This block MUST match atmosphere.frag exactly: same binding, same block
// name, same members in the same order. Two stages in one pipeline that touch
// the same descriptor have to agree on its type, and the descriptor set layout
// built in vulkan_one_pass_engine.cpp from vulkan_atmosphere_jni.cpp declares
// binding 3 as a COMBINED_IMAGE_SAMPLER (the clock face) and binding 4 as this
// uniform buffer.
//
// This was binding 3 until now, left behind when the clock work moved the UBO
// to 4 and gave 3 to the clock texture. A vertex stage declaring a uniform
// block over a sampler binding is an invalid shader/layout interface, so
// vkCreateGraphicsPipelines rejected the pipeline, createPipeline() returned
// false, and setSurface() failed — which surfaced as "The Vulkan Atmosphere
// swapchain could not be initialized" with no native detail, because the
// pipeline bail-out was the last unlabelled one in the chain.
layout(std140, set = 0, binding = 4) uniform AtmosphereParams {
    vec4 render;
    vec4 noise;
    vec4 glass;
    vec4 viewport;
    vec4 misc;
    ivec4 blobMeta;
    vec4 blobColors[16];
    vec4 blobPositionsAndSizes[16];
    vec4 clockRect;
    vec4 clockMeta;
} params;

void main() {
    const vec2 positions[3] = vec2[3](
        vec2(-1.0, -1.0),
        vec2(3.0, -1.0),
        vec2(-1.0, 3.0)
    );
    const vec2 coordinates[3] = vec2[3](
        vec2(0.0, 0.0),
        vec2(2.0, 0.0),
        vec2(0.0, 2.0)
    );

    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    vec2 coordinate = coordinates[gl_VertexIndex];
    float windowX = max(params.viewport.y, 0.001);
    coordinate.x =
        params.viewport.x * (1.0 - windowX) +
        coordinate.x * windowX;
    vTexCoord = coordinate;
    vEffectCoord = coordinates[gl_VertexIndex];
}
