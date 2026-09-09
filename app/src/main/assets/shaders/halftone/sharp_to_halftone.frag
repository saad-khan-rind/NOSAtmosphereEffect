#version 300 es
precision highp float;

in vec2 vTexCoord;
// Screen-locked, unaffected by the wallpaper's scroll window — the clock
// overlay is positioned against the physical screen.
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uSubjectMask;
uniform float uAspectRatio;
uniform float uBlurStrength;
uniform float uDimLevel;
uniform float uDotSize;
uniform float uGrayscale;
uniform float uBackgroundOnly;
uniform float uHasSubject;

float random(vec2 co) {
    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

mat2 rotate2d(float angle) {
    float s = sin(angle);
    float c = cos(angle);
    return mat2(c, -s, s, c);
}

float halftoneChannel(vec2 uv, float angle, float value, vec2 texSize, float dotSize) {
    vec2 centerUV = uv - 0.5;
    centerUV.x *= uAspectRatio;
    vec2 rotUV = rotate2d(angle) * centerUV;

    vec2 gridUV = rotUV * texSize.y / dotSize;
    vec2 localUV = fract(gridUV) - 0.5;

    float dist = length(localUV);
    float radius = sqrt(value) * 0.75;
    float edge = max(0.05, 1.0 / dotSize);

    return smoothstep(radius + edge, radius - edge, dist);
}

float foregroundProtection(vec2 uv) {
    if (uBackgroundOnly <= 0.5) return 0.0;
    // No subject mask: nothing is known to protect, so don't revert the
    // whole frame back to the untouched image.
    if (uHasSubject <= 0.5) return 0.0;

    vec2 stepSize = 2.0 / vec2(textureSize(uSubjectMask, 0));
    float mask = texture(uSubjectMask, uv).r;
    mask = max(mask, texture(uSubjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    return smoothstep(0.30, 0.72, mask);
}

// ---------------------------------------------------------------- clock
// Wallpaper clock overlay. uClockEnabled is 1.0 only once a real face has
// been uploaded — it is NOT the user's toggle, because the texture starts
// out as unwritten storage and sampling that would paint a rectangle of
// garbage where the clock belongs. uClockRect is x, y, width, height in the
// screen-locked vEffectCoord space, so the clock stays put while the photo
// pans. uClockOpacity already has the lock/home fade folded in by the
// renderer, so both backends share one curve.
uniform sampler2D uClockTexture;
uniform float uClockEnabled;
uniform vec4 uClockRect;
uniform float uClockOpacity;
// The clock's own depth switch, already ANDed with "a real subject mask is
// bound" by the renderer. Deliberately independent of the effect's own
// background-only mode: the depth effect has to work whether or not the user
// has asked for subject isolation elsewhere.
uniform float uClockDepth;

vec3 compositeClock(vec3 color, vec2 screenCoord) {
    if (uClockEnabled <= 0.5 || uClockOpacity <= 0.0) return color;
    vec2 clockUv = (screenCoord - uClockRect.xy) / max(uClockRect.zw, vec2(1e-5));
    if (clockUv.x < 0.0 || clockUv.x > 1.0 ||
        clockUv.y < 0.0 || clockUv.y > 1.0) {
        return color;
    }
    vec4 clockSample = texture(uClockTexture, clockUv);
    return mix(color, clockSample.rgb, clockSample.a * uClockOpacity);
}

// Draws the sharp subject back over the clock, which is what sells "the clock
// is behind them". Fades with the clock itself, so the subject is not left
// re-sharpened over a stylised background once the clock has gone.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, float subjectMask) {
    if (uClockEnabled <= 0.5 || uClockDepth <= 0.5 || uClockOpacity <= 0.0) {
        return color;
    }
    float coverage = smoothstep(0.30, 0.72, subjectMask);
    return mix(color, subjectColor, coverage * uClockOpacity);
}

// Raw subject coverage for the clock's depth effect.
//
// Deliberately not foregroundProtection() above: that one returns 0 whenever
// the Halftone effect's own "background only" mode is off, because it exists
// to decide where the halftone is suppressed. The clock's depth is a separate
// user setting and must work with background-only switched off, so it reads
// the mask directly.
float clockSubjectMask(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(uSubjectMask, 0));
    float mask = texture(uSubjectMask, uv).r;
    mask = max(mask, texture(uSubjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    mask = max(mask, texture(uSubjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    return mask;
}

void main() {
    float effectStrength = 1.0 - clamp(uBlurStrength, 0.0, 1.0);

    vec3 sharp = texture(uTextureSharp, vTexCoord).rgb;
    vec2 texSize = vec2(textureSize(uTextureSharp, 0));

    vec3 halftoneOutput;

    if (uDotSize < 0.1) {
        if (uGrayscale > 0.5) {
            float luma = dot(sharp, vec3(0.299, 0.587, 0.114));
            halftoneOutput = vec3(luma);
        } else {
            halftoneOutput = sharp;
        }
    } else {
        if (uGrayscale > 0.5) {
            float luma = dot(sharp, vec3(0.299, 0.587, 0.114));
            float kDot = halftoneChannel(vTexCoord, radians(45.0), 1.0 - luma, texSize, uDotSize);
            halftoneOutput = vec3(1.0 - kDot);
        } else {
            vec3 cmy = 1.0 - sharp;
            float cDot = halftoneChannel(vTexCoord, radians(15.0), cmy.r, texSize, uDotSize);
            float mDot = halftoneChannel(vTexCoord, radians(75.0), cmy.g, texSize, uDotSize);
            float yDot = halftoneChannel(vTexCoord, radians(0.0), cmy.b, texSize, uDotSize);
            halftoneOutput = 1.0 - vec3(cDot, mDot, yDot);
        }
    }

    vec3 finalColor = mix(sharp, halftoneOutput, effectStrength);
    finalColor = mix(finalColor, vec3(0.0), uDimLevel * effectStrength);
    finalColor = mix(finalColor, sharp, foregroundProtection(vTexCoord));

    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(finalColor, sharp, clockSubjectMask(vTexCoord));

    fragColor = vec4(finalColor, 1.0);
}
