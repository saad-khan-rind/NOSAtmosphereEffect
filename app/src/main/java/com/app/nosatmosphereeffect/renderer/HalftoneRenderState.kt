package com.app.nosatmosphereeffect.renderer

import com.app.nosatmosphereeffect.helper.ClockOverlayState

data class HalftoneRenderState(
    val progress: Float = 0f,
    val dimLevel: Float = 0f,
    val dotSize: Float = 12f,
    val grayscale: Boolean = false,
    val backgroundOnly: Boolean = false,
    val hasSubject: Boolean = false,
    /**
     * The wallpaper clock. Grouped rather than flattened into a dozen fields
     * so that adding the clock to an effect is one field, not twelve chances
     * to forget one — see ClockOverlayState.
     */
    val clock: ClockOverlayState = ClockOverlayState()
) {
    fun sanitized(): HalftoneRenderState {
        return copy(
            progress = progress.finiteOr(0f).coerceIn(0f, 1f),
            dimLevel = dimLevel.finiteOr(0f).coerceIn(0f, 1f),
            dotSize = dotSize.finiteOr(12f).coerceIn(0f, 40f),
            // The clock's depth effect can be the reason a mask exists, so
            // "has a subject" is no longer conditional on background-only.
            hasSubject = (backgroundOnly || clock.needsSubjectMask()) && hasSubject,
            clock = clock.sanitized()
        )
    }

    fun effectStrength(reverse: Boolean): Float {
        return HalftoneProgressPolicy.effectStrength(progress, reverse)
    }

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }
}

internal object HalftoneProgressPolicy {
    const val LOCKED_PROGRESS = 0f
    const val UNLOCKED_PROGRESS = 1f

    fun effectStrength(progress: Float, reverse: Boolean): Float {
        val safeProgress = progress.takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f)
            ?: LOCKED_PROGRESS
        return if (reverse) 1f - safeProgress else safeProgress
    }
}
