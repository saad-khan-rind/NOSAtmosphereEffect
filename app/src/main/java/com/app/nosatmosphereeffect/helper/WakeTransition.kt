package com.app.nosatmosphereeffect.helper

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** How the lock screen arrives when the screen turns on, from the always-on display or off. */
enum class WakeTransitionStyle(val storedValue: String) {
    /** The photo settles from a slight zoom; the clock stays put, so depth reads naturally. */
    ZOOM("zoom"),
    /** Colour spreads out from the centre of the screen. */
    COLOR_FILL("color_fill"),
    /** The lock screen sharpens out of a light blur. */
    BLUR("blur"),
    /** The corners are pulled out past the edges, or in towards the middle, and spring back. */
    STRETCH("stretch"),
    /** The whole screen jolts and settles. */
    SHAKE("shake");

    companion object {
        fun fromStoredValue(value: String?): WakeTransitionStyle =
            entries.firstOrNull { it.storedValue == value } ?: ZOOM
    }
}

enum class WakeShakeDirection(val storedValue: String) {
    SIDEWAYS("sideways"),
    VERTICAL("vertical"),
    TWIST("twist");

    companion object {
        fun fromStoredValue(value: String?): WakeShakeDirection =
            entries.firstOrNull { it.storedValue == value } ?: SIDEWAYS
    }
}

data class WakeTransitionSettings(
    val enabled: Boolean = false,
    val style: WakeTransitionStyle = WakeTransitionStyle.ZOOM,
    val durationMs: Int = DEFAULT_DURATION_MS,
    /** How far in a zoom starts, in percent. */
    val zoomPercent: Int = DEFAULT_ZOOM_PERCENT,
    /** Stretch pulls the corners in towards the middle rather than out past the edges. */
    val stretchInward: Boolean = false,
    val stretchPercent: Int = DEFAULT_STRETCH_PERCENT,
    val shakeDirection: WakeShakeDirection = WakeShakeDirection.SIDEWAYS,
    /** How far a shake moves, in percent of the screen's height. */
    val shakePercent: Int = DEFAULT_SHAKE_PERCENT,
    /** Back-and-forth swings in one shake. */
    val shakeCount: Int = DEFAULT_SHAKE_COUNT
) {
    companion object {
        const val MIN_DURATION_MS = 50
        const val MAX_DURATION_MS = 1000
        const val DEFAULT_DURATION_MS = 250
        const val MIN_ZOOM_PERCENT = 1
        const val MAX_ZOOM_PERCENT = 80
        const val DEFAULT_ZOOM_PERCENT = 20

        fun sanitizeDuration(ms: Int): Int = ms.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

        const val MIN_STRETCH_PERCENT = 5
        /** Past a third the outward stretch would fold over itself. */
        const val MAX_STRETCH_PERCENT = 30
        const val DEFAULT_STRETCH_PERCENT = 15
        const val MIN_SHAKE_PERCENT = 1
        const val MAX_SHAKE_PERCENT = 8
        const val DEFAULT_SHAKE_PERCENT = 3
        const val MIN_SHAKE_COUNT = 1
        const val MAX_SHAKE_COUNT = 8
        const val DEFAULT_SHAKE_COUNT = 3

        fun sanitizeZoom(percent: Int): Int = percent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)

        fun sanitizeStretch(percent: Int): Int = percent.coerceIn(MIN_STRETCH_PERCENT, MAX_STRETCH_PERCENT)

        fun sanitizeShake(percent: Int): Int = percent.coerceIn(MIN_SHAKE_PERCENT, MAX_SHAKE_PERCENT)

        fun sanitizeShakeCount(count: Int): Int = count.coerceIn(MIN_SHAKE_COUNT, MAX_SHAKE_COUNT)
    }
}

/**
 * The wake transition in progress, read by every renderer on both backends.
 * Zoom moves only the photo (the renderers' wallpaper zoom); colour fill,
 * blur, stretch and shake are drawn over the finished frame by the wake post
 * pass (WakePostRenderer on OpenGL ES, the native engine on Vulkan).
 */
object WakeTransition {
    const val MODE_NONE = 0
    const val MODE_COLOR_FILL = 1
    const val MODE_BLUR = 2
    const val MODE_STRETCH = 3
    const val MODE_SHAKE = 4
    /** A twist's turn, in radians, per unit of shake (a share of the screen's height). */
    private const val TWIST_RADIANS = 0.6f

    @Volatile var zoom = 1f
        private set
    @Volatile var postMode = MODE_NONE
        private set
    /** 1 at the start of the post pass, 0 when it is done. */
    @Volatile var postAmount = 0f
        private set
    /** Stretch: how far the corners move, positive out past the edges, negative inwards. */
    @Volatile var stretch = 0f
        private set
    /** Shake: the frame's offset in screen heights, and its turn in radians. */
    @Volatile var shakeX = 0f
        private set
    @Volatile var shakeY = 0f
        private set
    @Volatile var shakeAngle = 0f
        private set

    /**
     * Colour Fill is playing the wake itself (a black and white lock screen):
     * it fills from the middle of the screen, as the wake always does, not
     * from its own fill point (Fine tuning, or Pro's unlock-method origin).
     */
    @Volatile var fillFromCentre = false

    /**
     * [remaining] runs from 1 when the wake starts to 0 when it ends. A shake
     * takes it unshaped (linear in time), so its swings keep an even beat.
     */
    fun show(settings: WakeTransitionSettings, remaining: Float) {
        val left = remaining.coerceIn(0f, 1f)
        when (settings.style) {
            WakeTransitionStyle.ZOOM ->
                zoom = 1f + WakeTransitionSettings.sanitizeZoom(settings.zoomPercent) / 100f * left
            WakeTransitionStyle.COLOR_FILL -> post(MODE_COLOR_FILL, left)
            WakeTransitionStyle.BLUR -> post(MODE_BLUR, left)
            WakeTransitionStyle.STRETCH -> {
                val reach = WakeTransitionSettings.sanitizeStretch(settings.stretchPercent) / 100f
                stretch = if (settings.stretchInward) -reach else reach
                post(MODE_STRETCH, left)
            }
            WakeTransitionStyle.SHAKE -> {
                // Starts still, swings [shakeCount] times, dying away as it goes.
                val swing = WakeTransitionSettings.sanitizeShake(settings.shakePercent) / 100f * left *
                    sin((1f - left) * WakeTransitionSettings.sanitizeShakeCount(settings.shakeCount) * 2f * PI.toFloat())
                shakeX = if (settings.shakeDirection == WakeShakeDirection.SIDEWAYS) swing else 0f
                shakeY = if (settings.shakeDirection == WakeShakeDirection.VERTICAL) swing else 0f
                shakeAngle = if (settings.shakeDirection == WakeShakeDirection.TWIST) swing * TWIST_RADIANS else 0f
                post(MODE_SHAKE, left)
            }
        }
    }

    fun clear() {
        zoom = 1f
        postAmount = 0f
        postMode = MODE_NONE
        stretch = 0f
        shakeX = 0f
        shakeY = 0f
        shakeAngle = 0f
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
