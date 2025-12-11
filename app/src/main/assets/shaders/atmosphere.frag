//File: saad-khan-rind/nosatmosphereeffect/saad-khan-rind-NOSAtmosphereEffect-25c3abbe6645a7e5b9afb4818b31393d421bd482/app/src/main/assets/shaders/atmosphere.frag
#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;
uniform float uBlurStrength;
uniform float uSeed;

// --- UTILS ---
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

// 2D Rotation Helper
vec2 rotate(vec2 v, float a) {
    float s = sin(a);
    float c = cos(a);
    mat2 m = mat2(c, -s, s, c);
    return m * v;
}

void main() {
    // --- CONFIGURATION ---
    // Reduced from (4, 8) to (3, 6) for fewer, larger zones
    vec2 gridConfig = vec2(3.0, 6.0);

    // --- STEP 1: CALCULATE ZONES ---
    // Scale UVs to grid space
    vec2 gridUV = vTexCoord * gridConfig;

    // "id" is the integer coordinate of the cell
    vec2 cellId = floor(gridUV);

    // "localUV" is the coordinate INSIDE the cell (-0.5 to 0.5)
    vec2 localUV = fract(gridUV) - 0.5;

    // --- STEP 2: CONTROL DISTORTION TIMING ---
    // Start with 0 distortion at 0.4 (locked state).
    // Ramp up distortion as we unlock.
    float distortionPhase = smoothstep(0.4, 1.0, uBlurStrength);

    // --- STEP 3: APPLY LOCAL SPIRAL ---
    // Calculate distance from center OF THE CELL
    float dist = length(localUV);

    // Generate a random direction per cell so they don't all spin the same way
    float cellRandom = sign(sin(dot(cellId, vec2(12.9898, 78.233)) + uSeed));
    if (cellRandom == 0.0) cellRandom = 1.0;

    // Spiral Strength:
    // Stronger at center of cell (1.0 - dist * 2.0)
    // Multiplied by phase so it starts at 0.0
    // Reduced multiplier from 5.0 to 3.0 to make it less aggressive (slower spin)
    float twistStrength = distortionPhase * 3.0 * max(0.0, (0.5 - dist));

    // Apply rotation to the local UV coordinates
    vec2 twistedLocalUV = rotate(localUV, twistStrength * cellRandom);

    // --- STEP 4: RECONSTRUCT UVs ---
    // Convert back from Local (-0.5 to 0.5) to Grid (0 to 1) to Texture (0 to 1)
    vec2 finalSharpUV = (cellId + twistedLocalUV + 0.5) / gridConfig;

    // --- STEP 5: FETCH COLORS ---
    vec4 sharpColor = texture(uTextureSharp, finalSharpUV);

    // Add a little zoom to the clouds to keep them dynamic
    vec2 cloudUV = (vTexCoord - 0.5) * (1.0 - (0.1 * uBlurStrength)) + 0.5;
    vec4 cloudColor = texture(uTextureBlur, cloudUV);

    // --- STEP 6: MIXING ---
    // Grain for texture
    float grain = hash(gl_FragCoord.xy + uSeed) * 0.1;

    // Ensure 100% transition to clouds by 0.8 strength
    // This hides the twisted wallpaper completely at the end
    float mixFactor = smoothstep(0.0, 0.8, uBlurStrength + grain - 0.05);

    // Final composition
    vec4 finalColor = mix(sharpColor, cloudColor, mixFactor);

    finalColor.rgb += (grain - 0.5) * 0.02;

    fragColor = finalColor;
}