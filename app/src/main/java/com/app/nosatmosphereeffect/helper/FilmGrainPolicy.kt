package com.app.nosatmosphereeffect.helper

import kotlin.math.ln
import kotlin.math.pow

/**
 * The film grain settings as the Fine tuning sliders show them.
 *
 * Stored exactly as before — "noise_scale" is how many grains fit across the
 * image, "noise_strength" how far each one moves the colour — so existing
 * settings carry over. The sliders map a 0..1 position onto those numbers and
 * describe the result in words rather than asking for a raw value.
 *
 * The shaders never draw a grain smaller than one screen pixel, so on any
 * display the finest setting is the finest the screen can show; see the
 * grain block in the effect shaders.
 */
object FilmGrainPolicy {

    const val DEFAULT_SCALE = 2_000f
    const val DEFAULT_STRENGTH = 0.06f

    /** Grains down the image at the fine and coarse ends: about one and six pixels on a phone. */
    private const val FINEST_SCALE = 2_400f
    private const val COARSEST_SCALE = 400f

    const val MAX_STRENGTH = 0.15f

    /** Slider position 0 (fine) .. 1 (coarse) for a stored [scale]. */
    fun sizePosition(scale: Float): Float {
        if (!scale.isFinite() || scale <= 0f) return 0f
        val clamped = scale.coerceIn(COARSEST_SCALE, FINEST_SCALE)
        return (ln(FINEST_SCALE / clamped) / ln(FINEST_SCALE / COARSEST_SCALE)).coerceIn(0f, 1f)
    }

    /** The stored scale for a slider [position]; even steps feel even to the eye. */
    fun scaleAt(position: Float): Float =
        FINEST_SCALE * (COARSEST_SCALE / FINEST_SCALE).pow(position.coerceIn(0f, 1f))

    fun sizeLabel(position: Float): String = when {
        position < 0.2f -> "Very fine"
        position < 0.45f -> "Fine"
        position < 0.7f -> "Medium"
        position < 0.9f -> "Coarse"
        else -> "Very coarse"
    }

    fun sanitizeStrength(strength: Float): Float =
        if (strength.isFinite()) strength.coerceIn(0f, MAX_STRENGTH) else DEFAULT_STRENGTH

    fun strengthLabel(strength: Float): String = when {
        strength < 0.02f -> "Barely there"
        strength < 0.05f -> "Subtle"
        strength < 0.09f -> "Medium"
        strength < 0.12f -> "Strong"
        else -> "Heavy"
    }
}
