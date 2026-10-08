package com.app.nosatmosphereeffect.helper

import kotlin.math.roundToInt

data class GlassEffectSettings(
    val lineCount: Int,
    val lineThickness: Float,
    val transitionStyle: GlassTransitionStyle,
    val backgroundOnly: Boolean
)

enum class GlassTransitionStyle(val storedValue: String) {
    RIGHT_TO_LEFT("right_to_left"),
    FADE("fade");

    companion object {
        fun fromStoredValue(value: String?): GlassTransitionStyle =
            entries.firstOrNull { it.storedValue == value } ?: RIGHT_TO_LEFT
    }
}

object GlassEffectPolicy {
    const val LINE_COUNT_KEY = "glass_line_count"
    const val LINE_THICKNESS_KEY = "glass_line_thickness"
    const val TRANSITION_STYLE_KEY = "glass_transition_style"
    const val BACKGROUND_ONLY_KEY = "glass_background_only"
    const val PRESET_VERSION_KEY = "glass_preset_version"

    /**
     * Whether the glass must stay off the subject because of the clock: a
     * showing clock with Depth effect or Adapt to subject on puts the subject
     * in front of it, and glass drawn across that subject would contradict
     * it. Background only is then on whatever is stored (and greyed out in
     * Fine tuning). Covers the Glass effect itself and the glass inside the
     * Atmosphere effects.
     */
    fun clockForcesBackgroundOnly(clock: ClockOverlayState): Boolean = clock.needsSubjectMask()
    const val CURRENT_PRESET_VERSION = 2

    const val MIN_LINE_COUNT = 4
    const val MAX_LINE_COUNT = 40
    const val DEFAULT_LINE_COUNT = 28

    const val MIN_LINE_THICKNESS = 0.15f
    const val MAX_LINE_THICKNESS = 1f
    const val DEFAULT_LINE_THICKNESS = 0.775f

    private const val LEGACY_DEFAULT_LINE_COUNT = 18
    private const val LEGACY_DEFAULT_LINE_THICKNESS = 0.65f

    fun sanitizeLineCount(value: Int): Int =
        value.coerceIn(MIN_LINE_COUNT, MAX_LINE_COUNT)

    fun sanitizeLineCount(value: Float): Int {
        if (!value.isFinite()) return DEFAULT_LINE_COUNT
        return sanitizeLineCount(value.roundToInt())
    }

    fun sanitizeLineThickness(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_LINE_THICKNESS
        return value.coerceIn(MIN_LINE_THICKNESS, MAX_LINE_THICKNESS)
    }

    fun resolveStoredSettings(
        lineCount: Int,
        lineThickness: Float,
        presetVersion: Int,
        transitionStyle: GlassTransitionStyle = GlassTransitionStyle.RIGHT_TO_LEFT,
        backgroundOnly: Boolean = false
    ): GlassEffectSettings {
        val safeCount = sanitizeLineCount(lineCount)
        val safeThickness = sanitizeLineThickness(lineThickness)
        val hasLegacyDefaults = presetVersion < CURRENT_PRESET_VERSION &&
            safeCount == LEGACY_DEFAULT_LINE_COUNT &&
            safeThickness == LEGACY_DEFAULT_LINE_THICKNESS

        return if (hasLegacyDefaults) {
            GlassEffectSettings(
                DEFAULT_LINE_COUNT,
                DEFAULT_LINE_THICKNESS,
                transitionStyle,
                backgroundOnly
            )
        } else {
            GlassEffectSettings(
                safeCount,
                safeThickness,
                transitionStyle,
                backgroundOnly
            )
        }
    }

    fun shaderProgress(lockToHomeProgress: Float, reverse: Boolean): Float {
        val safeProgress = if (lockToHomeProgress.isFinite()) {
            lockToHomeProgress.coerceIn(0f, 1f)
        } else {
            0f
        }
        return if (reverse) 1f - safeProgress else safeProgress
    }

    fun lineIndex(screenX: Float, lineCount: Int): Int {
        require(lineCount > 0) { "Line count must be positive" }
        val safeX = if (screenX.isFinite()) screenX.coerceIn(0f, 1f) else 0f
        return (safeX * lineCount).toInt().coerceAtMost(lineCount - 1)
    }

    fun revealOrderFromRight(columnFromLeft: Int, lineCount: Int): Int {
        require(lineCount > 0) { "Line count must be positive" }
        require(columnFromLeft in 0 until lineCount) {
            "Column $columnFromLeft is outside a $lineCount-line effect"
        }
        return lineCount - 1 - columnFromLeft
    }

    fun revealAmount(
        columnFromLeft: Int,
        lineCount: Int,
        globalProgress: Float
    ): Float {
        val order = revealOrderFromRight(columnFromLeft, lineCount)
        val progress = if (globalProgress.isFinite()) {
            globalProgress.coerceIn(0f, 1f)
        } else {
            0f
        }
        val localProgress = (progress * lineCount - order).coerceIn(0f, 1f)
        return localProgress * localProgress * (3f - 2f * localProgress)
    }

    fun transitionAmount(
        columnFromLeft: Int,
        lineCount: Int,
        globalProgress: Float,
        style: GlassTransitionStyle
    ): Float {
        val progress = if (globalProgress.isFinite()) {
            globalProgress.coerceIn(0f, 1f)
        } else {
            0f
        }
        return when (style) {
            GlassTransitionStyle.RIGHT_TO_LEFT ->
                revealAmount(columnFromLeft, lineCount, progress)
            GlassTransitionStyle.FADE ->
                progress * progress * (3f - 2f * progress)
        }
    }
}
