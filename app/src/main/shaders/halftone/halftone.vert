#version 450

layout(location = 0) out vec2 vTexCoord;
// Screen-locked: the scroll window below is deliberately NOT applied,
// so the clock stays put while the photo pans.
layout(location = 1) out vec2 vEffectCoord;

layout(push_constant) uniform HalftoneParams {
    vec4 render;
    vec4 controls;
    vec4 scroll;
    // Appended after the existing vec4s so none of the offsets above shift.
    //
    // clockRect: centerX, top, widthFraction, heightFraction — all in the
    // screen-locked vEffectCoord space. The width arrives already divided by
    // the surface aspect (see the JNI), because this shader has no
    // surface-aspect field of its own.
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
    float windowWidth = max(params.scroll.y, 0.001);
    vec2 coordinate = coordinates[gl_VertexIndex];
    // The Adaptive clock's arrival zoom: the photo starts a little magnified
    // about the screen's centre and settles with the clock. The clock itself
    // is placed by vEffectCoord, which is left alone. 0 means no zoom.
    float wallpaperZoom = params.scroll.z > 0.0 ? params.scroll.z : 1.0;
    coordinate = 0.5 + (coordinate - 0.5) / wallpaperZoom;
    coordinate.x = params.scroll.x * (1.0 - windowWidth) +
        coordinate.x * windowWidth;
    vTexCoord = coordinate;
    vEffectCoord = coordinates[gl_VertexIndex];
}
