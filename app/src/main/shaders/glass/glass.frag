#version 450

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D sourceTexture;
layout(set = 0, binding = 1) uniform sampler2D subjectMask;
layout(set = 0, binding = 2) uniform sampler2D clockTexture;

layout(push_constant) uniform GlassParams {
    vec4 transition;
    vec4 viewport;
    vec4 mask;
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

// Mirrors the GLES path in assets/shaders/glass/glass.frag; keep the two in step.
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

const float TWO_PI = 6.28318530718;

vec3 sampleSoftened(vec2 uv, vec2 texel) {
    vec2 radius = vec2(texel.x * 1.15, 0.0);
    return
        texture(sourceTexture, uv).rgb * 0.58 +
        texture(sourceTexture, clamp(uv + radius, 0.0, 1.0)).rgb * 0.21 +
        texture(sourceTexture, clamp(uv - radius, 0.0, 1.0)).rgb * 0.21;
}

float sampleSubject(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(subjectMask, 0));
    float value = texture(subjectMask, uv).r;
    value = max(
        value,
        texture(subjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    value = max(
        value,
        texture(subjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r
    );
    return value;
}

void main() {
    float progress = clamp(params.transition.x, 0.0, 1.0);
    float count = max(1.0, floor(params.transition.y + 0.5));
    float thickness = clamp(params.transition.z, 0.0, 1.0);
    float style = params.transition.w;

    float screenX = clamp(vEffectCoord.x, 0.0, 0.999999);
    float lanePosition = screenX * count;
    float laneIndex = floor(lanePosition);
    float orderFromRight = count - 1.0 - laneIndex;

    float sequential = clamp(progress * count - orderFromRight, 0.0, 1.0);
    sequential = sequential * sequential * (3.0 - 2.0 * sequential);
    float fade = progress * progress * (3.0 - 2.0 * progress);
    float transitionAmount = mix(sequential, fade, step(0.5, style));

    float localRib = fract(lanePosition);
    float wave = sin(TWO_PI * localRib);
    float profileExponent = mix(1.80, 0.25, thickness);
    float profile = sign(wave) * pow(abs(wave), profileExponent);
    float displacement = profile * (1.08 * max(params.viewport.y, 0.001) / count);

    vec2 glassUv = vec2(
        clamp(vTexCoord.x + displacement, 0.0, 1.0),
        vTexCoord.y
    );
    vec2 texel = 1.0 / vec2(textureSize(sourceTexture, 0));
    vec3 sharpColor = texture(sourceTexture, vTexCoord).rgb;
    vec3 refractedColor = texture(sourceTexture, glassUv).rgb;
    vec3 glassColor = mix(
        refractedColor,
        sampleSoftened(glassUv, texel),
        0.72
    );
    glassColor += vec3(0.016 * (2.0 * localRib - 1.0));

    float rightInnerGlow =
        1.0 - smoothstep(0.0, 0.25, 1.0 - localRib);
    glassColor = clamp(
        glassColor + vec3(0.036 * rightInnerGlow),
        0.0,
        1.0
    );
    glassColor = mix(
        glassColor,
        vec3(0.0),
        clamp(params.viewport.z, 0.0, 1.0)
    );

    float backgroundCoverage = 1.0;
    if (params.mask.x > 0.5) {
        if (params.mask.y > 0.5) {
            float subject = max(
                sampleSubject(vTexCoord),
                sampleSubject(glassUv)
            );
            backgroundCoverage = 1.0 - smoothstep(0.30, 0.72, subject);
        } else {
            // No subject mask: nothing is known to protect, so cover the
            // whole frame rather than suppressing the effect everywhere.
            backgroundCoverage = 1.0;
        }
    }

    vec3 finalColor = mix(
        sharpColor,
        glassColor,
        transitionAmount * backgroundCoverage
    );

    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(
        finalColor,
        sharpColor,
        sampleSubject(vTexCoord)
    );

    fragColor = vec4(finalColor, 1.0);
}
