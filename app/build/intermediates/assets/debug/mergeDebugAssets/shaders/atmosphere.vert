#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
out vec2 vTexCoord;

void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}