#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D frame;

// x: mode, y: amount, z: surface width / height.
layout(push_constant) uniform WakeParams {
    vec4 wake;
} params;

#define SAMPLE(uv) texture(frame, clamp(uv, 0.0, 1.0)).rgb

// The lock screen's wake transition, drawn over the finished frame (every
// effect, clock included) while it runs. Keep in step with the GLES copy in
// assets/shaders/wake/wake.frag.
//   mode 1: colour spreads out from the centre over a grey frame
//   mode 2: the frame sharpens out of a light blur
// amount runs from 1 (start) to 0 (done).
vec3 wake(vec2 uv, int mode, float amount, float aspect) {
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
    fragColor = vec4(wake(vUv, int(params.wake.x + 0.5), params.wake.y, params.wake.z), 1.0);
}
