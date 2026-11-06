#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

// ----------------------------------------------------------------------------
// Canvas sketch - outline cleanup pass.
//
// Decides what the "maybe" crests from neon_edges.frag really are.
//
// A single threshold has to be wrong somewhere: set it high and outlines break
// into dashes wherever the contrast dips for a texel or two, set it low and
// every scrap of sensor noise becomes a line. But real outlines are continuous,
// so the question is not "is this crest strong enough" - it is "does this crest
// join up with one that is". Each pass walks certainty one texel further along
// the contour; a few passes carry it across the dips and leave the strays behind.
// ----------------------------------------------------------------------------

uniform sampler2D uTexture;   // R: 1.0 = outline, 0.5 = maybe, 0.0 = no
uniform vec2  uStep;
uniform float uFinal;         // 1.0 on the last pass: emit distance-pass seed encoding

void main() {
    float c = textureLod(uTexture, vTexCoord, 0.0).r;
    float v = c;

    if (c > 0.25 && c < 0.75) {
        float m = 0.0;
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                m = max(m, textureLod(uTexture, vTexCoord + vec2(float(x), float(y)) * uStep, 0.0).r);
            }
        }
        v = (m > 0.75) ? 1.0 : 0.5;
    }

    // Last pass: anything still unproven never reached a real outline, so it was
    // noise - drop it. Then re-encode for neon_edt.frag, which reads
    // 0.0 = "an outline lives here", 1.0 = "nothing here".
    float edge = step(0.75, v);
    fragColor = vec4(mix(v, 1.0 - edge, uFinal), 0.0, 0.0, 1.0);
}
