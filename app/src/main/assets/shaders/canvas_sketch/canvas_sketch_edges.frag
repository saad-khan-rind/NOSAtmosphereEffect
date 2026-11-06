#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

// Canvas contour bake. The subject mask supplies one clean silhouette. Color
// contours are retained only when they survive at two coarse image scales, so
// fabric weave, comic hatching, hair strands and sensor texture do not become a
// dense mesh of lines.

uniform sampler2D uTextureSharp;
uniform sampler2D uSubjectMask;
uniform vec2 uStep;
uniform vec2 uMaskStep;
uniform float uHasSubject;
uniform float uLod;
uniform float uThreshold;
uniform float uWeakRatio;

vec3 tap(vec2 uv, float lod) {
    return textureLod(uTextureSharp, clamp(uv, vec2(0.0), vec2(1.0)), lod).rgb;
}

// Returns (magnitude, direction.x, direction.y). The color structure tensor
// sees boundaries that a luma-only Sobel would miss.
vec3 gradientAt(vec2 uv, float lod) {
    vec2 s = uStep;
    vec3 tl = tap(uv + vec2(-s.x, -s.y), lod);
    vec3 tm = tap(uv + vec2( 0.0, -s.y), lod);
    vec3 tr = tap(uv + vec2( s.x, -s.y), lod);
    vec3 ml = tap(uv + vec2(-s.x,  0.0), lod);
    vec3 mr = tap(uv + vec2( s.x,  0.0), lod);
    vec3 bl = tap(uv + vec2(-s.x,  s.y), lod);
    vec3 bm = tap(uv + vec2( 0.0,  s.y), lod);
    vec3 br = tap(uv + vec2( s.x,  s.y), lod);

    vec3 gx = ((tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl)) * 0.25;
    vec3 gy = ((bl + 2.0 * bm + br) - (tl + 2.0 * tm + tr)) * 0.25;

    float jxx = dot(gx, gx);
    float jyy = dot(gy, gy);
    float jxy = dot(gx, gy);
    float disc = sqrt(max((jxx - jyy) * (jxx - jyy) + 4.0 * jxy * jxy, 0.0));
    float lambda = 0.5 * (jxx + jyy + disc);
    float magnitude = sqrt(max(lambda, 0.0)) * 0.83715789;
    float theta = 0.5 * atan(2.0 * jxy, jxx - jyy);
    return vec3(magnitude, cos(theta), sin(theta));
}

float maskAt(vec2 uv) {
    return textureLod(uSubjectMask, clamp(uv, vec2(0.0), vec2(1.0)), 0.0).r;
}

float subjectSilhouette(float center) {
    vec2 s = uMaskStep;
    float lo = center;
    float hi = center;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float value = maskAt(vTexCoord + vec2(float(x), float(y)) * s);
            lo = min(lo, value);
            hi = max(hi, value);
        }
    }

    float crossesBoundary = step(lo, 0.5) * step(0.5, hi);
    float centered = 1.0 - smoothstep(0.06, 0.26, abs(center - 0.5));
    return crossesBoundary * centered;
}

void main() {
    float interior = 1.0;
    float silhouette = 0.0;
    if (uHasSubject > 0.5) {
        float mask = maskAt(vTexCoord);
        interior = smoothstep(0.60, 0.82, mask);
        silhouette = subjectSilhouette(mask);
    }

    vec3 fine = gradientAt(vTexCoord, uLod);
    vec3 broad = gradientAt(vTexCoord, uLod + 0.85);

    // Fine marks must still exist after the second blur scale. This is what
    // removes Spider-Man webbing and similar repeated micro-detail while keeping
    // the face, body divisions and other large visual forms.
    float magnitude = min(fine.r, broad.r * 1.4);
    vec2 direction = normalize(broad.gb + vec2(1e-8));
    vec2 offset = direction * uStep;
    float before = gradientAt(vTexCoord - offset, uLod + 0.85).r;
    float after = gradientAt(vTexCoord + offset, uLod + 0.85).r;
    float crest = (broad.r >= before && broad.r > after) ? 1.0 : 0.0;
    float contour = magnitude * crest * interior;

    float strong = max(step(uThreshold, contour), step(0.35, silhouette));
    float weak = step(uThreshold * uWeakRatio, contour) * (1.0 - strong);
    fragColor = vec4(strong + weak * 0.5, 0.0, 0.0, 1.0);
}
