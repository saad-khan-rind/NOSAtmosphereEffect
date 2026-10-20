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
    SHAKE("shake"),
    /** A ripple runs out from the centre, like a stone dropped in water. */
    RIPPLE("ripple"),
    /** A burst of broken pixels and split colour that settles into the lock screen. */
    GLITCH("glitch"),
    /** Strips open in turn from black, like a venetian blind. */
    BLINDS("blinds"),
    /** The lock screen breathes in from black, swelling just past full brightness. */
    BREATHE("breathe"),
    /** A different style each wake, shuffled ([WakeRandom]), each with its own settings. */
    RANDOM("random");

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
    val shakeCount: Int = DEFAULT_SHAKE_COUNT,
    /** How far the ripple bends the picture, in percent of the screen's height. */
    val ripplePercent: Int = DEFAULT_RIPPLE_PERCENT,
    val glitchPercent: Int = DEFAULT_GLITCH_PERCENT,
    /** Blinds open across the screen in columns rather than down it in rows. */
    val blindsVertical: Boolean = false,
    val blindsCount: Int = DEFAULT_BLINDS_COUNT,
    /** How far past full brightness the breath swells, in percent. */
    val breathePercent: Int = DEFAULT_BREATHE_PERCENT,
    /** Random's own settings for each style it picks; missing ones are the defaults. */
    val random: Map<WakeTransitionStyle, WakeTransitionSettings> = emptyMap()
) {
    fun randomFor(style: WakeTransitionStyle): WakeTransitionSettings =
        random[style] ?: WakeTransitionSettings(style = style)

    companion object {
        const val MIN_DURATION_MS = 50
        const val MAX_DURATION_MS = 1000
        const val DEFAULT_DURATION_MS = 250
        const val MIN_ZOOM_PERCENT = 1
        const val MAX_ZOOM_PERCENT = 150
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
        const val MIN_RIPPLE_PERCENT = 1
        const val MAX_RIPPLE_PERCENT = 10
        const val DEFAULT_RIPPLE_PERCENT = 4
        const val MIN_GLITCH_PERCENT = 10
        const val MAX_GLITCH_PERCENT = 100
        const val DEFAULT_GLITCH_PERCENT = 60
        const val MIN_BLINDS_COUNT = 2
        const val MAX_BLINDS_COUNT = 24
        const val DEFAULT_BLINDS_COUNT = 8
        const val MIN_BREATHE_PERCENT = 0
        const val MAX_BREATHE_PERCENT = 30
        const val DEFAULT_BREATHE_PERCENT = 10

        /** The styles Random picks from. */
        val RANDOM_POOL = WakeTransitionStyle.entries - WakeTransitionStyle.RANDOM

        fun sanitizeZoom(percent: Int): Int = percent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)

        fun sanitizeStretch(percent: Int): Int = percent.coerceIn(MIN_STRETCH_PERCENT, MAX_STRETCH_PERCENT)

        fun sanitizeShake(percent: Int): Int = percent.coerceIn(MIN_SHAKE_PERCENT, MAX_SHAKE_PERCENT)

        fun sanitizeShakeCount(count: Int): Int = count.coerceIn(MIN_SHAKE_COUNT, MAX_SHAKE_COUNT)

        fun sanitizeRipple(percent: Int): Int = percent.coerceIn(MIN_RIPPLE_PERCENT, MAX_RIPPLE_PERCENT)

        fun sanitizeGlitch(percent: Int): Int = percent.coerceIn(MIN_GLITCH_PERCENT, MAX_GLITCH_PERCENT)

        fun sanitizeBlinds(count: Int): Int = count.coerceIn(MIN_BLINDS_COUNT, MAX_BLINDS_COUNT)

        fun sanitizeBreathe(percent: Int): Int = percent.coerceIn(MIN_BREATHE_PERCENT, MAX_BREATHE_PERCENT)

        /** Styles that move in time, not just towards rest, and so take the wake's time unshaped. */
        val EVEN_PACED = setOf(
            WakeTransitionStyle.SHAKE,
            WakeTransitionStyle.RIPPLE,
            WakeTransitionStyle.GLITCH,
            WakeTransitionStyle.BREATHE
        )
    }
}

/**
 * The wake transition in progress, read by every renderer on both backends.
 * Zoom moves only the photo (the renderers' wallpaper zoom); every other
 * style is drawn over the finished frame by the wake post pass
 * (WakePostRenderer on OpenGL ES, the native engine on Vulkan, wake.frag).
 */
object WakeTransition {
    const val MODE_NONE = 0
    const val MODE_COLOR_FILL = 1
    const val MODE_BLUR = 2
    const val MODE_STRETCH = 3
    const val MODE_SHAKE = 4
    const val MODE_RIPPLE = 5
    const val MODE_GLITCH = 6
    const val MODE_BLINDS = 7
    const val MODE_BREATHE = 8
    /** A twist's turn, in radians, per unit of shake (a share of the screen's height). */
    private const val TWIST_RADIANS = 0.6f

    @Volatile var zoom = 1f
        private set
    @Volatile var postMode = MODE_NONE
        private set
    /** 1 at the start of the post pass, 0 when it is done. */
    @Volatile var postAmount = 0f
        private set
    /**
     * The post pass style's own values, as wake.frag reads them: Stretch's
     * reach (negative inwards), Ripple's depth, Glitch's strength, Blinds'
     * strip count, Breathe's swell; [paramX] to [paramZ] are Shake's offset
     * and turn, and [paramX] whether Blinds open across.
     */
    @Volatile var param = 0f
        private set
    @Volatile var paramX = 0f
        private set
    @Volatile var paramY = 0f
        private set
    @Volatile var paramZ = 0f
        private set

    /**
     * Colour Fill is playing the wake itself (a black and white lock screen):
     * it fills from the middle of the screen, as the wake always does, not
     * from its own fill point (Fine tuning, or Pro's unlock-method origin).
     */
    @Volatile var fillFromCentre = false

    /**
     * [remaining] runs from 1 when the wake starts to 0 when it ends. The
     * [WakeTransitionSettings.EVEN_PACED] styles take it unshaped (linear in
     * time), so a shake's swings, say, keep an even beat.
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
                post(MODE_STRETCH, left, param = if (settings.stretchInward) -reach else reach)
            }
            WakeTransitionStyle.SHAKE -> {
                // Starts still, swings [shakeCount] times, dying away as it goes.
                val swing = WakeTransitionSettings.sanitizeShake(settings.shakePercent) / 100f * left *
                    sin((1f - left) * WakeTransitionSettings.sanitizeShakeCount(settings.shakeCount) * 2f * PI.toFloat())
                post(
                    MODE_SHAKE,
                    left,
                    x = if (settings.shakeDirection == WakeShakeDirection.SIDEWAYS) swing else 0f,
                    y = if (settings.shakeDirection == WakeShakeDirection.VERTICAL) swing else 0f,
                    z = if (settings.shakeDirection == WakeShakeDirection.TWIST) swing * TWIST_RADIANS else 0f
                )
            }
            WakeTransitionStyle.RIPPLE ->
                post(MODE_RIPPLE, left, param = WakeTransitionSettings.sanitizeRipple(settings.ripplePercent) / 100f)
            WakeTransitionStyle.GLITCH ->
                post(MODE_GLITCH, left, param = WakeTransitionSettings.sanitizeGlitch(settings.glitchPercent) / 100f)
            WakeTransitionStyle.BLINDS -> post(
                MODE_BLINDS,
                left,
                param = WakeTransitionSettings.sanitizeBlinds(settings.blindsCount).toFloat(),
                x = if (settings.blindsVertical) 1f else 0f
            )
            WakeTransitionStyle.BREATHE ->
                post(MODE_BREATHE, left, param = WakeTransitionSettings.sanitizeBreathe(settings.breathePercent) / 100f)
            // Resolved to one of the others before it plays (WakeRandom).
            WakeTransitionStyle.RANDOM -> Unit
        }
    }

    fun clear() {
        zoom = 1f
        postAmount = 0f
        postMode = MODE_NONE
        param = 0f
        paramX = 0f
        paramY = 0f
        paramZ = 0f
    }

    private fun post(mode: Int, amount: Float, param: Float = 0f, x: Float = 0f, y: Float = 0f, z: Float = 0f) {
        this.param = param
        paramX = x
        paramY = y
        paramZ = z
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
