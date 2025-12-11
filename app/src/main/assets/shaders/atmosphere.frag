#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;
uniform float uBlurStrength; // 0.0 to 1.0 (Animation Progress)
uniform float uSeed;

// Standard pseudo-random noise generator
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    // --- TIMING LOGIC ---

    // 1. FAST BLUR (0.0 -> 0.1)
    // The blur happens instantly (in the first 0.2s of the 2s animation)
    float blurMix = smoothstep(0.0, 0.1, uBlurStrength);

    // 2. CLOUD SHIFT/TRAVEL (0.0 -> 1.0)
    // The cloud texture continuously zooms in slightly over the whole animation.
    float zoom = 1.0 - (0.1 * uBlurStrength); // 10% Zoom travel
    vec2 cloudUV = (vTexCoord - 0.5) * zoom + 0.5;

    // 3. DARK OVERLAY (0.0 -> 1.0) --- UPDATED ---
    // Instead of waiting for 0.6, we now start darkening immediately.
    // The darkening happens smoothly along with the cloud movement.
    float darkenFactor = smoothstep(0.0, 1.0, uBlurStrength) * 0.3;

    // --- COLOR FETCHING ---

    vec4 sharpColor = texture(uTextureSharp, vTexCoord);
    vec4 cloudColor = texture(uTextureBlur, cloudUV);

    // --- COMPOSITION ---

    // Mix Sharp and Cloud based on the Fast Blur timing
    vec3 result = mix(sharpColor.rgb, cloudColor.rgb, blurMix);

    // Apply Dark Mask
    // This now gradually dims the screen from start to finish
    result *= (1.0 - darkenFactor);

    fragColor = vec4(result, 1.0);
}