#version 450

layout(set = 0, binding = 0) uniform sampler2D wallpaperTexture;
layout(set = 0, binding = 1) uniform sampler2D subjectMask;
layout(set = 0, binding = 2) uniform sampler2D clockTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

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

// Mirrors the GLES path in assets/shaders/halftone/sharp_to_halftone.frag; keep the two in step.
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

// Draws the sharp subject back over the clock, so the clock reads as sitting
// behind them. Fades with the clock itself, so the subject is not left
// re-sharpened over a stylised background once the clock has gone.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, float subjectMask) {
    if (params.clockMeta.y <= 0.5 || params.clockMeta.z <= 0.5) return color;
    float coverage = smoothstep(0.30, 0.72, subjectMask);
    return mix(color, subjectColor, coverage * params.clockMeta.x);
}

// Raw subject coverage for the clock's depth effect.
//
// Deliberately not foregroundProtection() below: that one returns 0 whenever
// the Halftone effect's own "background only" mode is off, because it exists
// to decide where the halftone is suppressed. The clock's depth is a separate
// user setting that must work with background-only switched off, so it reads
// the mask directly.
float clockSubjectMask(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(subjectMask, 0));
    float mask = texture(subjectMask, uv).r;
    mask = max(
        mask,
        texture(subjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return mask;
}

mat2 rotate2d(float angle) {
    float sine = sin(angle);
    float cosine = cos(angle);
    return mat2(cosine, -sine, sine, cosine);
}

float halftoneChannel(
    vec2 uv,
    float angle,
    float value,
    vec2 textureDimensions,
    float dotSize
) {
    vec2 centered = uv - 0.5;
    centered.x *= params.render.z;
    vec2 rotated = rotate2d(angle) * centered;
    vec2 grid = rotated * textureDimensions.y / dotSize;
    vec2 local = fract(grid) - 0.5;

    float distanceFromCenter = length(local);
    float radius = sqrt(value) * 0.75;
    float edge = max(0.05, 1.0 / dotSize);
    return smoothstep(
        radius + edge,
        radius - edge,
        distanceFromCenter
    );
}

float foregroundProtection(vec2 uv) {
    if (params.controls.z <= 0.5) return 0.0;
    // No subject mask: nothing is known to protect, so don't revert the
    // whole frame back to the untouched image.
    if (params.controls.w <= 0.5) return 0.0;

    vec2 stepSize = 2.0 / vec2(textureSize(subjectMask, 0));
    float mask = texture(subjectMask, uv).r;
    mask = max(
        mask,
        texture(subjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    mask = max(
        mask,
        texture(subjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return smoothstep(0.30, 0.72, mask);
}

void main() {
    bool reverse = params.render.w > 0.5;
    float progress = clamp(params.render.x, 0.0, 1.0);
    float effectStrength = reverse ? 1.0 - progress : progress;
    float dotSize = params.controls.x;
    bool grayscale = params.controls.y > 0.5;

    vec3 sharp = texture(wallpaperTexture, vTexCoord).rgb;
    vec2 textureDimensions = vec2(textureSize(wallpaperTexture, 0));
    vec3 halftoneOutput;

    bool dotsDisabled = reverse ? dotSize == 0.0 : dotSize < 0.1;
    if (dotsDisabled) {
        if (grayscale) {
            float luma = dot(sharp, vec3(0.299, 0.587, 0.114));
            halftoneOutput = vec3(luma);
        } else {
            halftoneOutput = sharp;
        }
    } else if (grayscale) {
        float luma = dot(sharp, vec3(0.299, 0.587, 0.114));
        float black = halftoneChannel(
            vTexCoord,
            radians(45.0),
            1.0 - luma,
            textureDimensions,
            dotSize
        );
        halftoneOutput = vec3(1.0 - black);
    } else {
        vec3 cmy = 1.0 - sharp;
        float cyan = halftoneChannel(
            vTexCoord,
            radians(15.0),
            cmy.r,
            textureDimensions,
            dotSize
        );
        float magenta = halftoneChannel(
            vTexCoord,
            radians(75.0),
            cmy.g,
            textureDimensions,
            dotSize
        );
        float yellow = halftoneChannel(
            vTexCoord,
            radians(0.0),
            cmy.b,
            textureDimensions,
            dotSize
        );
        halftoneOutput = 1.0 - vec3(cyan, magenta, yellow);
    }

    vec3 finalColor = mix(sharp, halftoneOutput, effectStrength);
    finalColor = mix(
        finalColor,
        vec3(0.0),
        params.render.y * effectStrength
    );
    finalColor = mix(
        finalColor,
        sharp,
        foregroundProtection(vTexCoord)
    );

    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(
        finalColor,
        sharp,
        clockSubjectMask(vTexCoord)
    );

    fragColor = vec4(finalColor, 1.0);
}
