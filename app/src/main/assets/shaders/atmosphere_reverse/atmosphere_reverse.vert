#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
out vec2 vEffectCoord;

// Horizontal wallpaper scrolling (home-screen parallax). The defaults are
// identity (full-width window, zero offset), so any draw that does NOT set
// these uniforms - e.g. the off-screen separable-blur passes - samples the
// texture exactly as before. Only the final on-screen draw sets real values.
uniform float uScrollOffsetX;   // launcher page offset, 0.0 (left) .. 1.0 (right)
uniform float uScrollWindowX;   // visible fraction of texture width, (0.0, 1.0]
uniform float uWallpaperZoom;   // the Adaptive clock's arrival zoom; 0/1 = none

void main() {
    gl_Position = aPosition;
    // The Adaptive clock's arrival zoom: the photo starts a little magnified
    // about the screen's centre and settles with the clock. Programs that
    // never set it (the off-screen passes) read 0, which means no zoom.
    float zoom = uWallpaperZoom > 0.0 ? uWallpaperZoom : 1.0;
    vec2 screen = 0.5 + (aTexCoord - 0.5) / zoom;
    float win = uScrollWindowX <= 0.0 ? 1.0 : uScrollWindowX;
    float u = uScrollOffsetX * (1.0 - win) + screen.x * win;
    vTexCoord = vec2(u, screen.y);
    // Keep the glass ribs anchored to the physical screen while the photo pans.
    vEffectCoord = aTexCoord;
}
