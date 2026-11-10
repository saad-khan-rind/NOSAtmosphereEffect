package com.app.nosatmosphereeffect.renderer.vhs

import com.app.nosatmosphereeffect.clock.ClockOverlayState

data class VhsRenderState(
    val progress: Float = 0f,
    val dimLevel: Float = 0f,
    val hasSubject: Boolean = false,
    /** The wallpaper clock; see ClockOverlayState. */
    val clock: ClockOverlayState = ClockOverlayState()
) {
    fun sanitized(): VhsRenderState {
        return copy(
            progress = progress.finiteOr(0f).coerceIn(0f, 1f),
            dimLevel = dimLevel.finiteOr(0f).coerceIn(0f, 1f),
            // Only the clock's depth effect asks for a subject mask here.
            hasSubject = clock.needsSubjectMask() && hasSubject,
            clock = clock.sanitized()
        )
    }

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }

    companion object {
        const val LOCKED_PROGRESS = 0f
        const val UNLOCKED_PROGRESS = 1f
        const val DEFAULT_DURATION_MS = 900L
    }
}
