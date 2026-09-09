package com.app.nosatmosphereeffect.helper

import kotlin.math.abs

/**
 * The side (or sides) of the lock/unlock transition on which an effect leaves
 * the wallpaper sharp.
 *
 * "Sharp" here means the photo is showing essentially unmodified — not
 * blurred, not stylised into dots or line art, not refracted through glass
 * ribs. It is the only place a clock overlay is worth drawing, for two
 * reasons that happen to agree:
 *
 * - Legibility. Glyphs over halftone dots or a sketch fight the pattern, and
 *   glyphs over the atmosphere clouds sit on a surface that is already
 *   carrying colour.
 * - Depth. The subject mask is computed against the sharp image, so drawing
 *   the sharp subject back over the clock only composites correctly where the
 *   background is showing that same sharp image. Over a blurred or stylised
 *   background it reads as a cut-out.
 */
enum class ClockSharpSide {
    /** Sharp while locked; the effect is applied on the home screen. */
    LOCK,

    /** The effect is applied while locked; sharp on the home screen. */
    HOME,

    /**
     * Legible on both sides, so the user gets to choose.
     *
     * "Sharp" is doing slightly less work here than the enum name suggests.
     * What actually matters for the clock is whether the effect *displaces or
     * softens* the image: a blur or a refraction moves pixels away from where
     * the clock expects them and turns the edge of a glyph into mush, while a
     * recolour, a line drawing or a dot screen leaves the geometry exactly
     * where it was and stays high-contrast. Colour Fill, Sketch and Halftone
     * are all the second kind, so a clock sits on either end of their
     * transition perfectly well.
     */
    BOTH
}

/** Where the user has asked for the clock to appear. */
enum class ClockScreen(val id: String, val label: String) {
    LOCK("lock", "Lock screen"),
    HOME("home", "Home screen"),
    BOTH("both", "Both");

    companion object {
        val DEFAULT = LOCK

        fun fromId(id: String?): ClockScreen {
            if (id == null) return DEFAULT
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * Decides where the clock may appear for a given effect, and how strongly it
 * is drawn at a given point in the transition.
 *
 * ## Why this does not carry a per-effect fade table
 *
 * Effects disagree about which end of the shader's 0..1 progress range is the
 * lock screen — Atmosphere locks at 0, Reverse Atmosphere at 1, Halftone has
 * its own constants, Glass runs progress through
 * [GlassEffectPolicy.shaderProgress]. Every one of those already publishes
 * `lockedProgress` and `unlockedProgress` on its wallpaper service, so this
 * object works in a normalised *unlock fraction* (0 = fully locked, 1 = fully
 * unlocked) derived from that pair. Adding a new effect needs nothing here
 * beyond a row in [sharpSide].
 */
object ClockScreenPolicy {

    /**
     * Fraction of the transition over which the clock fades. Deliberately
     * short: the clock should be gone before the effect has visibly started,
     * not cross-dissolving with it.
     */
    const val FADE_RANGE = 0.25f

    /**
     * Which side each effect leaves sharp.
     *
     * Read against EffectCatalog's transition descriptions:
     *
     * | Effect             | Locked          | Unlocked        | Sharp |
     * |--------------------|-----------------|-----------------|-------|
     * | ORIGINAL           | photo           | blur + clouds   | LOCK  |
     * | REVERSE            | blur + clouds   | photo           | HOME  |
     * | GLASS              | photo           | reeded glass    | LOCK  |
     * | GLASS_REVERSE      | reeded glass    | photo           | HOME  |
     * | COLORFILL          | monochrome      | colour          | BOTH  |
     * | COLORFILL_REVERSE  | colour          | monochrome      | BOTH  |
     * | NEON               | sketch          | photo           | HOME  |
     * | NEON_REVERSE       | photo           | sketch          | LOCK  |
     * | FROSTED            | photo           | blur            | LOCK  |
     * | FROSTED_REVERSE    | blur            | photo           | HOME  |
     * | HALFTONE           | photo           | halftone dots   | LOCK  |
     * | HALFTONE_REVERSE   | halftone dots   | photo           | HOME  |
     *
     * Unknown ids fall back to LOCK, which is the conservative answer: a
     * clock that only shows on the lock screen is never worse than wrong.
     */
    fun sharpSide(effectId: String?): ClockSharpSide = when (effectId) {
        "COLORFILL", "COLORFILL_REVERSE" -> ClockSharpSide.BOTH
        "REVERSE",
        "GLASS_REVERSE",
        "NEON",
        "FROSTED_REVERSE",
        "HALFTONE_REVERSE" -> ClockSharpSide.HOME
        else -> ClockSharpSide.LOCK
    }

    /**
     * True when the effect is sharp on both sides and the settings UI should
     * therefore offer the lock/home/both choice. Everywhere else the answer
     * is forced by the effect and the UI should say so rather than showing a
     * control that cannot take effect.
     */
    fun offersChoice(effectId: String?): Boolean =
        sharpSide(effectId) == ClockSharpSide.BOTH

    /** What the choice defaults to before the user has expressed one. */
    fun defaultScreen(effectId: String?): ClockScreen =
        when (sharpSide(effectId)) {
            ClockSharpSide.LOCK -> ClockScreen.LOCK
            ClockSharpSide.HOME -> ClockScreen.HOME
            // Both sides work, so both sides is the friendliest default —
            // the clock simply stays put across the transition.
            ClockSharpSide.BOTH -> ClockScreen.BOTH
        }

    /**
     * Collapses the stored preference onto what the effect can actually do.
     * A request for a side the effect never leaves sharp is answered with the
     * side it does, rather than with "nowhere" — the user asked for a clock.
     */
    fun resolveScreen(effectId: String?, requested: ClockScreen?): ClockScreen {
        val side = sharpSide(effectId)
        if (side != ClockSharpSide.BOTH) return defaultScreen(effectId)
        return requested ?: ClockScreen.BOTH
    }

    fun sanitizeScreenId(value: String?): String = ClockScreen.fromId(value).id

    /**
     * Normalises an effect's own shader progress into 0 (fully locked) .. 1
     * (fully unlocked), using the bounds the effect's wallpaper service
     * already declares.
     *
     * A degenerate span (the two bounds equal, which would mean the effect
     * does not animate at all) reports "locked" rather than dividing by zero.
     */
    fun unlockFraction(
        progress: Float,
        lockedProgress: Float,
        unlockedProgress: Float
    ): Float {
        if (!progress.isFinite()) return 0f
        val span = unlockedProgress - lockedProgress
        if (!span.isFinite() || abs(span) < 1e-4f) return 0f
        return ((progress - lockedProgress) / span).coerceIn(0f, 1f)
    }

    /**
     * How strongly to draw the clock, 0..1, at a point in the transition.
     * Multiplied into the user's opacity by both backends so the two agree on
     * the curve.
     *
     * [ClockScreen.BOTH] holds at 1 the whole way across: the effect is sharp
     * at both ends, so there is nothing to fade for.
     */
    fun visibility(screen: ClockScreen, unlockFraction: Float): Float {
        if (!unlockFraction.isFinite()) return 0f
        val fraction = unlockFraction.coerceIn(0f, 1f)
        return when (screen) {
            ClockScreen.LOCK -> (1f - fraction / FADE_RANGE).coerceIn(0f, 1f)
            ClockScreen.HOME ->
                ((fraction - (1f - FADE_RANGE)) / FADE_RANGE).coerceIn(0f, 1f)
            ClockScreen.BOTH -> 1f
        }
    }

    /** Convenience overload for callers holding raw shader progress. */
    fun visibility(
        screen: ClockScreen,
        progress: Float,
        lockedProgress: Float,
        unlockedProgress: Float
    ): Float = visibility(
        screen,
        unlockFraction(progress, lockedProgress, unlockedProgress)
    )
}
