package com.app.nosatmosphereeffect.effect

/**
 * Defines the image-level glass option shared by both Atmosphere directions.
 *
 * The option is intentionally global to the active wallpaper collection: a
 * standard playlist and both sides of a theme playlist use the same renderer.
 */
object AtmosphereGlassPolicy {
    const val ENABLED_KEY = "atmosphere_glass_enabled"

    fun supportsEffect(effectId: String?): Boolean {
        return effectId == "ATMOSPHERE" || effectId == "ATMOSPHERE_REVERSE"
    }

    fun resolveEnabled(effectId: String?, requested: Boolean): Boolean {
        return supportsEffect(effectId) && requested
    }
}
