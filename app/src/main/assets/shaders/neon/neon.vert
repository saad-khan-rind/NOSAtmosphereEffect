#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;
// Screen-locked copy of the quad's coordinate, i.e. the photo's scroll
// window is NOT applied. The clock overlay is positioned against the
// physical screen, so it must not pan with the wallpaper.
out vec2 vEffectCoord;

// Horizontal wallpaper scrolling (home-screen parallax). The defaults are
// identity (full-width window, zero offset). This shader is used only for the
// final on-screen pass; off-screen contour work uses neon_bake.vert.
uniform float uScrollOffsetX;   // launcher page offset, 0.0 (left) .. 1.0 (right)
uniform float uScrollWindowX;   // visible fraction of texture width, (0.0, 1.0]

void main() {
    gl_Position = aPosition;
    float win = uScrollWindowX <= 0.0 ? 1.0 : uScrollWindowX;
    float u = uScrollOffsetX * (1.0 - win) + aTexCoord.x * win;
    vTexCoord = vec2(u, aTexCoord.y);
    vEffectCoord = aTexCoord;
}
