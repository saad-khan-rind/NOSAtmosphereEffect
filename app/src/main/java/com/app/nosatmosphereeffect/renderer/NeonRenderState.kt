package com.app.nosatmosphereeffect.renderer

import com.app.nosatmosphereeffect.helper.ClockOverlayState

data class NeonRenderState(
    val progress: Float = 0f,
    val dimLevel: Float = 0f,
    val lineWidth: Float = 1.5f,
    val sensitivity: Float = 0.5f,
    val subjectSegmentationEnabled: Boolean = false,
    /**
     * The wallpaper clock. Grouped rather than flattened into a dozen fields
     * so that adding the clock to an effect is one field, not twelve chances
     * to forget one — see ClockOverlayState.
     */
    val clock: ClockOverlayState = ClockOverlayState()
) {
    fun sanitized(): NeonRenderState {
        return copy(
            progress = progress.finiteOr(0f).coerceIn(0f, 1f),
            dimLevel = dimLevel.finiteOr(0f).coerceIn(0f, 1f),
            lineWidth = lineWidth.finiteOr(1.5f).coerceIn(0.25f, 8f),
            sensitivity = sensitivity.finiteOr(0.5f).coerceIn(0f, 1f),
            clock = clock.sanitized()
        )
    }

    fun imageAmount(reverse: Boolean): Float {
        val safeProgress = progress.finiteOr(0f).coerceIn(0f, 1f)
        return if (reverse) 1f - safeProgress else safeProgress
    }

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }
}
