package com.app.nosatmosphereeffect.helper

/**
 * Defines the depth-composited clock overlay, currently supported only by
 * the original Atmosphere effect (blobs/clouds renderer, [AtmosphereRenderer]).
 *
 * Unlike [AtmosphereGlassPolicy], this intentionally excludes "REVERSE":
 * that direction runs through BlurToSharpRenderer, which does not yet
 * implement the clock compositing pass.
 *
 * Geometry is stored as screen-fraction values (matching the shader's
 * screen-locked uEffectCoord space) so the same numbers drive both the
 * live wallpaper and the ClockAdjustActivity preview with no conversion.
 */
object AtmosphereClockPolicy {
    const val ENABLED_KEY = "atmosphere_clock_enabled"
    const val CENTER_X_KEY = "atmosphere_clock_center_x"
    const val TOP_KEY = "atmosphere_clock_top"
    const val HEIGHT_KEY = "atmosphere_clock_height"
    const val OPACITY_KEY = "atmosphere_clock_opacity"

    // Large-by-default: chosen so the clock is unmissable without on-device
    // tuning. ClockAdjustActivity lets a user shrink/move it per device.
    const val DEFAULT_CENTER_X = 0.5f
    const val DEFAULT_TOP = 0.14f
    const val DEFAULT_HEIGHT = 0.16f
    const val DEFAULT_OPACITY = 1f

    private const val MIN_CENTER_X = 0.05f
    private const val MAX_CENTER_X = 0.95f
    private const val MIN_TOP = 0.02f
    private const val MAX_TOP = 0.85f
    private const val MIN_HEIGHT = 0.04f
    private const val MAX_HEIGHT = 0.32f

    fun supportsEffect(effectId: String?): Boolean {
        return effectId == "ORIGINAL"
    }

    fun resolveEnabled(effectId: String?, requested: Boolean): Boolean {
        return supportsEffect(effectId) && requested
    }

    fun sanitizeCenterX(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_CENTER_X
        return value.coerceIn(MIN_CENTER_X, MAX_CENTER_X)
    }

    fun sanitizeTop(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_TOP
        return value.coerceIn(MIN_TOP, MAX_TOP)
    }

    fun sanitizeHeight(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_HEIGHT
        return value.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
    }

    fun sanitizeOpacity(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_OPACITY
        return value.coerceIn(0f, 1f)
    }
}
