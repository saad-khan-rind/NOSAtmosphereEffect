package com.app.nosatmosphereeffect.renderer

import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.GlassTransitionStyle

data class GlassRenderState(
    val progress: Float = 0f,
    val dimLevel: Float = 0f,
    val lineCount: Int = GlassEffectPolicy.DEFAULT_LINE_COUNT,
    val lineThickness: Float = GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
    val transitionStyle: GlassTransitionStyle = GlassTransitionStyle.RIGHT_TO_LEFT,
    val backgroundOnly: Boolean = false,
    val hasSubject: Boolean = false,
    val scrollOffsetX: Float = 0.5f,
    val scrollWindowX: Float = 1f,
    /**
     * The wallpaper clock. Grouped rather than flattened into a dozen fields
     * so that adding the clock to an effect is one field, not twelve chances
     * to forget one — see ClockOverlayState.
     */
    val clock: ClockOverlayState = ClockOverlayState()
) {
    fun sanitized(): GlassRenderState {
        return copy(
            progress = progress.finiteOr(0f).coerceIn(0f, 1f),
            dimLevel = dimLevel.finiteOr(0f).coerceIn(0f, 1f),
            lineCount = GlassEffectPolicy.sanitizeLineCount(lineCount),
            lineThickness = GlassEffectPolicy.sanitizeLineThickness(lineThickness),
            scrollOffsetX = scrollOffsetX.finiteOr(0.5f).coerceIn(0f, 1f),
            scrollWindowX = scrollWindowX.finiteOr(1f).coerceIn(MIN_SCROLL_WINDOW, 1f),
            clock = clock.sanitized()
        )
    }

    internal val transitionStyleShaderValue: Float
        get() = when (transitionStyle) {
            GlassTransitionStyle.RIGHT_TO_LEFT -> 0f
            GlassTransitionStyle.FADE -> 1f
        }

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }

    private companion object {
        const val MIN_SCROLL_WINDOW = 0.001f
    }
}
