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
    const val SECONDS_KEY = "atmosphere_clock_seconds"
    const val ANIMATE_KEY = "atmosphere_clock_animate"
    const val CENTER_X_KEY = "atmosphere_clock_center_x"
    const val TOP_KEY = "atmosphere_clock_top"
    const val HEIGHT_KEY = "atmosphere_clock_height"
    const val OPACITY_KEY = "atmosphere_clock_opacity"
    /**
     * Stored as an ARGB int. [ClockPalette.AUTO] (0) means "follow the
     * wallpaper", which is the default — a plain white clock reads as pasted
     * on, a wallpaper-tinted one reads as part of the image.
     */
    const val COLOR_KEY = "atmosphere_clock_color"
    /** "system" (default), "12" or "24". */
    const val HOUR_FORMAT_KEY = "atmosphere_clock_hour_format"

    const val DEFAULT_CENTER_X = 0.5f
    const val DEFAULT_TOP = 0.14f
    const val DEFAULT_HEIGHT = 0.16f
    const val DEFAULT_OPACITY = 1f
    const val DEFAULT_DEPTH = true
    const val DEFAULT_SECONDS = false
    const val DEFAULT_ANIMATE = true
    const val DEFAULT_COLOR = ClockPalette.AUTO
    const val HOUR_FORMAT_SYSTEM = "system"
    const val HOUR_FORMAT_12 = "12"
    const val HOUR_FORMAT_24 = "24"
    const val DEFAULT_HOUR_FORMAT = HOUR_FORMAT_SYSTEM

    private const val MIN_CENTER_X = 0.05f
    private const val MAX_CENTER_X = 0.95f
    private const val MIN_TOP = 0.02f
    private const val MAX_TOP = 0.90f
    private const val MIN_HEIGHT = 0.03f
    private const val MAX_HEIGHT = 0.40f

    /** All keys this feature owns, for the Advanced Settings reset path. */
    val ALL_KEYS: List<String> = listOf(
        ENABLED_KEY,
        DEPTH_KEY,
        STYLE_KEY,
        SECONDS_KEY,
        ANIMATE_KEY,
        COLOR_KEY,
        HOUR_FORMAT_KEY,
        CENTER_X_KEY,
        TOP_KEY,
        HEIGHT_KEY,
        OPACITY_KEY
    )

    fun supportsEffect(effectId: String?): Boolean = effectId == "ORIGINAL"

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

    fun sanitizeHeight(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_HEIGHT
        return value.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
    }

    fun sanitizeOpacity(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_OPACITY
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
        if (value == ClockPalette.AUTO) return ClockPalette.AUTO
        return value or (0xFF shl 24)
    }

    /**
     * The clock only draws on the lock-screen side of the transition, so it
     * fades out over the first slice of the unlock animation. Shared by both
     * backends so they agree on the curve.
     */
    const val LOCK_FADE_RANGE = 0.25f

    fun lockFade(progress: Float): Float {
        if (!progress.isFinite()) return 0f
        return (1f - progress / LOCK_FADE_RANGE).coerceIn(0f, 1f)
    }
}
