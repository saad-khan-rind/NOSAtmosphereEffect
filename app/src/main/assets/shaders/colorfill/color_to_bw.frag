#version 300 es
precision highp float;
in vec2 vTexCoord;
// Screen-locked, unaffected by the wallpaper's scroll window — the clock
// overlay is positioned against the physical screen.
in vec2 vEffectCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform float uBlurStrength; // 0.0 (Unlocked/Color) -> 1.0 (Locked/B&W)
uniform vec2 uOrigin;
uniform float uAspectRatio;
uniform float uDimLevel;

// Reverse reveal reuses the same noisy paint front with grayscale as the fill.

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        v += amp * vnoise(p);
        p = p * 2.03 + 7.1;
        amp *= 0.5;
    }
    return v;
}

const vec2  DROP_DIR[5]  = vec2[5](
    vec2( 0.80,  0.60), vec2(-0.55,  0.84), vec2( 0.28, -0.96),
    vec2(-0.90, -0.30), vec2( 0.97,  0.05)
);
const float DROP_F[5]    = float[5](0.52, 0.66, 0.60, 0.74, 0.83);
const float DROP_SIZE[5] = float[5](0.11, 0.08, 0.13, 0.07, 0.06);

float paintCoverage(vec2 uv, vec2 origin, float aspect, float progress, out float rim) {
    rim = 0.0;
    if (progress <= 0.002) return 0.0;
    if (progress >= 0.998) return 1.0;

    float reach = 0.0;
    reach = max(reach, distance(origin, vec2(0.0,    0.0)));
    reach = max(reach, distance(origin, vec2(aspect, 0.0)));
    reach = max(reach, distance(origin, vec2(0.0,    1.0)));
    reach = max(reach, distance(origin, vec2(aspect, 1.0)));

    vec2 d = uv - origin;
    float dist = length(d);
    float ang = atan(d.y, d.x);
    vec2 circ = vec2(cos(ang), sin(ang));

    float R = progress * reach * 1.42;

    float lobe = fbm(circ * 2.1 + vec2(9.0, progress * 1.2));
    float fingers = fbm(uv * 7.5 + circ * 1.7);

    float front = R * (0.74 + 0.34 * lobe) + (fingers - 0.5) * 0.13 * reach;

    float aa = mix(0.06, 0.012, progress) * (reach + 0.25);
    float cover = 1.0 - smoothstep(front - aa, front + aa, dist);

    for (int i = 0; i < 5; i++) {
        float f = DROP_F[i];
        vec2 c = origin + DROP_DIR[i] * (f * reach);
        float appear = smoothstep(f - 0.20, f - 0.02, progress);
        float r = DROP_SIZE[i] * reach * appear;
        if (r > 0.0001) {
            float dd = length(uv - c);
            float dn = (fbm(uv * 11.0 + float(i) * 3.7) - 0.5) * 0.25;
            cover = max(cover, 1.0 - smoothstep(r * (0.6 + dn), r, dd));
        }
    }

    rim = (1.0 - smoothstep(0.0, aa * 3.5, abs(dist - front))) * cover * (1.0 - progress);

    return clamp(cover, 0.0, 1.0);
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

void main() {
    vec4 color = texture(uTextureSharp, vTexCoord);
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 bwColor = vec3(gray);

    vec2 uv = vTexCoord;
    uv.x *= uAspectRatio;
    vec2 origin = uOrigin;
    origin.x *= uAspectRatio;

    float progress = uBlurStrength;

    float rim;
    float cover = paintCoverage(uv, origin, uAspectRatio, progress, rim);

    vec3 finalColor = mix(color.rgb, bwColor, cover);
    finalColor += rim * 0.06;

    finalColor *= mix(1.0, 1.0 - uDimLevel, uBlurStrength);

    finalColor = compositeClock(finalColor, vEffectCoord);

    fragColor = vec4(finalColor, color.a);
}
