#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;
uniform float uBlurStrength; // 0.0 -> 1.0 (Linear Time)
uniform float uSeed;

// Rotate UVs around a center point
vec2 rotate(vec2 uv, float angle) {
    vec2 center = vec2(0.5);
    uv -= center;
    float s = sin(angle);
    float c = cos(angle);
    mat2 rot = mat2(c, -s, s, c);
    return (uv * rot) + center;
}

// Cubic Ease Out: Fast Start -> Slow Stop
float easeOutCubic(float x) {
    return 1.0 - pow(1.0 - x, 3.0);
}

void main() {
    float t = uBlurStrength;

    // --- TIMING SEQUENCE ---

    // 1. Blur Phase (0.0 to 0.25)
    // 0.0 to 1.0 value representing opacity of the blur
    float blurMix = smoothstep(0.0, 0.09, t);

    // 2. Movement Phase (0.25 to 1.0)
    // Starts ONLY after blur is established
    float moveRaw = smoothstep(0.06, 1.0, t);

    // Apply Physics (Deceleration) to the movement
    float movePhysics = easeOutCubic(moveRaw);


    // --- MOVEMENT LOGIC ---

    // 1. Zoom (Increase Size)
    // Only zoom during the movement phase
    float zoom = 1.0 - (movePhysics * 0.4);
    vec2 center = vec2(0.5);
    vec2 zoomedUV = (vTexCoord - center) * zoom + center;

    // 2. Circular / Swirl Movement
    float randomDir = sign(sin(uSeed));
    float dist = length(vTexCoord - center);

    // Rotate based on Physics Curve
    float angle = movePhysics * randomDir * (0.5 + dist);

    vec2 cloudUV = rotate(zoomedUV, angle);

    // 3. Random Destination Shift
    vec2 drift = vec2(sin(uSeed), cos(uSeed)) * movePhysics * 0.3;
    cloudUV += drift;

    // --- COMPOSITION ---

    vec4 sharpColor = texture(uTextureSharp, vTexCoord);
    vec4 cloudColor = texture(uTextureBlur, cloudUV);

    // Mix based on the Blur Phase
    vec3 result = mix(sharpColor.rgb, cloudColor.rgb, blurMix);

    // Darken Overlay (Gradual over whole animation)
    float darken = smoothstep(0.0, 1.0, t) * 0.4;
    result *= (1.0 - darken);

    fragColor = vec4(result, 1.0);
}