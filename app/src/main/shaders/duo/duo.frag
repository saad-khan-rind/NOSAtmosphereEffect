#version 450

layout(set = 0, binding = 0) uniform sampler2D lockPhoto;
layout(set = 0, binding = 1) uniform sampler2D homePhoto;

layout(location = 0) in vec2 vLockCoord;
layout(location = 1) in vec2 vHomeCoord;
layout(location = 2) in vec2 vScreen;
layout(location = 0) out vec4 fragColor;

// render: progress, dim, surface width / height, style (DuoStyle's order).
// scroll: see duo.vert.
layout(push_constant) uniform DuoParams {
    vec4 render;
    vec4 scroll;
} params;

// Duo: the lock screen's photo turns into the home screen's as PROGRESS runs
// from 0 (locked) to 1 (unlocked), in one of four ways (STYLE, DuoStyle's
// order): 0 a crossfade, 1 the home photo climbing the screen behind a soft
// edge, 2 the home photo opening in a circle from the bottom, 3 the home photo
// coming through in fine grains. Every style shows only the lock photo at 0
// and only the home photo at 1. The Vulkan copy in src/main/shaders/duo and
// the GLES copy in assets/shaders/duo share this body; keep them in step.

// A bit-mixing hash rather than fract(sin(...)), which collapses into a
// visible mesh on GPUs with a low-precision sin.
float hash(vec2 p) {
    uvec2 q = uvec2(ivec2(floor(p)) + ivec2(65536));
    uint h = (q.x * 1597334677u) ^ (q.y * 3812015801u);
    h = (h ^ (h >> 16u)) * 2246822519u;
    h ^= h >> 13u;
    return float(h & 0xFFFFu) / 65535.0;
}

// How much of the home photo shows at this pixel, 0 to 1.
float homeShare(vec2 screen, float progress, float aspect, int style) {
    float p = clamp(progress, 0.0, 1.0);
    if (style == 1) {
        float edge = 0.12;
        // The line the home photo has climbed to, from below the screen to above it.
        float line = mix(-edge, 1.0 + edge, p);
        return 1.0 - smoothstep(line - edge, line + edge, 1.0 - screen.y);
    }
    if (style == 2) {
        float edge = 0.14;
        // Distances in screen heights, so the circle is round on any shape.
        vec2 d = (screen - vec2(0.5, 1.0)) * vec2(aspect, 1.0);
        float radius = p * (length(vec2(0.5 * aspect, 1.0)) + edge);
        return 1.0 - smoothstep(radius - edge, radius, length(d));
    }
    if (style == 3) {
        float grain = hash(floor(screen * vec2(aspect, 1.0) * 160.0));
        return smoothstep(grain - 0.15, grain + 0.15, p * 1.3 - 0.15);
    }
    return p * p * (3.0 - 2.0 * p);
}

void main() {
    vec3 lock = texture(lockPhoto, vLockCoord).rgb;
    vec3 home = texture(homePhoto, vHomeCoord).rgb;
    vec3 color = mix(lock, home, homeShare(vScreen, params.render.x, params.render.z, int(params.render.w + 0.5)));
    fragColor = vec4(color * (1.0 - clamp(params.render.y, 0.0, 1.0)), 1.0);
}
