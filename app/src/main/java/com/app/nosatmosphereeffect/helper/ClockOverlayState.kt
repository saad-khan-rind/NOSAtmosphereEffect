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
    val showDate: Boolean = AtmosphereClockPolicy.DEFAULT_DATE,
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
    /**
     * The date's own placement, set and stored exactly like the clock's. It
     * shares the clock's bitmap, so the face converts these into a position
     * relative to the digits — see [ClockBoxPlacement.relativeDateBox].
     */
    val dateCenterX: Float = AtmosphereClockPolicy.DEFAULT_DATE_CENTER_X,
    val dateTop: Float = AtmosphereClockPolicy.DEFAULT_DATE_TOP,
    val dateHeight: Float = AtmosphereClockPolicy.DEFAULT_DATE_HEIGHT,
    val dateWidthScale: Float = AtmosphereClockPolicy.DEFAULT_DATE_WIDTH_SCALE,
    val opacity: Float = AtmosphereClockPolicy.DEFAULT_OPACITY,
    /** How frosted the glass is, 0..1. See [glassMeta] for how it travels. */
    val frost: Float = AtmosphereClockPolicy.DEFAULT_FROST,
    /** The Adaptive face's stroke weight, 0 thin .. 1 bold. */
    val weight: Float = AtmosphereClockPolicy.DEFAULT_WEIGHT,
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
    val faceUploaded: Boolean = false,
    /**
     * Vulkan-only, dynamic: where the digits sit inside the face bitmap,
     * refreshed whenever a fresh face is uploaded. The stored geometry
     * describes the digits, so turning it into the rectangle the shader
     * samples needs to know how much bitmap surrounds them — margin for the
     * animations, plus room for the date wherever it was placed.
     *
     * Defaults to "the bitmap is all digits", which is what a face with no
     * layout yet reports, so a path that never sets it still draws something
     * sane rather than nothing.
     */
    val faceContentTop: Float = 0f,
    val faceContentHeight: Float = 1f
) {
    val style: ClockStyle
        get() = ClockStyle.fromId(styleId)

    val screen: ClockScreen
        get() = ClockScreen.fromId(screenId)

    val hourFormatOverride: Boolean?
        get() = AtmosphereClockPolicy.hourFormatOverride(hourFormat)

    fun sanitized(): ClockOverlayState {
        val safeStyle = ClockStyle.fromId(styleId)
        return copy(
            styleId = safeStyle.id,
            // The Adaptive face is fitted around the subject and always drawn
            // over it. Depth would put the subject back on top wherever a
            // digit touches it — a spring's overshoot, a digit that could not
            // get any shorter — which is exactly what that face avoids.
            depthEnabled = depthEnabled && !safeStyle.adaptsToSubject,
            centerX = AtmosphereClockPolicy.sanitizeCenterX(centerX),
            top = AtmosphereClockPolicy.sanitizeTop(top),
            height = AtmosphereClockPolicy.sanitizeHeight(height),
            widthScale = AtmosphereClockPolicy.sanitizeAxisScale(widthScale),
            heightScale = AtmosphereClockPolicy.sanitizeAxisScale(heightScale),
            dateCenterX = AtmosphereClockPolicy.sanitizeCenterX(dateCenterX),
            dateTop = AtmosphereClockPolicy.sanitizeTop(dateTop),
            dateHeight = AtmosphereClockPolicy.sanitizeHeight(dateHeight),
            dateWidthScale = AtmosphereClockPolicy.sanitizeAxisScale(dateWidthScale),
            opacity = AtmosphereClockPolicy.sanitizeOpacity(opacity),
            frost = AtmosphereClockPolicy.sanitizeFrost(frost),
            weight = AtmosphereClockPolicy.sanitizeWeight(weight),
            requestedColor = AtmosphereClockPolicy.sanitizeColor(requestedColor),
            // A stray AUTO reaching a renderer would draw an opaque black
            // clock, so it is collapsed to the fallback here rather than
            // trusted to have been resolved upstream.
            color = if (ClockPalette.followsWallpaper(color)) {
                ClockPalette.DEFAULT_FALLBACK
            } else {
                color or (0xFF shl 24)
            },
            hourFormat = AtmosphereClockPolicy.sanitizeHourFormat(hourFormat),
            screenId = ClockScreenPolicy.sanitizeScreenId(screenId),
            lockedProgress = lockedProgress.finiteOr(0f),
            unlockedProgress = unlockedProgress.finiteOr(1f),
            textureAspect = textureAspect.finiteOr(1f).coerceIn(0.05f, 20f),
            faceContentTop = faceContentTop.finiteOr(0f).coerceIn(0f, 1f),
            faceContentHeight = faceContentHeight.finiteOr(1f).coerceIn(0.01f, 1f)
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
        get() = contentHeight / faceContentHeight.coerceIn(0.01f, 1f)

    /** The digits' own height on screen: what the user dragged. */
    val contentHeight: Float
        get() = height * heightScale

    /**
     * The digits' own top edge. [top] is the top at the *unstretched* size,
     * because that is what the drag gesture sets; growing [heightScale] from
     * there would extend the box downwards only.
     */
    val contentTop: Float
        get() = top + (height - contentHeight) / 2f

    /** The clock's placement, ready for [ClockBoxPlacement]. */
    val placement: ClockPlacement
        get() = ClockPlacement(
            centerX = centerX,
            top = contentTop,
            height = contentHeight,
            widthScale = widthScale
        )

    /** The date's placement, ready for [ClockBoxPlacement]. */
    val datePlacement: ClockPlacement
        get() = ClockPlacement(
            centerX = dateCenterX,
            top = dateTop,
            height = dateHeight,
            widthScale = dateWidthScale
        )

    /** Which of the two glass treatments this face is lit with. */
    val treatment: ClockTreatment
        get() = style.treatment

    /**
     * The single number the shaders read for the glass: 0 draws nothing,
     * `1 + frost` is a translucent face at that frost level, and 3 is the
     * original glass face, which has no frost. The Adaptive face is solid: 0.
     *
     * [FOLLOWS_WALLPAPER] is added when the colour comes from the wallpaper
     * (Auto, or the Adaptive shading) rather than being picked: the shaders
     * then drain the clock's colour wherever the effect has drained the
     * photo's, so a black-and-white screen gets a grey clock.
     *
     * Three settings in one float because the shaders take it in a slot that
     * already exists — `uClockGlass` on GLES, `clockMeta.w` on Vulkan. Adding
     * a field to six push-constant structs and six shader uniform blocks to
     * carry them separately is the kind of change that has broken this backend
     * before, and the encoding is exact.
     */
    val glassMeta: Float
        get() = glassMode +
            if (ClockPalette.followsWallpaper(requestedColor)) FOLLOWS_WALLPAPER else 0f

    /**
     * The treatment alone, without [glassMeta]'s colour flag: for the
     * calibration preview, which draws over the plain photo and so has no
     * effect for the colour to follow.
     */
    val glassMode: Float
        get() = when (treatment) {
            ClockTreatment.GLASS -> 3f
            ClockTreatment.TRANSLUCENT -> 1f + AtmosphereClockPolicy.sanitizeFrost(frost)
            // Solid digits: the shaders' flat path.
            ClockTreatment.ADAPTIVE -> 0f
        }

    /** The Adaptive face paints each digit a tint of what is behind it. */
    val adaptiveColors: Boolean
        get() = ClockPalette.isAdaptive(requestedColor)

    /**
     * The face texture's top edge — where the renderers place the bitmap.
     *
     * The stored geometry describes the digits, and the bitmap extends above
     * them by [faceContentTop] of its own height, so the texture starts that
     * much higher. Keeping the two apart is what stops a face's animation
     * margin, or the date being dragged somewhere new, from moving the clock.
     */
    val renderTop: Float
        get() = contentTop - faceContentTop.coerceIn(0f, 1f) * renderHeight

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
    fun needsSubjectMask(): Boolean = enabled && (depthEnabled || style.adaptsToSubject)

    companion object {
        /** See [glassMeta]. Far above every treatment's own value. */
        const val FOLLOWS_WALLPAPER = 100f
    }

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }
}
