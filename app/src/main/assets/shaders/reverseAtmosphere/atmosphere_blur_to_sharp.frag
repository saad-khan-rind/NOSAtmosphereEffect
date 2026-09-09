#version 300 es
precision highp float;
// hashU needs 32-bit ints; ES 3.00 defaults to mediump (only 16 bits guaranteed).
precision highp int;

in vec2 vTexCoord;
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;
uniform sampler2D uSubjectMask;

#define MAX_BLOBS 16
uniform vec3 uBlobColors[MAX_BLOBS];
uniform vec2 uBlobPositions[MAX_BLOBS];
uniform float uBlobSizes[MAX_BLOBS];
uniform int uBlobCount;
uniform float uAspectRatio;

uniform float uBlurStrength;
uniform float uDimLevel;
uniform float uEnableNoise;
uniform float uNoiseScale;
uniform float uNoiseStrength;

uniform float uSaturation;
uniform float uContrast;

uniform float uAtmosphereGlassEnabled;
uniform float uGlassLineCount;
uniform float uGlassLineThickness;
uniform float uScrollWindowX;
uniform float uBackgroundOnly;
uniform float uHasSubject;

// App-drawer / recents blur, driven by wallpaper visibility. 0 = in view, 1 = hidden.
uniform float uDrawerBlur;

const float TWO_PI = 6.28318530718;

vec3 sampleGlassSoftened(vec2 sampleUv, vec2 texel) {
    vec2 radius = vec2(texel.x * 1.15, 0.0);
    return
        texture(uTextureSharp, sampleUv).rgb * 0.58 +
        texture(uTextureSharp, clamp(sampleUv + radius, 0.0, 1.0)).rgb * 0.21 +
        texture(uTextureSharp, clamp(sampleUv - radius, 0.0, 1.0)).rgb * 0.21;
}

float sampleSubject(vec2 sampleUv) {
    vec2 stepSize = 2.0 / vec2(textureSize(uSubjectMask, 0));
    float mask = texture(uSubjectMask, sampleUv).r;
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv + vec2(stepSize.x, 0.0), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv - vec2(stepSize.x, 0.0), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv + vec2(0.0, stepSize.y), 0.0, 1.0)
        ).r
    );
    mask = max(
        mask,
        texture(
            uSubjectMask,
            clamp(sampleUv - vec2(0.0, stepSize.y), 0.0, 1.0)
        ).r
    );
    return mask;
}

vec3 sampleStaticAtmosphereGlass() {
    float count = max(1.0, floor(uGlassLineCount + 0.5));
    float screenX = clamp(vEffectCoord.x, 0.0, 0.999999);
    float lanePosition = screenX * count;
    float localRib = fract(lanePosition);
    float wave = sin(TWO_PI * localRib);
    float profileExponent = mix(
        1.80,
        0.25,
        clamp(uGlassLineThickness, 0.0, 1.0)
    );
    float profile = sign(wave) * pow(abs(wave), profileExponent);

    float scrollWindow = uScrollWindowX <= 0.0 ? 1.0 : uScrollWindowX;
    float displacement = profile * (1.08 * scrollWindow / count);
    vec2 glassUv = vec2(
        clamp(vTexCoord.x + displacement, 0.0, 1.0),
        vTexCoord.y
    );
    vec2 texel = 1.0 / vec2(textureSize(uTextureSharp, 0));
    vec3 sharpColor = texture(uTextureSharp, vTexCoord).rgb;
    vec3 refractedColor = texture(uTextureSharp, glassUv).rgb;
    vec3 glassColor = mix(
        refractedColor,
        sampleGlassSoftened(glassUv, texel),
        0.72
    );
    float ribFaceLighting = 0.016 * (2.0 * localRib - 1.0);
    glassColor += vec3(ribFaceLighting);

    float rightInnerDistance = 1.0 - localRib;
    float rightInnerGlow = 1.0 - smoothstep(
        0.0,
        0.25,
        rightInnerDistance
    );
    glassColor = clamp(
        glassColor + vec3(0.036 * rightInnerGlow),
        0.0,
        1.0
    );

    float backgroundCoverage = 1.0;
    if (uBackgroundOnly > 0.5) {
        if (uHasSubject > 0.5) {
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
    return mix(sharpColor, glassColor, backgroundCoverage);
}

vec3 adjustColor(vec3 color) {
    color = (color - 0.5) * max(uContrast, 0.0) + 0.5;
    float luminance = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(vec3(luminance), color, max(uSaturation, 0.0));
    return clamp(color, 0.0, 1.0);
}

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

void main() {
    float t = uBlurStrength;
    vec2 uv = vTexCoord;
    uv.x *= uAspectRatio;

    vec3 cloudSum = vec3(0.0);
    float cloudWeight = 0.0;

    for(int i = 0; i < MAX_BLOBS; i++) {
        if (i >= uBlobCount) break;
        vec2 pos = uBlobPositions[i];
        pos.x *= uAspectRatio;
        float dist = length(uv - pos);

        float w = uBlobSizes[i] / (pow(dist, 2.0) + 0.05);
        cloudSum += adjustColor(uBlobColors[i]) * w;
        cloudWeight += w;
    }

    vec3 muddyBackground = vec3(0.0);
    if (cloudWeight > 0.0) {
        muddyBackground = cloudSum / cloudWeight;
    }

    float blurPhase = smoothstep(0.0, 0.2, t);
    float cloudMorph = smoothstep(0.18, 0.5, t);

    vec3 sharp = texture(uTextureSharp, vTexCoord).rgb;
    if (uAtmosphereGlassEnabled > 0.5) {
        sharp = sampleStaticAtmosphereGlass();
    }
    vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;

    vec3 currentBg = mix(sharp, frosted, blurPhase);

    if (t > 0.18) {
        currentBg = mix(currentBg, muddyBackground, cloudMorph);
    }

    vec3 finalColor = currentBg;

    float blobOpacity = smoothstep(0.15, 0.3, t);

    if (blobOpacity > 0.01 && uBlobCount > 0) {
        for(int i = 0; i < MAX_BLOBS; i++) {
            if (i >= uBlobCount) break;

            vec2 pos = uBlobPositions[i];
            pos.x *= uAspectRatio;

            vec2 delta = uv - pos;
            float dist = length(delta);
            float radius = uBlobSizes[i];

            float effectiveRadius = radius;
            float alpha = 1.0 - smoothstep(0.0, effectiveRadius, dist);

            alpha *= blobOpacity;

            if (alpha > 0.0) {
                finalColor = mix(finalColor, adjustColor(uBlobColors[i]), alpha);
            }
        }
    }

    finalColor = mix(finalColor, vec3(0.0), uDimLevel * t);

    if (uEnableNoise > 0.5) {
        vec2 grainUV = floor(uv * uNoiseScale);
        float noise = random(grainUV);
        float noiseVisibility = smoothstep(0.0, 0.4, t);
        finalColor += vec3(noise * uNoiseStrength * noiseVisibility);
    }

    // App-drawer / recents: when the wallpaper is out of view (screen still on) the
    // engine sets this to 1, blending toward the clean blurred image so a translucent
    // drawer shows a strong blur. In view -> 0 -> sharp.
    finalColor = mix(finalColor, frosted, clamp(uDrawerBlur, 0.0, 1.0));

    // Composited after the drawer blur so the clock is never washed out by
    // it, then the sharp subject goes back on top when depth is on.
    finalColor = compositeClock(finalColor, vEffectCoord);
    finalColor = applyClockDepth(
        finalColor,
        texture(uTextureSharp, vTexCoord).rgb,
        sampleSubject(vTexCoord)
    );

    fragColor = vec4(finalColor, 1.0);
}
