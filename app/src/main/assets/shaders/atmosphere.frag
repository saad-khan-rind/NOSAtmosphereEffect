#version 300 es
precision mediump float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;
uniform float uBlurStrength;
uniform float uSeed;
uniform float uIsSamsung;

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
    float blurMix;
    float moveRaw;

    // Check if Samsung (passed from Kotlin as 1.0 or 0.0)
    if (uIsSamsung > 0.5) {
        // 1. Blur Phase (0.0 to 0.11)
        blurMix = smoothstep(0.0, 0.11, t);
        // 2. Movement Phase (0.09 to 1.0) [Delayed start]
        moveRaw = smoothstep(0.09, 1.0, t);

    } else {
        // 1. Movement Phase (0.00 to 1.0) [Immediate start]
        moveRaw = smoothstep(0.0, 1.0, t);
    }

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

    // Mix based on the Blur
    vec3 result;
    if(uIsSamsung > 0.5){
        result = mix(sharpColor.rgb, cloudColor.rgb, blurMix);
    } else {
        result = cloudColor.rgb;
    }

    float darken = smoothstep(0.0, 1.0, t) * 0.2;
    result *= (1.0 - darken);

    fragColor = vec4(result, 1.0);

}