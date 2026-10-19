package com.app.nosatmosphereeffect.helper

import kotlin.math.max

/** How the lock screen arrives when the screen turns on, from the always-on display or off. */
enum class WakeTransitionStyle(val storedValue: String) {
    /** The photo settles from a slight zoom; the clock stays put, so depth reads naturally. */
    ZOOM("zoom"),
    /** Colour spreads out from the centre of the screen. */
    COLOR_FILL("color_fill"),
    /** The lock screen sharpens out of a light blur. */
    BLUR("blur");

    companion object {
        fun fromStoredValue(value: String?): WakeTransitionStyle =
            entries.firstOrNull { it.storedValue == value } ?: ZOOM
    }
}

data class WakeTransitionSettings(
    val enabled: Boolean = false,
    val style: WakeTransitionStyle = WakeTransitionStyle.ZOOM,
    val durationMs: Int = DEFAULT_DURATION_MS
) {
    companion object {
        const val MIN_DURATION_MS = 150
        const val MAX_DURATION_MS = 1000
        const val DEFAULT_DURATION_MS = 250

        fun sanitizeDuration(ms: Int): Int = ms.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }
}

/**
 * The wake transition in progress, read by every renderer on both backends.
 * Zoom moves only the photo (the renderers' wallpaper zoom); colour fill and
 * blur are drawn over the finished frame by the wake post pass (WakePostRenderer
 * on OpenGL ES, the native engine on Vulkan).
 */
object WakeTransition {
    /** Where a zoom starts: enough to read as motion, little enough to stay subtle. */
    const val START_ZOOM = 1.05f
    const val MODE_NONE = 0
    const val MODE_COLOR_FILL = 1
    const val MODE_BLUR = 2

    @Volatile var zoom = 1f
        private set
    @Volatile var postMode = MODE_NONE
        private set
    /** 1 at the start of the post pass, 0 when it is done. */
    @Volatile var postAmount = 0f
        private set

    /** [remaining] runs from 1 when the wake starts to 0 when it ends. */
    fun show(style: WakeTransitionStyle, remaining: Float) {
        val left = remaining.coerceIn(0f, 1f)
        when (style) {
            WakeTransitionStyle.ZOOM -> zoom = 1f + (START_ZOOM - 1f) * left
            WakeTransitionStyle.COLOR_FILL -> post(MODE_COLOR_FILL, left)
            WakeTransitionStyle.BLUR -> post(MODE_BLUR, left)
        }
    }

    fun clear() {
        zoom = 1f
        postAmount = 0f
        postMode = MODE_NONE
    }

    private fun post(mode: Int, amount: Float) {
        postAmount = amount
        postMode = mode
    }

    /**
     * Whether the lock screen is already black and white, so a colour-fill
     * wake drawn over the frame would have no colour to bring in: Colour
     * Fill's monochrome side (progress 1, either direction), or Halftone with
     * Black and white on, on the side showing its dots. Then the effect
     * itself plays from its colour side to the lock screen instead.
     * [lockProgress] is the effect's progress on the lock screen.
     */
    fun lockScreenIsMonochrome(effectId: String, lockProgress: Float, halftoneGrayscale: Boolean): Boolean =
        when (effectId) {
            "COLORFILL", "COLORFILL_REVERSE" -> lockProgress >= 0.5f
            "HALFTONE" -> halftoneGrayscale && lockProgress >= 0.5f
            "HALFTONE_REVERSE" -> halftoneGrayscale && lockProgress < 0.5f
            else -> false
        }

    /**
     * The zoom to draw with: the larger of this and [base], the Adaptive
     * clock's arrival zoom, which plays on the same wake. Taking the larger
     * rather than multiplying keeps the two from stacking into a lurch.
     */
    @JvmStatic
    fun zoom(base: Float): Float = max(base, zoom)
}
