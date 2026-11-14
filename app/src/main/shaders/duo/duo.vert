#version 450

// Where this pixel reads each photo: the lock screen's and the home screen's.
// Each has its own scroll window, as each is fitted to the screen on its own.
layout(location = 0) out vec2 vLockCoord;
layout(location = 1) out vec2 vHomeCoord;
// Screen-locked: 0,0 at the top left, without the scroll window or the zoom.
layout(location = 2) out vec2 vScreen;

// render: progress, dim, surface width / height, style (DuoStyle's order).
// scroll: launcher page offset, the lock photo's visible share of its width,
// the home photo's, and a wake transition's zoom on the wallpaper (0/1 = none).
layout(push_constant) uniform DuoParams {
    vec4 render;
    vec4 scroll;
} params;

void main() {
    const vec2 positions[3] = vec2[3](
        vec2(-1.0, -1.0),
        vec2(3.0, -1.0),
        vec2(-1.0, 3.0)
    );
    const vec2 coordinates[3] = vec2[3](
        vec2(0.0, 0.0),
        vec2(2.0, 0.0),
        vec2(0.0, 2.0)
    );

    gl_Position = vec4(positions[gl_VertexIndex], 0.0, 1.0);
    float zoom = params.scroll.w > 0.0 ? params.scroll.w : 1.0;
    vec2 screen = 0.5 + (coordinates[gl_VertexIndex] - 0.5) / zoom;
    float lockWindow = max(params.scroll.y, 0.001);
    float homeWindow = max(params.scroll.z, 0.001);
    vLockCoord = vec2(params.scroll.x * (1.0 - lockWindow) + screen.x * lockWindow, screen.y);
    vHomeCoord = vec2(params.scroll.x * (1.0 - homeWindow) + screen.x * homeWindow, screen.y);
    vScreen = coordinates[gl_VertexIndex];
}
