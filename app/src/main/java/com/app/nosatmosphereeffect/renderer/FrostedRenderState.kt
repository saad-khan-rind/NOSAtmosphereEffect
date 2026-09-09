package com.app.nosatmosphereeffect.renderer

import com.app.nosatmosphereeffect.helper.ClockOverlayState
import kotlin.math.roundToInt

data class FrostedRenderState(
    val progress: Float = 0f,
    val dimLevel: Float = 0.2f,
    val enableNoise: Boolean = false,
    val noiseScale: Float = 2_000f,
    val noiseStrength: Float = 0.06f,
    val blurRadius: Float = 200f,
    val drawerBlur: Float = 0f,
    /**
     * The wallpaper clock. Grouped rather than flattened into a dozen fields
     * so that adding the clock to an effect is one field, not twelve chances
     * to forget one — see ClockOverlayState.
     */
    val clock: ClockOverlayState = ClockOverlayState(),
    /**
     * Whether a subject mask has been uploaded for the current image. Only the
     * clock's depth effect consumes it here — this effect has no subject
     * isolation of its own.
     */
    val hasSubject: Boolean = false
) {
    fun sanitized(): FrostedRenderState {
        return copy(
            progress = progress.finiteOr(0f).coerceIn(0f, 1f),
            dimLevel = dimLevel.finiteOr(0.2f).coerceIn(0f, 1f),
            noiseScale = noiseScale.finiteOr(2_000f).coerceAtLeast(0f),
            noiseStrength = noiseStrength.finiteOr(0.06f).coerceAtLeast(0f),
            blurRadius = blurRadius
                .finiteOr(200f)
                .coerceIn(0f, 400f)
                .roundToInt()
                .toFloat(),
            drawerBlur = drawerBlur.finiteOr(0f).coerceIn(0f, 1f),
            clock = clock.sanitized(),
            hasSubject = hasSubject && clock.needsSubjectMask()
        )
    }

    val blurRadiusPixels: Int
        get() = blurRadius.finiteOr(200f).coerceIn(0f, 400f).toInt()

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }
}
