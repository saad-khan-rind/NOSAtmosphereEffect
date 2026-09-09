#version 450

layout(location = 0) out vec2 vTexCoord;
// Screen-locked: the scroll window below is deliberately NOT applied,
// so the clock stays put while the photo pans.
layout(location = 1) out vec2 vEffectCoord;

layout(push_constant) uniform CanvasParams {
    vec4 render;
    vec4 canvas;
    // Appended after the existing vec4s so none of the offsets above shift.
    //
    // clockRect: centerX, top, widthFraction, heightFraction — all in the
    // screen-locked vEffectCoord space. Unlike the Atmosphere shader, which
    // is handed the face's own texture aspect and divides by the surface
    // aspect here, the width arrives already divided: these effects have no
    // surface-aspect field in their push constants, and the JNI knows the
    // surface aspect anyway, so doing the division there costs nothing and
    // keeps the shader free of geometry it would otherwise need a new
    // parameter to compute.
    //
    // clockMeta: opacity (with the lock/home fade already folded in by the
    // host), "a face has been uploaded", "depth enabled AND a subject mask
    // exists", unused.
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
    float windowWidth = max(params.canvas.z, 0.001);
    vec2 coordinate = coordinates[gl_VertexIndex];
    coordinate.x =
        params.canvas.y * (1.0 - windowWidth) +
        coordinate.x * windowWidth;
    vTexCoord = coordinate;
    vEffectCoord = coordinates[gl_VertexIndex];
}
