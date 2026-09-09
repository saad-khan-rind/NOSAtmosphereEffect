#version 300 es
precision highp float;

in vec2 vTexCoord;
// Screen-locked, unaffected by the wallpaper's scroll window — the clock
// overlay is positioned against the physical screen.
in vec2 vEffectCoord;
out vec4 fragColor;

// Canvas transition: a restrained pale sketch on black crossfades with the
// original fitted wallpaper. uReverse swaps the lock and home states.

uniform sampler2D uTextureSharp;
uniform sampler2D uLineTex;

uniform float uBlurStrength;
uniform float uReverse;
uniform float uDimLevel;
uniform float uLineWidth;
uniform float uLineMax;

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

// Subject mask for the clock's depth effect. These effects have no
// "background only" mode of their own, so this binding exists purely for the
// clock; uClockDepth is 0 whenever no real mask is bound, and the sampler is
// then never read.
uniform sampler2D uClockSubjectMask;

float clockSubjectCoverage(vec2 uv) {
    vec2 stepSize = 2.0 / vec2(textureSize(uClockSubjectMask, 0));
    float mask = texture(uClockSubjectMask, uv).r;
    mask = max(mask, texture(uClockSubjectMask, clamp(uv + vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uClockSubjectMask, clamp(uv - vec2(stepSize.x, 0.0), 0.0, 1.0)).r);
    mask = max(mask, texture(uClockSubjectMask, clamp(uv + vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    mask = max(mask, texture(uClockSubjectMask, clamp(uv - vec2(0.0, stepSize.y), 0.0, 1.0)).r);
    return smoothstep(0.30, 0.72, mask);
}

// Draws the subject back over the clock, so the clock reads as sitting behind
// them.
//
// [subjectColor] is the frame as it looked BEFORE the clock was composited,
// not the untouched photo. Atmosphere, Glass and Halftone use the sharp photo
// because their backgrounds are blurred or stylised, so a sharp subject reads
// as depth. Here it would read as a cut-out instead: a full-colour subject
// over Colour Fill's monochrome end, or a photographic subject over Sketch's
// line art. Re-drawing what the effect had already produced keeps the subject
// looking exactly like the rest of the frame, which is what actually sells
// the occlusion.
vec3 applyClockDepth(vec3 color, vec3 subjectColor, vec2 maskUv) {
    if (uClockEnabled <= 0.5 || uClockDepth <= 0.5 || uClockOpacity <= 0.0) {
        return color;
    }
    return mix(
        color,
        subjectColor,
        clockSubjectCoverage(maskUv) * uClockOpacity
    );
}

void main() {
    vec2 uv = vTexCoord;
    vec3 sharp = texture(uTextureSharp, uv).rgb;

    float progress = clamp(uBlurStrength, 0.0, 1.0);
    float imageAmount = mix(progress, 1.0 - progress, uReverse);

    float lineDistance = texture(uLineTex, uv).r * uLineMax;
    float width = mix(uLineWidth, uLineWidth * 0.78, imageAmount);
    float ink = 1.0 - smoothstep(width * 0.45, width * 0.45 + 1.1, lineDistance);

    float luma = dot(sharp, vec3(0.2126, 0.7152, 0.0722));
    vec3 inkColor = mix(vec3(0.76), vec3(0.96), smoothstep(0.12, 0.88, luma));
    vec3 sketch = inkColor * ink;

    float blend = smoothstep(0.02, 0.98, imageAmount);
    vec3 color = mix(sketch, sharp, blend);
    color = mix(color, vec3(0.0), uDimLevel * (1.0 - imageAmount));

    vec3 beforeClock = color;
    color = compositeClock(color, vEffectCoord);
    color = applyClockDepth(color, beforeClock, vTexCoord);

    fragColor = vec4(color, 1.0);
}
