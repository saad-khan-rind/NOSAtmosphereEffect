#version 450

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 fragColor;

layout(set = 0, binding = 0) uniform sampler2D frame;

// wake: mode, amount, surface width / height, param.
// params: the style's three more values (see wake()), unused.
layout(push_constant) uniform WakeParams {
    vec4 wake;
    vec4 params;
} params;

#define SAMPLE(uv) texture(frame, clamp(uv, 0.0, 1.0)).rgb
// Up the screen: Vulkan's frame runs top to bottom.
#define UP(y) (1.0 - (y))
const float UP_SIGN = -1.0;

// The lock screen's wake transition, drawn over the finished frame (every
// effect, clock included) while it runs. The Vulkan copy in
// src/main/shaders/wake/wake.frag and the GLES copy in
// assets/shaders/wake/wake.frag share this body; keep them in step.
//   mode 1: colour spreads out from the centre over a grey frame
//   mode 2: the frame sharpens out of a light blur
//   mode 3: the corners spring back from [param] (out past the edges if
//           positive, in towards the middle, over black, if negative)
//   mode 4: the frame, offset ([params].xy, screen heights) and turned
//           ([params].z, radians), mirrored at its edges
//   mode 5: a ripple runs out from the centre, [param] deep
//   mode 6: broken rows and blocks and split colour, [param] strong
//   mode 7: [param] strips open in turn over a dimmed copy of the frame,
//           down the screen, or across it when [params].x is 1
//   mode 8: the frame breathes in from dim, swelling [param] past full
//   mode 9: black (WakeTransition.blackout)
//   mode 10: twisted from the centre by [param] radians, unwinding
//   mode 11: a wave [param] deep running from the centre line out to two
//            opposite edges, left and right, or top and bottom when [params].x is 1
//   mode 12: a surge of small bubbles rising from the bottom, [param] strong
//   mode 13: the picture grown from a card [param] of its size, over a blur
// amount runs from 1 (start) to 0 (done).

// A bit-mixing hash rather than fract(sin(...)), which collapses into a
// visible mesh on GPUs with a low-precision sin.
float hash(vec2 p) {
    uvec2 q = uvec2(ivec2(floor(p)) + ivec2(65536));
    uint h = (q.x * 1597334677u) ^ (q.y * 3812015801u);
    h = (h ^ (h >> 16u)) * 2246822519u;
    h ^= h >> 13u;
    return float(h & 0xFFFFu) / 65535.0;
}

// Where Venetian blind and Breathe start: the frame dimmed, never black, so
// the wake never opens on a black screen.
const float WAKE_FLOOR = 0.3;

// One layer of Bubble surge's bubbles, in cells [cells] to a screen height:
// the bend each bubble gives the picture (xy, before strength) and its rim (z).
vec3 bubbles(vec2 uv, float aspect, float t, float amount, float band, float cells, float seed) {
    vec2 g = vec2(uv.x * aspect, UP(uv.y)) * cells;
    float rise = t * cells * 0.55;
    g.y -= rise;
    vec2 id = floor(g);
    vec2 f = fract(g) - 0.5;
    float cellUp = (id.y + 0.5 + rise) / cells;
    float density = (1.0 - smoothstep(0.0, 0.42, abs(cellUp - band))) * 0.97 +
        0.35 * amount * step(cellUp, band);
    float present = step(1.0 - density, hash(id + seed));
    vec2 centre = (vec2(hash(id + seed + 3.0), hash(id + seed + 7.0)) - 0.5) * 0.3;
    float r = mix(0.2, 0.44, hash(id + seed + 13.0));
    vec2 d = f - centre;
    float dist = length(d);
    float inside = present * (1.0 - smoothstep(r - 0.04, r, dist));
    vec2 bend = d / max(r, 1e-3) * (1.0 - dist / r) * inside * 0.02;
    return vec3(bend, inside * smoothstep(r * 0.6, r, dist));
}

vec3 wake(vec2 uv, int mode, float amount, float aspect, float param, vec3 params) {
    float t = 1.0 - amount;
    if (mode == 9) return vec3(0.0);
    if (mode == 3) {
        // Each axis pulled by how far along the other it is, so the corners
        // move most and the middle lines not at all.
        vec2 n = uv * 2.0 - 1.0;
        // Weighted by the other axis itself, not its square, so the pull
        // reaches well in towards the middle, not just the outer corners.
        vec2 from = n * (1.0 - param * amount * abs(n.yx)) * 0.5 + 0.5;
        if (any(lessThan(from, vec2(0.0))) || any(greaterThan(from, vec2(1.0)))) return vec3(0.0);
        return SAMPLE(from);
    }
    if (mode == 4) {
        // In screen heights about the centre, so a turn keeps the photo's shape.
        vec2 p = (uv - 0.5) * vec2(aspect, 1.0) - params.xy;
        float c = cos(params.z);
        float s = sin(params.z);
        vec2 from = vec2(c * p.x + s * p.y, -s * p.x + c * p.y) / vec2(aspect, 1.0) + 0.5;
        return SAMPLE(abs(mod(from + 1.0, 2.0) - 1.0));
    }
    if (mode == 5) {
        // One wave travelling out past the corners, fading as it goes.
        vec2 d = (uv - 0.5) * vec2(aspect, 1.0);
        float dist = length(d);
        float front = t * (length(vec2(aspect, 1.0) * 0.5) + 0.25);
        float x = (dist - front) / 0.14;
        float wave = sin(x * 6.2831853) * exp(-x * x * 1.5);
        vec2 towards = dist > 1e-4 ? d / dist : vec2(0.0);
        vec2 from = uv - towards * (param * amount * wave) / vec2(aspect, 1.0);
        return SAMPLE(abs(mod(from + 1.0, 2.0) - 1.0));
    }
    if (mode == 6) {
        // A new burst some twenty times through the wake, weaker each time.
        float k = param * amount;
        float seed = floor(t * 20.0);
        float row = floor(uv.y * 28.0);
        float torn = step(0.62, hash(vec2(row, seed)));
        float shift = torn * (hash(vec2(row, seed + 7.0)) - 0.5) * 0.3 * k;
        vec2 cell = floor(uv * vec2(14.0, 28.0));
        float broken = step(1.0 - 0.3 * k, hash(cell + vec2(seed * 3.0, 11.0)));
        vec2 p = uv + vec2(shift, 0.0);
        float split = 0.025 * k;
        vec3 color = vec3(SAMPLE(p + vec2(split, 0.0)).r, SAMPLE(p).g, SAMPLE(p - vec2(split, 0.0)).b);
        vec3 noise = vec3(hash(cell + vec2(seed, 5.0)), hash(cell + vec2(seed, 9.0)), hash(cell + vec2(seed, 13.0)));
        return mix(color, noise, broken * 0.7);
    }
    if (mode == 10) {
        // Twisted about the centre, most in the middle and not at all at the
        // corners, unwinding as it settles. Mirrored at the edges: never black.
        vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
        float reach = length(vec2(aspect, 1.0) * 0.5);
        float f = 1.0 - clamp(length(p) / reach, 0.0, 1.0);
        float angle = param * amount * f * f;
        float c = cos(angle);
        float s = sin(angle);
        vec2 from = vec2(c * p.x - s * p.y, s * p.x + c * p.y) / vec2(aspect, 1.0) + 0.5;
        return SAMPLE(abs(mod(from + 1.0, 2.0) - 1.0));
    }
    if (mode == 11) {
        // A cloth snapped by its two opposite edges: a wave starts on the
        // centre line and runs out to both edges, dying away as it goes. The
        // picture rolls with it and its slopes are shaded, so it reads as a
        // surface, not a slide.
        float along = params.x > 0.5 ? uv.y : uv.x;
        float e = abs(along - 0.5);
        float x = (e - t * 0.75) / 0.16;
        float envelope = exp(-x * x) * amount;
        float phase = x * 4.712389;
        float height = sin(phase) * envelope;
        float slope = cos(phase) * envelope;
        float outwards = along < 0.5 ? -1.0 : 1.0;
        vec2 axis = params.x > 0.5 ? vec2(0.0, outwards) : vec2(outwards, 0.0);
        vec2 from = uv - axis * height * param;
        vec3 cloth = SAMPLE(abs(mod(from + 1.0, 2.0) - 1.0));
        return cloth * (1.0 + slope * 0.35 * min(param / 0.05, 2.0));
    }
    if (mode == 12) {
        // A dense, fizzing surge of small bubbles rising from the bottom to
        // the top: two layers of different sizes, each bubble bending the
        // picture behind it like a lens, with a bright rim. The surge stays
        // on screen for nearly the whole wake, a lighter fizz trailing it.
        float band = 1.45 * t - 0.2;
        vec3 fine = bubbles(uv, aspect, t, amount, band, 46.0, 0.0);
        vec3 coarse = bubbles(uv, aspect, t, amount, band, 26.0, 50.0);
        vec2 bend = fine.xy + coarse.xy;
        vec2 from = uv - vec2(bend.x / aspect, bend.y * UP_SIGN) * param;
        return SAMPLE(from) + (fine.z + coarse.z) * 0.35 * param;
    }
    if (mode == 13) {
        // The picture grows out of a small card in the middle of the screen,
        // with a slight bounce at the end ([param] is its size this frame),
        // over a heavily blurred copy of itself, never black.
        float size = max(param, 1e-3);
        vec2 p = (uv - 0.5) * vec2(aspect, 1.0);
        vec2 halfSize = vec2(aspect, 1.0) * 0.5 * size;
        float corner = 0.05 * size;
        vec2 q = abs(p) - halfSize + corner;
        float outside = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - corner;
        float card = 1.0 - smoothstep(-0.002, 0.002, outside);
        vec3 inner = SAMPLE((uv - 0.5) / size + 0.5);
        if (card >= 1.0) return inner;
        vec3 backdrop = vec3(0.0);
        float total = 0.0;
        for (int i = 0; i < 32; i++) {
            float r = sqrt((float(i) + 0.5) / 32.0);
            float a = float(i) * 2.39996;
            backdrop += SAMPLE(uv + vec2(cos(a), sin(a) * aspect) * r * 0.09);
            total += 1.0;
        }
        backdrop = backdrop / total * 0.7;
        return mix(backdrop, inner, card);
    }
    vec3 color = SAMPLE(uv);
    if (mode == 7) {
        // Each strip opens from its own middle, the next a little after.
        float count = max(param, 1.0);
        float along = (params.x > 0.5 ? uv.x : uv.y) * count;
        float strip = floor(along);
        float edge = abs(fract(along) - 0.5) * 2.0;
        float open = smoothstep(0.0, 1.0, clamp((t - strip / count * 0.5) * 2.0, 0.0, 1.0));
        float soft = max(fwidth(edge), 1e-3);
        float shown = smoothstep(edge - soft, edge + soft, open * (1.0 + soft));
        // Behind the strips, the frame dimmed rather than black, lightening as they open.
        return color * mix(WAKE_FLOOR + (1.0 - WAKE_FLOOR) * 0.5 * t, 1.0, shown);
    }
    if (mode == 8) {
        // Up from dim, a gentle swell past full brightness, then settled.
        float rise = mix(WAKE_FLOOR, 1.0, smoothstep(0.0, 0.7, t));
        float swell = param * sin(3.14159265 * clamp((t - 0.3) / 0.7, 0.0, 1.0));
        return color * (rise + swell);
    }
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
    fragColor = vec4(wake(vUv, int(params.wake.x + 0.5), params.wake.y, params.wake.z, params.wake.w, params.params.xyz), 1.0);
}
