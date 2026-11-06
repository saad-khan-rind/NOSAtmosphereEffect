package com.app.nosatmosphereeffect.wallpaper

internal class RendererLifecycleGate {
    @Volatile
    private var rendererAttached = false

    @Volatile
    private var destroyed = false

    fun reset() {
        rendererAttached = false
        destroyed = false
    }

    fun markRendererAttached() {
        if (!destroyed) {
            rendererAttached = true
        }
    }

    fun markDestroyed() {
        destroyed = true
        rendererAttached = false
    }

    fun canDispatchToRenderer(): Boolean = rendererAttached && !destroyed
}
