#version 450

layout(set = 0, binding = 0) uniform sampler2D sharpTexture;
layout(set = 0, binding = 1) uniform sampler2D blurredTexture;
layout(set = 0, binding = 2) uniform sampler2D clockTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform FrostedParams {
    vec4 render;
    vec4 noise;
    vec4 scroll;
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

// Mirrors the GLES path in assets/shaders/frostedBlur/frosted.frag; keep the two in step.
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

float random(vec2 coordinate) {
    return fract(
        sin(dot(coordinate, vec2(12.9898, 78.233))) *
        43758.5453
    );
}

void main() {
    float progress = clamp(params.render.x, 0.0, 1.0);
    vec3 sharp = textureLod(
        sharpTexture,
        vTexCoord,
        progress * 4.0
    ).rgb;
    vec3 frosted = texture(blurredTexture, vTexCoord).rgb;

    vec3 finalColor = mix(sharp, frosted, progress);
    finalColor = mix(
        finalColor,
        vec3(0.0),
        params.render.y * progress
    );

    if (params.noise.x > 0.5) {
        vec2 noiseCoordinate = vTexCoord;
        noiseCoordinate.x *= params.render.z;
        vec2 grain = floor(noiseCoordinate * params.noise.y);
        float noiseValue = random(grain);
        float visibility = smoothstep(0.4, 1.0, progress);
        finalColor += vec3(
            noiseValue *
            params.noise.z *
            visibility
        );
    }

    finalColor = mix(
        finalColor,
        frosted,
        clamp(params.render.w, 0.0, 1.0)
    );
    finalColor = compositeClock(finalColor, vEffectCoord);

    fragColor = vec4(finalColor, 1.0);
}
