package com.app.nosatmosphereeffect.renderer.zoom

import com.app.nosatmosphereeffect.clock.ClockOverlayState

data class ZoomRenderState(
    val progress: Float = 0f,
    val dimLevel: Float = 0f,
    val hasSubject: Boolean = false,
    /** The wallpaper clock; see ClockOverlayState. */
    val clock: ClockOverlayState = ClockOverlayState()
) {
    fun sanitized(): ZoomRenderState {
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
        const val DEFAULT_DURATION_MS = 500L

        /** How far the photo zooms at full effect. */
        private const val ZOOM_RANGE = 0.25f

        /**
         * The photo's zoom at transition [progress], for the variant that
         * shows its effect at the lock screen if [effectFirst]. Fed to the
         * vertex shader's wallpaper zoom, so the clock stays put.
         */
        fun photoZoom(effectFirst: Boolean, progress: Float): Float {
            val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
            return 1f + ZOOM_RANGE * (if (effectFirst) 1f - p else p)
        }
    }
}
