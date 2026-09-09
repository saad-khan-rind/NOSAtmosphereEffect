#version 450

layout(set = 0, binding = 0) uniform sampler2D sharpTexture;
layout(set = 0, binding = 1) uniform sampler2D lineTexture;
layout(set = 0, binding = 2) uniform sampler2D clockTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

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

// Mirrors the GLES path in assets/shaders/neon/neon.frag; keep the two in step.
//
// clockMeta.y is "a face has been uploaded", NOT the user's toggle: the
// engine fills unwritten optional bindings with an opaque-black 1x1 clear
// texture, so sampling before the first upload would paint a solid black
// rectangle where the clock belongs. The lock/home fade arrives already
// folded into clockMeta.x, so there is no policy in this shader.
vec3 compositeClock(vec3 color, vec2 screenCoord) {
    if (params.clockMeta.y <= 0.5 || params.clockMeta.x <= 0.0) return color;
    vec2 clockSize = max(params.clockRect.zw, vec2(1e-5));
    vec2 clockOrigin = vec2(
        params.clockRect.x - clockSize.x * 0.5,
        params.clockRect.y
    );
    vec2 clockUv = (screenCoord - clockOrigin) / clockSize;
    if (
        clockUv.x < 0.0 || clockUv.x > 1.0 ||
        clockUv.y < 0.0 || clockUv.y > 1.0
    ) {
        return color;
    }
    vec4 clockSample = texture(clockTexture, clockUv);
    return mix(color, clockSample.rgb, clockSample.a * params.clockMeta.x);
}

void main() {
    vec3 sharp = texture(sharpTexture, vTexCoord).rgb;
    float progress = clamp(params.render.x, 0.0, 1.0);
    float reverse = step(0.5, params.render.w);
    float imageAmount = mix(progress, 1.0 - progress, reverse);

    float lineMaximum = max(params.canvas.w, 1.0);
    float lineDistance = texture(lineTexture, vTexCoord).r * lineMaximum;
    float sourceWidth = max(float(textureSize(sharpTexture, 0).x), 1.0);
    float lineScale = float(textureSize(lineTexture, 0).x) / sourceWidth;
    float baseWidth = max(params.canvas.x * lineScale, 0.25);
    float width = mix(baseWidth, baseWidth * 0.78, imageAmount);
    float ink =
        1.0 -
        smoothstep(width * 0.45, width * 0.45 + 1.1, lineDistance);

    float luma = dot(sharp, vec3(0.2126, 0.7152, 0.0722));
    vec3 inkColor = mix(
        vec3(0.76),
        vec3(0.96),
        smoothstep(0.12, 0.88, luma)
    );
    vec3 sketch = inkColor * ink;

    float blend = smoothstep(0.02, 0.98, imageAmount);
    vec3 color = mix(sketch, sharp, blend);
    color = mix(
        color,
        vec3(0.0),
        clamp(params.render.y, 0.0, 1.0) * (1.0 - imageAmount)
    );
    color = compositeClock(color, vEffectCoord);

    fragColor = vec4(color, 1.0);
}
