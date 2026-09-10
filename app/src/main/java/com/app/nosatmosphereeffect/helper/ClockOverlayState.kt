package com.app.nosatmosphereeffect.helper

/**
 * Everything a renderer needs in order to draw the wallpaper clock, in one
 * object.
 *
 * ## Why this exists
 *
 * The clock started out as an Atmosphere-only feature, so its settings live as
 * a dozen flat fields on [com.app.nosatmosphereeffect.renderer.AtmosphereRenderState].
 * Copying those twelve fields into five more render states — and then into
 * five more renderers, five controllers, five services and both backends —
 * would have meant twelve chances per effect to forget one. Grouping them
 * means an effect gains the clock by carrying a single field.
 *
 * Atmosphere itself deliberately keeps its flat fields: they are load-bearing
 * for its Vulkan host and its unit tests, and rewriting working code to match
 * a new convention is how working code stops working. [fromAtmosphere] bridges
 * the two so there is still only one implementation of the drawing rules.
 *
 * ## Colours
 *
 * [requestedColor] is the raw preference, which may be [ClockPalette.AUTO].
 * [color] is what the face is actually drawn in — always a concrete opaque
 * ARGB value. Keeping both means the wallpaper-derived tint can be re-derived
 * off the render thread and folded in later without the renderer ever having
 * to know that "auto" is a thing.
 */
data class ClockOverlayState(
    val enabled: Boolean = false,
    /**
     * Whether the subject is drawn back over the clock. Only meaningful for
     * effects whose display pass has a subject mask to work from — see
     * [AtmosphereClockPolicy.supportsDepth].
     */
    val depthEnabled: Boolean = AtmosphereClockPolicy.DEFAULT_DEPTH,
    val styleId: String = ClockStyle.DEFAULT.id,
    val showSeconds: Boolean = AtmosphereClockPolicy.DEFAULT_SECONDS,
    val animate: Boolean = AtmosphereClockPolicy.DEFAULT_ANIMATE,
    val centerX: Float = AtmosphereClockPolicy.DEFAULT_CENTER_X,
    val top: Float = AtmosphereClockPolicy.DEFAULT_TOP,
    val height: Float = AtmosphereClockPolicy.DEFAULT_HEIGHT,
    /**
     * Per-axis stretch on top of [height]. 1.0 leaves the face at its natural
     * proportions; below 1 on width gives the tall, narrow column look.
     */
    val widthScale: Float = AtmosphereClockPolicy.DEFAULT_WIDTH_SCALE,
    val heightScale: Float = AtmosphereClockPolicy.DEFAULT_HEIGHT_SCALE,
    val opacity: Float = AtmosphereClockPolicy.DEFAULT_OPACITY,
    /** The stored preference; may be [ClockPalette.AUTO]. */
    val requestedColor: Int = AtmosphereClockPolicy.DEFAULT_COLOR,
    /** Already resolved — never [ClockPalette.AUTO]. */
    val color: Int = ClockPalette.DEFAULT_FALLBACK,
    val hourFormat: String = AtmosphereClockPolicy.DEFAULT_HOUR_FORMAT,
    /** "lock", "home" or "both", already collapsed onto what the effect can do. */
    val screenId: String = ClockScreen.DEFAULT.id,
    /**
     * The effect's own shader progress at each end of its transition. Effects
     * disagree about which end is the lock screen, so the fade is computed
     * from the normalised unlock fraction rather than from raw progress — see
     * [ClockScreenPolicy].
     */
    val lockedProgress: Float = 0f,
    val unlockedProgress: Float = 1f,
    /**
     * Vulkan-only, dynamic: the clock bitmap's width/height ratio, refreshed
     * whenever a fresh face is uploaded, so the shader can size the quad
     * without a native round-trip. The GLES path reads this from its own
     * texture provider instead.
     */
    val textureAspect: Float = 1f,
    /**
     * Vulkan-only, dynamic: true once a real face has been uploaded. Until
     * then the shader must not sample the clock binding — an unwritten
     * optional binding holds the engine's opaque 1x1 clear texture, which
     * would paint a black rectangle where the clock belongs.
     */
    val faceUploaded: Boolean = false
) {
    val style: ClockStyle
        get() = ClockStyle.fromId(styleId)

    val screen: ClockScreen
        get() = ClockScreen.fromId(screenId)

    val hourFormatOverride: Boolean?
        get() = AtmosphereClockPolicy.hourFormatOverride(hourFormat)

    fun sanitized(): ClockOverlayState {
        return copy(
            styleId = AtmosphereClockPolicy.sanitizeStyleId(styleId),
            centerX = AtmosphereClockPolicy.sanitizeCenterX(centerX),
            top = AtmosphereClockPolicy.sanitizeTop(top),
            height = AtmosphereClockPolicy.sanitizeHeight(height),
            widthScale = AtmosphereClockPolicy.sanitizeAxisScale(widthScale),
            heightScale = AtmosphereClockPolicy.sanitizeAxisScale(heightScale),
            opacity = AtmosphereClockPolicy.sanitizeOpacity(opacity),
            requestedColor = AtmosphereClockPolicy.sanitizeColor(requestedColor),
            // A stray AUTO reaching a renderer would draw an opaque black
            // clock, so it is collapsed to the fallback here rather than
            // trusted to have been resolved upstream.
            color = if (ClockPalette.isAuto(color)) {
                ClockPalette.DEFAULT_FALLBACK
            } else {
                color or (0xFF shl 24)
            },
            hourFormat = AtmosphereClockPolicy.sanitizeHourFormat(hourFormat),
            screenId = ClockScreenPolicy.sanitizeScreenId(screenId),
            lockedProgress = lockedProgress.finiteOr(0f),
            unlockedProgress = unlockedProgress.finiteOr(1f),
            textureAspect = textureAspect.finiteOr(1f).coerceIn(0.05f, 20f)
        )
    }

    /**
     * How strongly the clock should be drawn at [progress], 0..1, with the
     * user's own opacity already folded in. Both backends read this rather
     * than computing their own curve, which is what keeps them agreeing.
     */
    fun effectiveOpacity(progress: Float): Float {
        if (!enabled) return 0f
        return opacity * ClockScreenPolicy.visibility(
            screen = screen,
            progress = progress,
            lockedProgress = lockedProgress,
            unlockedProgress = unlockedProgress
        )
    }

    /**
     * The height fraction the renderers should actually use.
     *
     * ## Why the stretch is folded in here rather than passed down
     *
     * Both backends already size the clock as `height` and
     * `height * textureAspect / surfaceAspect`. Feeding them a pre-stretched
     * height and a pre-stretched aspect reproduces any width/height pair
     * exactly, with no new uniform, no new push-constant field, and no shader
     * change on either backend:
     *
     *     width  = (height * heightScale) * (aspect * widthScale / heightScale)
     *            = height * aspect * widthScale                        (as wanted)
     *     height = height * heightScale                                (as wanted)
     *
     * The alternative — two more floats threaded through five JNI signatures
     * and ten shaders — buys nothing, because the shaders only ever multiply
     * these two numbers together anyway.
     */
    val renderHeight: Float
        get() = height * heightScale

    /**
     * The top edge the renderers should actually use.
     *
     * [top] is the top of the box at the *unstretched* size, because that is
     * what the drag gesture on the calibration screen sets. Growing
     * [heightScale] from there would extend the box downwards only, so the
     * clock would visibly sink as the height slider went up — which is
     * exactly what it did before this existed. Re-centring means the height
     * slider changes the shape and nothing else.
     */
    val renderTop: Float
        get() = top + (height - renderHeight) / 2f

    /**
     * The texture aspect the renderers should actually use, given the face
     * bitmap's real [rawAspect]. See [renderHeight] for the algebra.
     */
    fun renderTextureAspect(rawAspect: Float): Float {
        val safeRaw = if (rawAspect.isFinite() && rawAspect > 0f) rawAspect else 1f
        val safeHeightScale = if (heightScale > 0f) heightScale else 1f
        return safeRaw * widthScale / safeHeightScale
    }

    /** True when anything on screen needs a subject mask for the clock. */
    fun needsSubjectMask(): Boolean = enabled && depthEnabled

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }
}
