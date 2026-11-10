#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;

// Android bitmaps and the final wallpaper pass use vertically inverted texture
// coordinates. Off-screen framebuffer textures do not. Flip the quad UV here so
// every contour-bake pass stays in raw texture space and preserves orientation.
void main() {
    gl_Position = aPosition;
    vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
}
