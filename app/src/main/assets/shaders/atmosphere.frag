#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;
uniform float uBlurStrength; // 0.0 to 1.0
uniform float uSeed;         // NEW: Random seed (0.0 to 500.0)

// --- IMPROVED NOISE FUNCTIONS ---
// High quality hash to prevent grid lines
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// Smooth noise
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    // Quintic interpolation (smoother than Hermite) - Fixes "blocky" artifacts
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);

    float a = hash(i + vec2(0.0, 0.0));
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float total = 0.0;
    float amplitude = 0.5;
    mat2 rot = mat2(cos(0.5), sin(0.5), -sin(0.5), cos(0.5)); // Rotate octaves to hide grid
    for (int i = 0; i < 3; i++) {
        total += noise(p) * amplitude;
        p = rot * p * 2.0 + 100.0; // Shift domain to avoid origin artifacts
        amplitude *= 0.5;
    }
    return total;
}

void main() {
    // --- STEP 1: RANDOMIZE DIRECTION ---
    // Use the seed to decide direction (Left vs Right)
    // If sin(uSeed) > 0, direction is 1.0. Else -1.0.
    float randomDir = sign(sin(uSeed));
    if (randomDir == 0.0) randomDir = 1.0;

    // --- STEP 2: LIQUID FLOW CALCULATION ---

    // Add Seed to time so waves are different every unlock
    float fluidTime = (uBlurStrength * 2.0) + uSeed;

    vec2 flow;
    // Offset coordinates by Seed to land in a different part of the noise field
    flow.x = fbm(vTexCoord * 3.0 + fluidTime);
    flow.y = fbm(vTexCoord * 3.0 - fluidTime + vec2(10.0, 5.0));

    flow -= 0.5; // Center the flow

    // Apply Random Direction to the flow vector
    flow *= randomDir;

    // Distortion strength
    float distortStrength = 0.3 * uBlurStrength;

    // Apply distortion
    vec2 cloudCoord = vTexCoord + (flow * distortStrength);

    // --- STEP 3: EDGE & ZOOM FIX ---
    // Zoom in (0.75) so we have plenty of room to shake the texture without hitting black edges
    // Center the zoom around (0.5, 0.5)
    vec2 centered = cloudCoord - 0.5;
    cloudCoord = (centered * (1.0 - (0.25 * uBlurStrength))) + 0.5;

    // Safety Clamp (Just in case)
    cloudCoord = clamp(cloudCoord, 0.01, 0.99);

    // --- STEP 4: MIX COLORS ---

    vec4 sharpColor = texture(uTextureSharp, vTexCoord);
    vec4 cloudColor = texture(uTextureBlur, cloudCoord);

    // --- STEP 5: GRAIN DISSOLVE ---

    // Generate static grain based on screen pixel coords
    float grain = hash(gl_FragCoord.xy + uSeed) * 0.15; // Randomize grain pattern too!

    float mixFactor = smoothstep(0.0, 1.0, uBlurStrength + grain - 0.05);

    vec4 finalColor = mix(sharpColor, cloudColor, mixFactor);
    finalColor.rgb += (grain - 0.5) * 0.03; // Debanding

    fragColor = finalColor;
}