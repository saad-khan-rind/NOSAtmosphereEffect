package com.app.nosatmosphereeffect.renderer

import com.app.nosatmosphereeffect.helper.ClockOverlayState

data class ColorFillRenderState(
    val progress: Float = 0f,
    val dimLevel: Float = 0f,
    val originX: Float = 0.5f,
    val originY: Float = 0.8f,
    val scrollOffsetX: Float = 0.5f,
    val scrollWindowX: Float = 1f,
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
    fun sanitized(): ColorFillRenderState {
        return copy(
            progress = progress.finiteOr(0f).coerceIn(0f, 1f),
            dimLevel = dimLevel.finiteOr(0f).coerceIn(0f, 1f),
            originX = originX.finiteOr(0.5f).coerceIn(0f, 1f),
            originY = originY.finiteOr(0.8f).coerceIn(0f, 1f),
            scrollOffsetX = scrollOffsetX.finiteOr(0.5f).coerceIn(0f, 1f),
            scrollWindowX = scrollWindowX.finiteOr(1f).coerceIn(0.001f, 1f),
            clock = clock.sanitized(),
            hasSubject = hasSubject && clock.needsSubjectMask()
        )
    }

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }
}
