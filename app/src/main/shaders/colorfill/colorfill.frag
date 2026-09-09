#version 450

layout(set = 0, binding = 0) uniform sampler2D wallpaperTexture;
layout(set = 0, binding = 1) uniform sampler2D clockTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec2 vEffectCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform ColorFillParams {
    vec4 render;
    vec4 position;
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

// Mirrors the GLES path in assets/shaders/colorfill/bw_to_color.frag; keep the two in step.
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

float hash(vec2 point) {
    point = fract(point * vec2(123.34, 345.45));
    point += dot(point, point + 34.345);
    return fract(point.x * point.y);
}

float valueNoise(vec2 point) {
    vec2 cell = floor(point);
    vec2 local = fract(point);
    local = local * local * (3.0 - 2.0 * local);
    float a = hash(cell);
    float b = hash(cell + vec2(1.0, 0.0));
    float c = hash(cell + vec2(0.0, 1.0));
    float d = hash(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}

float fbm(vec2 point) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int octave = 0; octave < 5; octave++) {
        value += amplitude * valueNoise(point);
        point = point * 2.03 + 7.1;
        amplitude *= 0.5;
    }
    return value;
}

const vec2 DROP_DIRECTION[5] = vec2[5](
    vec2(0.80, 0.60),
    vec2(-0.55, 0.84),
    vec2(0.28, -0.96),
    vec2(-0.90, -0.30),
    vec2(0.97, 0.05)
);
const float DROP_DISTANCE[5] = float[5](0.52, 0.66, 0.60, 0.74, 0.83);
const float DROP_SIZE[5] = float[5](0.11, 0.08, 0.13, 0.07, 0.06);

float paintCoverage(
    vec2 uv,
    vec2 origin,
    float aspect,
    float progress,
    out float rim
) {
    rim = 0.0;
    if (progress <= 0.002) return 0.0;
    if (progress >= 0.998) return 1.0;

    float reach = 0.0;
    reach = max(reach, distance(origin, vec2(0.0, 0.0)));
    reach = max(reach, distance(origin, vec2(aspect, 0.0)));
    reach = max(reach, distance(origin, vec2(0.0, 1.0)));
    reach = max(reach, distance(origin, vec2(aspect, 1.0)));

    vec2 delta = uv - origin;
    float distanceFromOrigin = length(delta);
    float angle = atan(delta.y, delta.x);
    vec2 circle = vec2(cos(angle), sin(angle));
    float radius = progress * reach * 1.42;
    float lobe = fbm(circle * 2.1 + vec2(9.0, progress * 1.2));
    float fingers = fbm(uv * 7.5 + circle * 1.7);
    float front = radius * (0.74 + 0.34 * lobe) +
        (fingers - 0.5) * 0.13 * reach;
    float antialiasWidth = mix(0.06, 0.012, progress) * (reach + 0.25);
    float coverage = 1.0 - smoothstep(
        front - antialiasWidth,
        front + antialiasWidth,
        distanceFromOrigin
    );

    for (int drop = 0; drop < 5; drop++) {
        float distanceFraction = DROP_DISTANCE[drop];
        vec2 center = origin + DROP_DIRECTION[drop] * (distanceFraction * reach);
        float appear = smoothstep(
            distanceFraction - 0.20,
            distanceFraction - 0.02,
            progress
        );
        float dropRadius = DROP_SIZE[drop] * reach * appear;
        if (dropRadius > 0.0001) {
            float dropDistance = length(uv - center);
            float noise = (fbm(uv * 11.0 + float(drop) * 3.7) - 0.5) * 0.25;
            coverage = max(
                coverage,
                1.0 - smoothstep(
                    dropRadius * (0.6 + noise),
                    dropRadius,
                    dropDistance
                )
            );
        }
    }

    rim = (
        1.0 - smoothstep(
            0.0,
            antialiasWidth * 3.5,
            abs(distanceFromOrigin - front)
        )
    ) * coverage * (1.0 - progress);
    return clamp(coverage, 0.0, 1.0);
}

void main() {
    vec4 color = texture(wallpaperTexture, vTexCoord);
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 monochrome = vec3(gray);

    float aspect = params.render.z;
    vec2 uv = vTexCoord;
    uv.x *= aspect;
    vec2 origin = params.position.xy;
    origin.x *= aspect;

    bool reverse = params.render.w > 0.5;
    float fillProgress = reverse ? params.render.x : 1.0 - params.render.x;
    float rim;
    float coverage = paintCoverage(
        uv,
        origin,
        aspect,
        fillProgress,
        rim
    );

    vec3 startColor = reverse ? color.rgb : monochrome;
    vec3 endColor = reverse ? monochrome : color.rgb;
    vec3 finalColor = mix(startColor, endColor, coverage);
    finalColor += rim * (reverse ? 0.06 : 0.10);
    finalColor *= mix(1.0, 1.0 - params.render.y, params.render.x);
    finalColor = compositeClock(finalColor, vEffectCoord);

    fragColor = vec4(finalColor, color.a);
}
