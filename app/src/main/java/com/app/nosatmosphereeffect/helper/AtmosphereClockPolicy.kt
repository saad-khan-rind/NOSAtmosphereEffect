package com.app.nosatmosphereeffect.helper

/**
 * Defines the clock overlay drawn into the wallpaper by the original
 * Atmosphere effect (blobs/clouds renderer, [AtmosphereRenderer] on GLES and
 * VulkanAtmosphereHost on Vulkan).
 *
 * Excludes "REVERSE": that direction runs through BlurToSharpRenderer, which
 * has no clock compositing pass.
 *
 * Geometry is stored as screen-fraction values (matching the shader's
 * screen-locked effect-coordinate space) so the same numbers drive the live
 * wallpaper, both backends, and the ClockAdjustActivity preview with no
 * conversion anywhere.
 *
 * ## Depth is independent of Glass
 *
 * [DEPTH_KEY] is the clock's own switch for "draw the subject back over the
 * clock so it looks like the clock is behind them". The first version of this
 * feature piggybacked on the Glass effect's background-only flag, which meant
 * the depth effect silently did nothing whenever Glass was off. The two are
 * separate settings now; both feed the same subject-mask machinery, and the
 * mask is computed when *either* asks for it (see
 * AtmosphereRenderState.needsSubjectMask).
 */
object AtmosphereClockPolicy {
    const val ENABLED_KEY = "atmosphere_clock_enabled"
    const val DEPTH_KEY = "atmosphere_clock_depth"
    const val STYLE_KEY = "atmosphere_clock_style"
    /** Draws the day and date, placed and sized on its own. */
    const val DATE_KEY = "atmosphere_clock_date"
    const val ANIMATE_KEY = "atmosphere_clock_animate"
    const val CENTER_X_KEY = "atmosphere_clock_center_x"
    const val TOP_KEY = "atmosphere_clock_top"
    const val HEIGHT_KEY = "atmosphere_clock_height"
    /**
     * Per-axis stretch, applied on top of [HEIGHT_KEY].
     *
     * [HEIGHT_KEY] is the overall size; these two reshape it. Kept as separate
     * multipliers rather than folded into one "width" fraction so that
     * changing the size does not silently undo a shape the user tuned, and so
     * both default to a no-op 1.0 for everyone who never touches them.
     */
    const val WIDTH_SCALE_KEY = "atmosphere_clock_width_scale"
    const val HEIGHT_SCALE_KEY = "atmosphere_clock_height_scale"

    /**
     * The date's own placement, stored exactly like the clock's and dragged
     * the same way. It is drawn into the same bitmap as the digits — one
     * texture, one rectangle, on every effect and both backends — so these
     * are converted to a position relative to the digits before they reach
     * the face; see [ClockBoxPlacement.relativeDateBox].
     */
    const val DATE_CENTER_X_KEY = "atmosphere_clock_date_center_x"
    const val DATE_TOP_KEY = "atmosphere_clock_date_top"
    const val DATE_HEIGHT_KEY = "atmosphere_clock_date_height"
    const val DATE_WIDTH_SCALE_KEY = "atmosphere_clock_date_width_scale"

    /**
     * Which meaning the stored geometry has.
     *
     * Version 1 stored the face *texture's* rectangle; version 2 stores the
     * digits' own box, so that a face's animation margin — and the date, which
     * changes the bitmap's size wherever it is dragged to — no longer moves
     * the clock. [migrateGeometry] converts a version 1 value once.
     */
    const val GEOMETRY_VERSION_KEY = "atmosphere_clock_geometry_version"
    const val GEOMETRY_VERSION = 2
    const val OPACITY_KEY = "atmosphere_clock_opacity"
    /**
     * How frosted the glass is, 0..1: 0 is clear glass that shows the
     * wallpaper sharply through the digits, 1 is milky and diffuse.
     */
    const val FROST_KEY = "atmosphere_clock_frost"
    /** The Adaptive face's stroke weight, 0 thin .. 1 bold. */
    const val WEIGHT_KEY = "atmosphere_clock_weight"
    /**
     * Stored as an ARGB int. [ClockPalette.AUTO] (0) means "follow the
     * wallpaper", which is the default — a plain white clock reads as pasted
     * on, a wallpaper-tinted one reads as part of the image.
     */
    const val COLOR_KEY = "atmosphere_clock_color"
    /** "system" (default), "12" or "24". */
    const val HOUR_FORMAT_KEY = "atmosphere_clock_hour_format"
    /**
     * "lock", "home" or "both" — see [ClockScreen]. Only meaningful for
     * effects that leave the wallpaper sharp on both sides of the
     * transition; everywhere else [ClockScreenPolicy.resolveScreen]
     * collapses it onto the one side that works.
     */
    const val SCREEN_KEY = "atmosphere_clock_screen"

    const val DEFAULT_CENTER_X = 0.5f
    const val DEFAULT_TOP = 0.17f
    /**
     * Fraction of screen height the DIGITS occupy.
     *
     * Cut from 0.24 when the stored geometry stopped describing the face
     * texture and started describing the digits inside it. The texture is
     * about a third margin, so keeping the old number would have handed
     * everyone who had never opened the calibration screen a clock half as
     * big again as the one they had.
     */
    const val DEFAULT_HEIGHT = 0.15f
    const val DEFAULT_WIDTH_SCALE = 1f
    const val DEFAULT_HEIGHT_SCALE = 1f
    // Wider than the sliders these replaced allowed: the box is dragged, so
    // the limit people run into should be the shape they wanted, not a number
    // chosen to keep a slider tidy.
    const val MIN_AXIS_SCALE = 0.35f
    const val MAX_AXIS_SCALE = 2.6f
    const val DEFAULT_OPACITY = 1f
    /**
     * Clear by default. Frost is a departure from the glass rather than the
     * normal state of it: it is the setting that makes a clock milky, and a
     * clock that starts milky reads as one that has gone cloudy.
     */
    const val DEFAULT_FROST = 0f
    const val DEFAULT_WEIGHT = 0.55f
    const val DEFAULT_DEPTH = true
    const val DEFAULT_DATE = false

    /** The date sits just above the clock until it is dragged elsewhere. */
    const val DEFAULT_DATE_CENTER_X = 0.5f
    const val DEFAULT_DATE_HEIGHT = 0.032f
    const val DEFAULT_DATE_TOP = 0.122f
    const val DEFAULT_DATE_WIDTH_SCALE = 1f
    const val DEFAULT_ANIMATE = true
    const val DEFAULT_COLOR = ClockPalette.AUTO
    const val HOUR_FORMAT_SYSTEM = "system"
    const val HOUR_FORMAT_12 = "12"
    const val HOUR_FORMAT_24 = "24"
    const val DEFAULT_HOUR_FORMAT = HOUR_FORMAT_SYSTEM

    // Loose validity guards, not placement rules.
    //
    // These bound what may be *stored*; where the clock may actually sit is
    // decided by ClockBoxPlacement, which clamps the box the user sees to the
    // screen. A tighter floor here used to stop a tall clock further down the
    // screen than a short one, which is the opposite of a limit anyone asked
    // for.
    private const val MIN_CENTER_X = 0f
    private const val MAX_CENTER_X = 1f
    private const val MIN_TOP = -0.5f
    private const val MAX_TOP = 1f
    const val MIN_HEIGHT = 0.03f
    /**
     * Large enough for the digits to fill the screen's height; how big the
     * clock may actually get is capped by the screen, in ClockBoxPlacement.
     */
    const val MAX_HEIGHT = 1.6f

    /** The clock's placement as stored, ready for [ClockBoxPlacement]. */
    val DEFAULT_PLACEMENT = ClockPlacement(
        centerX = DEFAULT_CENTER_X,
        top = DEFAULT_TOP,
        height = DEFAULT_HEIGHT,
        widthScale = DEFAULT_WIDTH_SCALE
    )

    val DEFAULT_DATE_PLACEMENT = ClockPlacement(
        centerX = DEFAULT_DATE_CENTER_X,
        top = DEFAULT_DATE_TOP,
        height = DEFAULT_DATE_HEIGHT,
        widthScale = DEFAULT_DATE_WIDTH_SCALE
    )

    /**
     * Nominal share of a version 1 face texture that the digits occupied, for
     * [migrateGeometry]. Measured from the default face: the exact figures
     * depend on the style and on the device's fonts, so this converts an old
     * clock to about the same size in about the same place rather than
     * exactly — which is all the old numbers were ever worth, since the same
     * stored values already drew different faces differently.
     */
    private const val LEGACY_CONTENT_HEIGHT = 0.63f
    private const val LEGACY_CONTENT_TOP = 0.18f

    /**
     * Converts a version 1 (texture-relative) placement to version 2
     * (digits-relative). [ClockPlacement.widthScale] needs no conversion: it
     * is a ratio of the width to the height, and both shrank by the same
     * factor.
     */
    fun migrateGeometry(legacy: ClockPlacement): ClockPlacement = ClockPlacement(
        centerX = sanitizeCenterX(legacy.centerX),
        top = sanitizeTop(legacy.top + LEGACY_CONTENT_TOP * legacy.height),
        height = sanitizeHeight(legacy.height * LEGACY_CONTENT_HEIGHT),
        widthScale = sanitizeAxisScale(legacy.widthScale)
    )

    /** All keys this feature owns, for the Advanced Settings reset path. */
    val ALL_KEYS: List<String> = listOf(
        ENABLED_KEY,
        DEPTH_KEY,
        STYLE_KEY,
        DATE_KEY,
        ANIMATE_KEY,
        COLOR_KEY,
        HOUR_FORMAT_KEY,
        SCREEN_KEY,
        CENTER_X_KEY,
        TOP_KEY,
        HEIGHT_KEY,
        WIDTH_SCALE_KEY,
        HEIGHT_SCALE_KEY,
        DATE_CENTER_X_KEY,
        DATE_TOP_KEY,
        DATE_HEIGHT_KEY,
        DATE_WIDTH_SCALE_KEY,
        GEOMETRY_VERSION_KEY,
        FROST_KEY,
        WEIGHT_KEY,
        OPACITY_KEY
    )

    /**
     * Effects whose renderers actually composite the clock.
     *
     * Deliberately a set rather than a predicate over EffectCatalog: the
     * preference screen must not offer a clock for an effect whose renderer
     * would silently ignore it. Add an id here in the same change that wires
     * that effect's compositing, never before.
     *
     * Every effect is listed now, and every one is wired on BOTH backends. An
     * id belongs here only when the GLES renderer and the Vulkan host both
     * draw it — half a pair would give the same effect a clock on one device
     * and not on another, which reads as a bug rather than a limitation.
     */
    private val SUPPORTED_EFFECT_IDS = setOf(
        "ORIGINAL",
        "REVERSE",
        "GLASS",
        "GLASS_REVERSE",
        "COLORFILL",
        "COLORFILL_REVERSE",
        "NEON",
        "NEON_REVERSE",
        "FROSTED",
        "FROSTED_REVERSE",
        "HALFTONE",
        "HALFTONE_REVERSE"
    )

    /**
     * Effects that can draw the subject back over the clock.
     *
     * Every effect now binds a subject mask in its display pass, so this is
     * the same set as [SUPPORTED_EFFECT_IDS]. It stays a separate name because
     * the two answer different questions: an effect could gain the clock
     * before it gains segmentation. Keep the depth switch out of the settings
     * screen for anything that lands here without a mask in its display pass —
     * a switch that does nothing is worse than no switch.
     */
    private val DEPTH_EFFECT_IDS = SUPPORTED_EFFECT_IDS

    fun supportsEffect(effectId: String?): Boolean = effectId in SUPPORTED_EFFECT_IDS

    fun supportsDepth(effectId: String?): Boolean = effectId in DEPTH_EFFECT_IDS

    /**
     * The clock is single-image only for now.
     *
     * In playlist and theme modes the wallpaper swaps underneath the clock,
     * which breaks two things at once: the position that was calibrated
     * against one photo is wrong for the next, and the wallpaper-derived
     * "Auto" colour would have to re-derive on every rotation. Neither is
     * unsolvable, but neither is solved, so the honest behaviour is to keep
     * the clock off rather than show a badly placed one.
     */
    fun resolveEnabled(
        effectId: String?,
        requested: Boolean,
        singleImageMode: Boolean = true
    ): Boolean {
        return supportsEffect(effectId) && singleImageMode && requested
    }

    fun sanitizeCenterX(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_CENTER_X
        return value.coerceIn(MIN_CENTER_X, MAX_CENTER_X)
    }

    fun sanitizeTop(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_TOP
        return value.coerceIn(MIN_TOP, MAX_TOP)
    }

    /**
     * Both axes share one range and one sanitizer: they are the same kind of
     * quantity, and letting them drift apart is how a "width" slider ends up
     * able to reach a shape the "height" slider cannot.
     */
    fun sanitizeAxisScale(value: Float): Float {
        if (!value.isFinite()) return 1f
        return value.coerceIn(MIN_AXIS_SCALE, MAX_AXIS_SCALE)
    }

    fun sanitizeHeight(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_HEIGHT
        return value.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
    }

    fun sanitizeOpacity(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_OPACITY
        return value.coerceIn(0f, 1f)
    }

    fun sanitizeFrost(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_FROST
        return value.coerceIn(0f, 1f)
    }

    fun sanitizeStyleId(value: String?): String = ClockStyle.fromId(value).id

    fun sanitizeHourFormat(value: String?): String = when (value) {
        HOUR_FORMAT_12, HOUR_FORMAT_24 -> value
        else -> HOUR_FORMAT_SYSTEM
    }

    /** null means "follow the system setting". */
    fun hourFormatOverride(value: String?): Boolean? = when (sanitizeHourFormat(value)) {
        HOUR_FORMAT_12 -> false
        HOUR_FORMAT_24 -> true
        else -> null
    }

    /** Colours are stored opaque; a transparent value would hide the clock. */
    fun sanitizeColor(value: Int): Int {
        if (ClockPalette.followsWallpaper(value)) return value
        return value or (0xFF shl 24)
    }

    fun sanitizeWeight(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_WEIGHT
        return value.coerceIn(0f, 1f)
    }

    fun sanitizeScreenId(value: String?): String =
        ClockScreenPolicy.sanitizeScreenId(value)
}
