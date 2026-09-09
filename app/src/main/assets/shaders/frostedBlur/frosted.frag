#version 300 es
precision highp float;
// hashU needs 32-bit ints; ES 3.00 defaults to mediump (only 16 bits guaranteed).
precision highp int;

in vec2 vTexCoord;
// Screen-locked, unaffected by the wallpaper's scroll window — the clock
// overlay is positioned against the physical screen.
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;

uniform float uAspectRatio;

uniform float uBlurStrength;
uniform float uDimLevel;
uniform float uEnableNoise;
uniform float uNoiseScale;
uniform float uNoiseStrength;

// App-drawer / recents blur, driven by wallpaper visibility. 0 = in view, 1 = hidden.
uniform float uDrawerBlur;

// Bit-mixing hash, replacing fract(sin(dot(...))) -- that idiom collapses to a repeating pattern
// at the coordinate magnitudes this grain grid produces (see #85).
// uint overflow is defined wrapping in GLSL ES.
uint hashU(uvec2 p) {
    uint h = p.x * 73856093u ^ p.y * 19349663u;
    h ^= h >> 13;
    h *= 0x85ebca6bu;
    h ^= h >> 16;
    return h;
}

float random(vec2 co) {
    return float(hashU(uvec2(co)) & 0xFFFFFFu) / float(0x1000000u);
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
    float t = clamp(uBlurStrength, 0.0, 1.0);

    vec3 sharp = textureLod(uTextureSharp, vTexCoord, t * 4.0).rgb;

    vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;

    vec3 finalColor = mix(sharp, frosted, t);

    finalColor = mix(finalColor, vec3(0.0), uDimLevel * t);

    if (uEnableNoise > 0.5) {
        vec2 noiseUV = vTexCoord;
        noiseUV.x *= uAspectRatio;
        vec2 grainUV = floor(noiseUV * uNoiseScale);
        float noise = random(grainUV);
        float noiseVisibility = smoothstep(0.4, 1.0, t);
        finalColor += vec3(noise * uNoiseStrength * noiseVisibility);
    }

    // App-drawer / recents: reverse Frosted sets this to 1 when out of view, blending
    // toward the frosted image so the drawer shows a blur. In view -> 0 -> sharp.
    finalColor = mix(finalColor, frosted, clamp(uDrawerBlur, 0.0, 1.0));

    vec3 beforeClock = finalColor;
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(finalColor, beforeClock, vTexCoord);

    fragColor = vec4(finalColor, 1.0);
}