#version 300 es

in vec4 aPosition;
in vec2 aTexCoord;

out vec2 vTexCoord;
out vec2 vEffectCoord;

uniform float uScrollOffsetX;
uniform float uScrollWindowX;
uniform float uWallpaperZoom;

void main() {
    gl_Position = aPosition;
    // The Adaptive clock's arrival zoom: the photo starts a little magnified
    // about the screen's centre and settles with the clock. Programs that
    // never set it (the off-screen passes) read 0, which means no zoom.
    float zoom = uWallpaperZoom > 0.0 ? uWallpaperZoom : 1.0;
    vec2 screen = 0.5 + (aTexCoord - 0.5) / zoom;

    float windowX = uScrollWindowX <= 0.0 ? 1.0 : uScrollWindowX;
    float textureX = uScrollOffsetX * (1.0 - windowX) + screen.x * windowX;
    vTexCoord = vec2(textureX, screen.y);
    vEffectCoord = aTexCoord;
}
