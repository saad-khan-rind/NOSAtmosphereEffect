#version 300 es
precision highp float;

in vec2 vUv;
out vec4 fragColor;

uniform sampler2D uFrame;
uniform int uMode;
uniform float uAmount;
uniform float uAspect;
uniform float uStretch;
uniform vec3 uShake;

#define SAMPLE(uv) texture(uFrame, clamp(uv, 0.0, 1.0)).rgb

// The lock screen's wake transition, drawn over the finished frame (every
// effect, clock included) while it runs. Keep in step with the Vulkan copy in
// src/main/shaders/wake/wake.frag.
//   mode 1: colour spreads out from the centre over a grey frame
//   mode 2: the frame sharpens out of a light blur
//   mode 3: the corners spring back from [stretch] (out past the edges if
//           positive, in towards the middle, over black, if negative)
//   mode 4: the frame, offset and turned by [shake], mirrored at its edges
// amount runs from 1 (start) to 0 (done).
vec3 wake(vec2 uv, int mode, float amount, float aspect, float stretch, vec3 shake) {
    if (mode == 3) {
        // Each axis pulled by how far along the other it is, so the corners
        // move most and the middle lines not at all. Never folds below 1/3.
        vec2 n = uv * 2.0 - 1.0;
        vec2 from = n * (1.0 - stretch * amount * n.yx * n.yx) * 0.5 + 0.5;
        if (any(lessThan(from, vec2(0.0))) || any(greaterThan(from, vec2(1.0)))) return vec3(0.0);
        return SAMPLE(from);
    }
    if (mode == 4) {
        // In screen heights about the centre, so a turn keeps the photo's shape.
        vec2 p = (uv - 0.5) * vec2(aspect, 1.0) - shake.xy;
        float c = cos(shake.z);
        float s = sin(shake.z);
        vec2 from = vec2(c * p.x + s * p.y, -s * p.x + c * p.y) / vec2(aspect, 1.0) + 0.5;
        return SAMPLE(abs(mod(from + 1.0, 2.0) - 1.0));
    }
    vec3 color = SAMPLE(uv);
    if (mode == 2) {
        // A golden-angle disc, weighted towards the middle: smooth with few taps.
        float radius = amount * 0.03;
        vec3 sum = color;
        float total = 1.0;
        for (int i = 0; i < 24; i++) {
            float r = sqrt((float(i) + 0.5) / 24.0);
            float a = float(i) * 2.39996;
            vec2 offset = vec2(cos(a), sin(a) * aspect) * r * radius;
            float weight = 1.0 - 0.6 * r;
            sum += SAMPLE(uv + offset) * weight;
            total += weight;
        }
        return sum / total;
    }
    if (mode == 1) {
        // Distances in screen heights, so the circle is round on any shape.
        vec2 d = (uv - 0.5) * vec2(aspect, 1.0);
        float edge = 0.18;
        float reach = length(vec2(aspect, 1.0) * 0.5) + edge;
        float radius = (1.0 - amount) * reach;
        float inside = 1.0 - smoothstep(radius - edge, radius, length(d));
        float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
        return mix(vec3(luma), color, inside);
    }
    return color;
}

void main() {
    fragColor = vec4(wake(vUv, uMode, uAmount, uAspect, uStretch, uShake), 1.0);
}
