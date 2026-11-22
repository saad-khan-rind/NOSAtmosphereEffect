#version 300 es
in vec4 aPosition;
in vec2 aTexCoord;
// Where this pixel reads each photo: the lock screen's and the home screen's.
// Each has its own scroll window, as each is fitted to the screen on its own.
out vec2 vLockCoord;
out vec2 vHomeCoord;
// Screen-locked: 0,0 at the top left, without the scroll window or the zoom.
out vec2 vScreen;

uniform float uScrollOffsetX;   // launcher page offset, 0.0 (left) .. 1.0 (right)
uniform float uLockWindowX;     // visible share of the lock photo's width, (0.0, 1.0]
uniform float uHomeWindowX;     // the same for the home photo
uniform float uWallpaperZoom;   // a wake transition's zoom on the wallpaper; 0/1 = none

void main() {
    gl_Position = aPosition;
    float zoom = uWallpaperZoom > 0.0 ? uWallpaperZoom : 1.0;
    vec2 screen = 0.5 + (aTexCoord - 0.5) / zoom;
    float lockWindow = uLockWindowX <= 0.0 ? 1.0 : uLockWindowX;
    float homeWindow = uHomeWindowX <= 0.0 ? 1.0 : uHomeWindowX;
    vLockCoord = vec2(uScrollOffsetX * (1.0 - lockWindow) + screen.x * lockWindow, screen.y);
    vHomeCoord = vec2(uScrollOffsetX * (1.0 - homeWindow) + screen.x * homeWindow, screen.y);
    vScreen = aTexCoord;
}
