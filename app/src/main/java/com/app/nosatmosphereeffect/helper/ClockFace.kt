package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.core.graphics.createBitmap
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How a face is lit.
 *
 * Two different pieces of glass, not two settings of one. [GLASS] is the
 * original face: a rim of bevel catching the light around an otherwise clear
 * digit. [TRANSLUCENT] treats the whole digit as a solid piece — the
 * wallpaper bends across the entire stroke and is magnified by the thickness
 * — and is the only one with frost, because frosting a face that is only
 * glass at its edges just fogs the edges.
 */
enum class ClockTreatment {
    GLASS,
    TRANSLUCENT,
    /**
     * Not glass at all: solid digits that stretch around the subject — see
     * [AdaptiveClockFace].
     */
    ADAPTIVE
}

/**
 * The selectable clock faces.
 *
 * Every style is built from a typeface family that ships with Android
 * itself (no bundled font files, no downloadable-fonts dependency), so the
 * same code produces the same result in the F-Droid and Play builds and
 * nothing here needs a licence audit. [familyName] values are the standard
 * aliases declared in /system/etc/fonts.xml on every Android device; if a
 * device happens not to have one, Typeface.create falls back to the default
 * sans-serif rather than failing.
 */
enum class ClockStyle(
    val id: String,
    val label: String,
    val description: String,
    private val familyName: String,
    private val weight: Int,
    /** Extra tracking as a fraction of the text size. */
    val letterSpacingEm: Float,
    /** True when hours and minutes are drawn on separate rows. */
    val stacked: Boolean,
    /**
     * How far the glyph outline is stretched vertically, about its baseline.
     *
     * The clock is sized on screen by its *height* fraction, with width
     * following from the bitmap's aspect — so stretching here does not make
     * the clock occupy more screen, it makes the digits tall and narrow
     * inside the same height budget, which is what reads as a display clock
     * rather than a caption. It composes with the size slider instead of
     * competing with it.
     *
     * Tuned per style: a thin face carries more stretch gracefully than a
     * 900-weight one, where the stems thicken visually as they lengthen.
     */
    val verticalStretch: Float,
    /**
     * How far the glyph outline is condensed horizontally, 1.0 = untouched.
     *
     * Applied through Paint.textScaleX, so it narrows the advance as well as
     * the outline — the layout measures with it set, and the slots shrink to
     * match. That matters: condensing only the drawing would leave the digits
     * rattling around inside slots sized for the wide form.
     *
     * Paired with [verticalStretch] rather than used alone. Tall-and-narrow is
     * what reads as a display clock; tall-and-wide just reads as large.
     */
    val horizontalScale: Float = 1f,
    /** Which of the two glass treatments the shader gives this face. */
    val treatment: ClockTreatment
) {
    /**
     * The original face, and the default: hours and minutes side by side,
     * clear through the middle with the light caught around the bevel of
     * every stroke.
     *
     * Its weight and stretch are the ones it shipped with. What has changed
     * is underneath — its glyphs are distance fields now like everything
     * else, so the outline is rebuilt at whatever size it is drawn rather
     * than scaled up from the pixels it was rasterised at.
     */
    GLASS(
        id = "liquid_glass",
        label = "Glass",
        description = "Glass digits in one row",
        familyName = "sans-serif-black",
        weight = 900,
        letterSpacingEm = -0.03f,
        stacked = false,
        verticalStretch = 1.45f,
        horizontalScale = 0.98f,
        treatment = ClockTreatment.GLASS
    ),

    /** The original face, hours above minutes. */
    GLASS_STACKED(
        id = "liquid_glass_stacked",
        label = "Glass Stacked",
        description = "Glass digits, hours above minutes",
        familyName = "sans-serif-black",
        weight = 900,
        letterSpacingEm = -0.04f,
        stacked = true,
        verticalStretch = 1.48f,
        horizontalScale = 0.98f,
        treatment = ClockTreatment.GLASS
    ),

    /**
     * The whole digit as one piece of glass: the wallpaper bends across the
     * entire stroke and is magnified by its thickness, and frost takes it
     * from clear through etched to milk.
     *
     * The weight is the one part of this that is a compromise. The faces it
     * is modelled on are set in a geometric sans at about semibold, which no
     * Android device ships; Roboto at 600 is the closest thing that is on
     * every device, and bundling a font would mean a licence audit for a
     * decoration. The stretch is what recovers the proportions.
     */
    TRANSLUCENT(
        id = "translucent",
        label = "Translucent",
        description = "One piece of glass, hours and minutes in a row",
        familyName = "sans-serif",
        weight = 600,
        letterSpacingEm = -0.02f,
        stacked = false,
        verticalStretch = 1.50f,
        horizontalScale = 1f,
        treatment = ClockTreatment.TRANSLUCENT
    ),

    /** The same piece of glass, hours above minutes. */
    TRANSLUCENT_STACKED(
        id = "translucent_stacked",
        label = "Translucent Stacked",
        description = "One piece of glass, hours above minutes",
        familyName = "sans-serif",
        weight = 600,
        letterSpacingEm = -0.03f,
        stacked = true,
        verticalStretch = 1.52f,
        horizontalScale = 1f,
        treatment = ClockTreatment.TRANSLUCENT
    ),

    /**
     * Samsung's adaptive lock screen clock: tall rounded digits that each run
     * down to just above the subject. The digits are drawn from skeletons —
     * see [AdaptiveClockGlyphs] — so the typeface here only sets the date.
     */
    ADAPTIVE(
        id = "adaptive",
        label = "Adaptive",
        description = "Digits stretch around the subject",
        familyName = "sans-serif",
        weight = 400,
        letterSpacingEm = 0f,
        stacked = false,
        verticalStretch = 1f,
        horizontalScale = 1f,
        treatment = ClockTreatment.ADAPTIVE
    ),

    /**
     * The Adaptive face stacked: hours over minutes in two columns, with no
     * colon. Each column stretches as one, the minute hanging just under the
     * hour above it.
     */
    ADAPTIVE_STACKED(
        id = "adaptive_stacked",
        label = "Adaptive Stacked",
        description = "Hours over minutes, stretching around the subject",
        familyName = "sans-serif",
        weight = 400,
        letterSpacingEm = 0f,
        stacked = true,
        verticalStretch = 1f,
        horizontalScale = 1f,
        treatment = ClockTreatment.ADAPTIVE
    );

    /** Only the translucent faces are glass all the way through to frost. */
    val usesFrost: Boolean
        get() = treatment == ClockTreatment.TRANSLUCENT

    /** Needs the subject mask to draw at all, not just for depth. */
    val adaptsToSubject: Boolean
        get() = treatment == ClockTreatment.ADAPTIVE

    /** Has a stroke weight the user can set. */
    val hasWeight: Boolean
        get() = treatment == ClockTreatment.ADAPTIVE

    fun typeface(): Typeface {
        return try {
            Typeface.create(
                Typeface.create(familyName, Typeface.NORMAL),
                weight,
                false
            )
        } catch (_: RuntimeException) {
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
    }

    companion object {
        val DEFAULT = GLASS

        fun fromId(id: String?): ClockStyle {
            if (id == null) return DEFAULT
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * The surface a live wallpaper fills, as width/height. Used as the default
 * [ClockFaceRenderer.screenAspect]: the wallpaper is always the display, and
 * only the calibration screen — which draws into a smaller view — has to say
 * otherwise.
 */
private fun displaySize(context: Context): Pair<Int, Int> {
    val metrics = try {
        context.getSystemService(android.view.WindowManager::class.java)
            ?.currentWindowMetrics
            ?.bounds
    } catch (_: RuntimeException) {
        null
    }
    val width = metrics?.width() ?: context.resources.displayMetrics.widthPixels
    val height = metrics?.height() ?: context.resources.displayMetrics.heightPixels
    return width to height
}

private fun displayAspect(context: Context): Float {
    val (width, height) = displaySize(context)
    if (width <= 0 || height <= 0) return 0.46f
    return width.toFloat() / height.toFloat()
}

private fun displayHeightPx(context: Context): Float {
    val height = displaySize(context).second
    return if (height > 0) height.toFloat() else 2400f
}

/**
 * Draws the clock face into a reusable bitmap, and owns both the
 * digit-change animation and the entry animation.
 *
 * Shared deliberately by [ClockTextureProvider] (GLES) and
 * [com.app.nosatmosphereeffect.renderer.vulkan.VulkanClockTextureUploader]
 * (Vulkan): the previous version of this feature had two copies of the
 * drawing code, which is how the two backends ended up disagreeing about
 * geometry. The only thing the two wrappers still do differently is the
 * upload step.
 *
 * Not thread-safe. Each backend owns its own instance and only touches it
 * from its own render thread.
 *
 * ## Why the bitmap has a fixed size
 *
 * Digit slots are laid out using the widest digit's advance rather than the
 * advance of whichever digit is currently showing. That costs a few pixels
 * of padding on narrow digits and buys three things: the clock stops
 * shifting sideways as the time changes, the animation has somewhere stable
 * to slide within, and the bitmap dimensions never change — so the GLES path
 * can texSubImage2D into the existing texture instead of reallocating, and
 * the Vulkan path re-uploads the same extent every time.
 *
 * ## Two animations, one clock
 *
 * [beginEntry] plays when the wallpaper becomes visible: the whole face
 * rises, brightens and settles, staggered left to right. The digit
 * transition plays when a displayed digit changes, staggered right to left
 * so a rollover cascades. They run off the same monotonic clock and are
 * mutually exclusive by construction — [beginEntry] cancels any digit
 * transition in flight, because a clock that is still arriving has no
 * previous digits to slide away.
 */
class ClockFaceRenderer(private val context: Context) {

    var style: ClockStyle = ClockStyle.DEFAULT
        set(value) {
            if (field != value) {
                field = value
                atlas = ClockGlyphAtlas.of(value)
                adaptiveFace.stacked = value.stacked
                invalidateLayout()
            }
        }

    /** Draws the day and date, wherever [datePlacement] puts it. */
    var showDate: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateLayout()
            }
        }

    /**
     * Where the digits sit on screen. Not used to draw them — the shader does
     * the placing — but the date is positioned against this box, so the face
     * has to know it.
     */
    var clockPlacement: ClockPlacement = AtmosphereClockPolicy.DEFAULT_PLACEMENT
        set(value) {
            if (field != value) {
                field = value
                if (showDate) invalidateDateLayout()
            }
        }

    /** Where the date sits on screen, set and stored exactly like the clock. */
    var datePlacement: ClockPlacement = AtmosphereClockPolicy.DEFAULT_DATE_PLACEMENT
        set(value) {
            if (field != value) {
                field = value
                if (showDate) invalidateDateLayout()
            }
        }

    /**
     * Width/height of the surface the clock is drawn on. Only the date needs
     * it: its offset from the digits is a horizontal distance measured in
     * digit-box widths, and converting between the two needs to know how wide
     * a screen-height fraction is.
     *
     * Defaults to the display, which is what a live wallpaper always fills.
     * The calibration screen overrides it with its own preview's aspect so
     * that what it shows is what the wallpaper will draw.
     */
    var screenAspect: Float = displayAspect(context)
        set(value) {
            val safe = if (value.isFinite() && value > 0f) value else field
            if (field != safe) {
                field = safe
                if (showDate) invalidateDateLayout()
            }
        }

    /**
     * Colour the glyphs are drawn in, already resolved (never
     * [ClockPalette.AUTO]). Changing it only needs a redraw, not a relayout.
     */
    var color: Int = ClockPalette.DEFAULT_FALLBACK
        set(value) {
            val opaque = value or (0xFF shl 24)
            if (field != opaque) {
                field = opaque
                adaptiveFace.color = opaque
                invalidate()
            }
        }

    /**
     * Everything particular to the Adaptive face. Held whatever the style, so
     * the scene it collects is already there when the user switches to it.
     */
    private val adaptiveFace = AdaptiveClockFace()

    /**
     * What is behind the clock, for the Adaptive face. Renderers hand this to
     * their [SubjectMaskCoordinator] so it sees the same image and mask they
     * draw with.
     */
    val sceneSource: ClockSceneSource
        get() = adaptiveFace.sceneSource

    /** The Adaptive face's stroke weight, 0 thin .. 1 bold. */
    var weight: Float = AtmosphereClockPolicy.DEFAULT_WEIGHT
        set(value) {
            val safe = AtmosphereClockPolicy.sanitizeWeight(value)
            if (field != safe) {
                field = safe
                adaptiveFace.weight = safe
                if (isAdaptive) invalidate()
            }
        }

    /** The Adaptive face tints each digit by what is behind it. */
    var adaptiveColors: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                adaptiveFace.adaptiveColors = value
                if (isAdaptive) invalidate()
            }
        }

    /**
     * The launcher's page offset. The Adaptive face fits its digits to the
     * part of the image that is actually on screen.
     */
    var scrollOffsetX: Float
        get() = adaptiveFace.scrollOffsetX
        set(value) {
            adaptiveFace.scrollOffsetX = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.5f
        }

    /** See [AdaptiveClockFace.centerCropScene]; the calibration screen sets it. */
    var centerCropScene: Boolean
        get() = adaptiveFace.centerCropScene
        set(value) { adaptiveFace.centerCropScene = value }

    private val isAdaptive: Boolean
        get() = style.treatment == ClockTreatment.ADAPTIVE

    /** Reused between Adaptive frames; sized to the bitmap. */
    private var adaptivePixels: IntArray? = null

    /**
     * How much the renderers should magnify the wallpaper for the frame last
     * rendered: the Adaptive face's arrival zoom, and 1 for every other face.
     * Read alongside the face texture, so the photo and the clock always
     * show the same instant of the animation.
     */
    var wallpaperZoom: Float = 1f
        private set

    var animateDigits: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) transitionStartUptimeMs = NO_TRANSITION
            }
        }

    /**
     * Whether the entry animation plays at all. Shares the user's single
     * "animation" switch with the digit transition — someone who turned
     * animation off wants a clock that simply appears.
     */
    var animateEntry: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) entryStartUptimeMs = NO_TRANSITION
            }
        }

    /** Width of the most recently rendered bitmap, in pixels. */
    var width: Int = 0
        private set

    /** Height of the most recently rendered bitmap, in pixels. */
    var height: Int = 0
        private set

    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1f

    /**
     * null = follow the system setting; true/false = explicit override.
     */
    var hourFormatOverride: Boolean? = null
        set(value) {
            if (field != value) {
                field = value
                invalidateLayout()
            }
        }

    private var systemIs24Hour: Boolean = DateFormat.is24HourFormat(context)

    private val is24Hour: Boolean
        get() = hourFormatOverride ?: systemIs24Hour

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Set per glyph in drawGlyph — alpha is animated, so the colour has
        // to be reapplied each time anyway.
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    // Kept rather than rebuilt per frame: the pattern lookup and the parse
    // behind SimpleDateFormat are not free, and this runs on the render path.
    private var dateFormatter: java.text.SimpleDateFormat? = null
    private var dateFormatterLocale: java.util.Locale? = null

    private val calendar: Calendar = Calendar.getInstance()

    /**
     * The glyphs, as distance fields. Rebuilt when the face changes, because
     * the field has the style's own stretch and weight baked into it.
     */
    private var atlas: ClockGlyphAtlas = ClockGlyphAtlas.of(style)
    private val glyphMatrix = Matrix()
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Overlapping fields take the larger value rather than blending:
        // two distance fields averaged together describe neither shape, and
        // during a digit change two of them share a slot.
        xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
    }

    /** Used to keep the rasterisation in proportion to the clock on screen. */
    private val screenHeightPx: Float = displayHeightPx(context)

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private var layout: FaceLayout? = null
    private var currentRows: List<String> = emptyList()
    private var previousRows: List<String> = emptyList()

    /** True when the last frame could not draw every glyph it wanted. */
    private var incomplete: Boolean = false
    private var incompleteRetries: Int = 0

    private var lastRenderedKey: Long = Long.MIN_VALUE
    private var lastRenderUptimeMs: Long = 0L
    private var transitionStartUptimeMs: Long = NO_TRANSITION
    private var entryStartUptimeMs: Long = NO_TRANSITION

    /**
     * Starts the entry animation. Called when the wallpaper engine becomes
     * visible — screen on, returning from an app, the picker opening a
     * preview — so the clock arrives rather than being already there.
     *
     * [uptimeMs] must come from a monotonic clock. Cancels any digit
     * transition in flight: the face is about to be composed from scratch,
     * so there is nothing for the old digits to slide away from.
     */
    fun beginEntry(uptimeMs: Long) {
        if (!animateEntry) {
            entryStartUptimeMs = NO_TRANSITION
            return
        }
        entryStartUptimeMs = uptimeMs
        transitionStartUptimeMs = NO_TRANSITION
        adaptiveFace.beginEntry(uptimeMs)
        // The displayed time has not necessarily changed, but the pixels
        // have — force the next render rather than letting the key check
        // short-circuit it.
        lastRenderedKey = Long.MIN_VALUE
    }

    /**
     * True when a frame is due — either the displayed time changed or an
     * animation is still in flight. Callers use this both to decide whether
     * to redraw and to decide whether to schedule another frame.
     */
    fun needsRender(nowMillis: Long, uptimeMs: Long): Boolean {
        if (bitmap == null) return true
        if (timeKey(nowMillis) != lastRenderedKey) return true
        return isAnimating(uptimeMs)
    }

    fun isAnimating(uptimeMs: Long): Boolean =
        incomplete ||
            isEntering(uptimeMs) ||
            isChangingDigits(uptimeMs) ||
            // An animation whose time is up still owes one frame: the render
            // that notices it is over and draws the end state. Without it the
            // last frame drawn was whichever landed just before the end — a
            // digit change frozen with the old digit still showing, until the
            // next minute redrew the face.
            transitionStartUptimeMs != NO_TRANSITION ||
            entryStartUptimeMs != NO_TRANSITION ||
            adaptiveNeedsFrame(uptimeMs)

    /**
     * The Adaptive face redraws on its own account when what is behind it
     * changes — a mask arriving, the page scrolling, the box moving — and
     * while its digits ease to their new lengths.
     */
    private fun adaptiveNeedsFrame(uptimeMs: Long): Boolean =
        isAdaptive &&
            layout != null &&
            adaptiveFace.needsFrame(adaptiveScreenBox(), screenAspect, uptimeMs)

    /** The digits' box on screen, which the Adaptive face measures against. */
    private fun adaptiveScreenBox(): ClockBoxRect = ClockBoxPlacement.contentBox(
        placement = clockPlacement,
        contentAspect = AdaptiveClockGlyphs.boxWidth(style.stacked) /
            AdaptiveClockGlyphs.boxHeight(style.stacked),
        screenAspect = screenAspect
    )

    /**
     * True only while the entry animation is running. Separate from
     * [isAnimating] because the backends throttle it differently: an entry
     * animation is a one-off worth spending frames on, a digit transition
     * happens every minute.
     */
    fun isEntering(uptimeMs: Long): Boolean {
        if (!animateEntry || entryStartUptimeMs == NO_TRANSITION) return false
        return uptimeMs - entryStartUptimeMs < entryTotalDurationMs()
    }

    private fun isChangingDigits(uptimeMs: Long): Boolean {
        if (!animateDigits || transitionStartUptimeMs == NO_TRANSITION) return false
        return uptimeMs - transitionStartUptimeMs < TRANSITION_DURATION_MS
    }

    /**
     * Renders the face for [nowMillis]. [uptimeMs] must come from a
     * monotonic clock (SystemClock.uptimeMillis) so that a user or network
     * time change moves the digits without corrupting the animation.
     *
     * [minimumIntervalMs] throttles animation frames; pass 0 to force a
     * render. Returns the bitmap to upload, or null when nothing needs to
     * be uploaded this frame.
     */
    fun render(
        nowMillis: Long,
        uptimeMs: Long,
        minimumIntervalMs: Long = 0L
    ): Bitmap? {
        val key = timeKey(nowMillis)
        val timeChanged = key != lastRenderedKey
        val animating = isAnimating(uptimeMs)
        if (!timeChanged && !animating && bitmap != null) return null
        // The Adaptive face's motion is only smooth at the display's own
        // rate, so it is never throttled; its frames are cheap enough.
        val interval = if (isAdaptive) 0L else minimumIntervalMs
        if (
            !timeChanged &&
            bitmap != null &&
            uptimeMs - lastRenderUptimeMs < interval
        ) {
            return null
        }

        val rows = formatRows(nowMillis)
        if (
            timeChanged &&
            currentRows.isNotEmpty() &&
            rows != currentRows &&
            // A digit change landing mid-entry is absorbed by the entry
            // animation rather than starting a competing slide.
            !isEntering(uptimeMs)
        ) {
            previousRows = currentRows
            if (animateDigits) transitionStartUptimeMs = uptimeMs
        }
        if (currentRows.isEmpty()) previousRows = rows
        currentRows = rows

        val face = ensureLayout(rows, dateText(nowMillis))
        val target = ensureBitmap(face) ?: return null
        val target2d = canvas ?: return null

        target.eraseColor(Color.TRANSPARENT)
        // A glyph whose field has not been built yet cannot be drawn, and the
        // time will not change again for a minute — so without this the face
        // would sit there missing a digit until some unrelated setting forced
        // a redraw, which is exactly what it used to do on the first frame.
        // Leaving the key unstamped makes the next call render again, and
        // reporting it as animating is what asks for that call.
        val drawn = if (isAdaptive) {
            drawAdaptiveFace(target, target2d, face, uptimeMs)
        } else {
            drawFace(target2d, face, uptimeMs)
        }
        wallpaperZoom = if (isAdaptive) adaptiveFace.wallpaperZoom(uptimeMs) else 1f
        if (drawn) {
            incomplete = false
            incompleteRetries = 0
        } else {
            // Bounded: a field that cannot be built at all — no memory for it,
            // no outline for the character — would otherwise have the clock
            // redrawing at frame rate for ever trying to finish a face that
            // never will.
            incompleteRetries++
            incomplete = incompleteRetries <= MAX_INCOMPLETE_RETRIES
        }

        lastRenderedKey = if (incomplete) Long.MIN_VALUE else key
        lastRenderUptimeMs = uptimeMs
        width = target.width
        height = target.height
        return target
    }

    /**
     * Where the digits sit inside the face bitmap, without drawing anything.
     * The bitmap carries margin for the animations and, when the date is on,
     * room for the date wherever it was placed; the calibration screen and
     * the renderers both need the digits' real extent, because that is what
     * the user positions.
     */
    fun measureFace(nowMillis: Long): ClockFaceBox =
        boxOf(ensureLayout(formatRows(nowMillis), dateText(nowMillis)))

    /**
     * The digits' box in the layout as it stands, without building one. The
     * render path calls this every frame to turn the stored placement into
     * the rectangle the shader samples, so it must not allocate or relayout.
     */
    val faceBox: ClockFaceBox
        get() = layout?.let(::boxOf) ?: ClockFaceBox.IDENTITY

    /**
     * The date line's natural width/height ratio in this style's typeface, or
     * null when there is no date. The calibration screen needs it to size the
     * date's box the way it sizes the clock's.
     */
    fun measureDateAspect(nowMillis: Long): Float? {
        val text = dateText(nowMillis) ?: return null
        return ensureLayout(formatRows(nowMillis), text).dateNaturalAspect
    }

    private fun boxOf(face: FaceLayout): ClockFaceBox {
        val width = max(face.bitmapWidth, 1f)
        val height = max(face.bitmapHeight, 1f)
        return ClockFaceBox(
            aspect = width / height,
            left = face.digitsLeft / width,
            top = face.digitsTop / height,
            right = (face.digitsLeft + face.digitsWidth) / width,
            bottom = (face.digitsTop + face.digitsHeight) / height
        )
    }

    /** Re-reads the system 12/24-hour setting; call on a config change. */
    fun refreshFormat() {
        val updated = DateFormat.is24HourFormat(context)
        if (updated != systemIs24Hour) {
            systemIs24Hour = updated
            if (hourFormatOverride == null) invalidateLayout()
        }
    }

    /**
     * Forces the next [render] to redraw and re-upload without animating —
     * for surface/context loss, where the GPU-side copy is gone but the
     * displayed time has not actually changed.
     */
    fun invalidate() {
        adaptiveFace.reset()
        incompleteRetries = 0
        lastRenderedKey = Long.MIN_VALUE
        transitionStartUptimeMs = NO_TRANSITION
        entryStartUptimeMs = NO_TRANSITION
    }

    fun release() {
        // The atlas is shared and outlives this renderer; see ClockGlyphAtlas.
        bitmap?.recycle()
        bitmap = null
        canvas = null
        layout = null
        width = 0
        height = 0
        invalidate()
        currentRows = emptyList()
        previousRows = emptyList()
    }

    // ---------------------------------------------------------------- draw

    /** Returns false when a glyph's field was not ready and was left out. */
    private fun drawFace(target: Canvas, face: FaceLayout, uptimeMs: Long): Boolean {
        val progress = transitionProgress(uptimeMs)
        val entry = entryProgress(uptimeMs)
        var complete = drawDate(target, face, entry)
        if (!drawStackSeparator(target, face, entry)) complete = false
        val rowCount = face.rows.size
        // Slots are staggered across the whole face, not per row, so a
        // stacked clock cascades down as well as across instead of both rows
        // starting together.
        var globalSlot = 0

        for (rowIndex in 0 until rowCount) {
            val row = face.rows[rowIndex]
            val previousRow = previousRows.getOrNull(rowIndex)
            val currentRow = currentRows.getOrNull(rowIndex) ?: continue
            var x = face.digitsLeft + (face.digitsWidth - row.width) / 2f
            val baseline = face.rowBaselines[rowIndex]

            for (slotIndex in row.slots.indices) {
                val slot = row.slots[slotIndex]
                val newChar = currentRow.getOrNull(slotIndex)
                if (newChar == null) {
                    x += slot.advance
                    globalSlot++
                    continue
                }
                val centerX = x + slot.advance / 2f
                val entrySlot = entry?.let { staggeredEntry(it, globalSlot) }
                // Each glyph is confined to its own slot.
                //
                // The field runs past the glyph on every side, and two of them
                // landing on the same texel add up where they overlap — which
                // between two digits is a gap narrower than twice the spread,
                // so the pair would read as joined. Cutting each field at the
                // slot boundary costs nothing: out there the field is well
                // below the level the shader draws at, so the seam is in a
                // part of the texture nothing is drawn from.
                target.save()
                target.clipRect(x, 0f, x + slot.advance, face.bitmapHeight)

                // Only a slot whose character actually changed animates, and
                // only while a transition is running. Held in one nullable
                // local so the non-null branch below smart-casts.
                val outgoing = previousRow
                    ?.getOrNull(slotIndex)
                    ?.takeIf { progress != null && it != newChar }
                val slotProgress = if (outgoing == null || progress == null) {
                    null
                } else {
                    staggered(progress, slotIndex, row.slots.size)
                }

                if (outgoing == null || slotProgress == null) {
                    if (!drawGlyph(
                        target = target,
                        character = newChar,
                        centerX = centerX,
                        baseline = baseline,
                        face = face,
                        alpha = 1f,
                        offsetY = 0f,
                        scale = 1f,
                        entry = entrySlot
                    )
                    ) {
                        complete = false
                    }
                } else {
                    val eased = easeOutCubic(slotProgress)
                    val shift = face.textSize * TRANSITION_TRAVEL_EM
                    // Outgoing digit rises and fades; incoming rises into
                    // place from below. Scale is nudged so the swap reads as
                    // depth rather than a flat slide.
                    if (!drawGlyph(
                            target = target,
                            character = outgoing,
                            centerX = centerX,
                            baseline = baseline,
                            face = face,
                            alpha = 1f - eased,
                            offsetY = -shift * eased,
                            scale = 1f - 0.10f * eased,
                            entry = entrySlot
                        )
                    ) {
                        complete = false
                    }
                    if (!drawGlyph(
                            target = target,
                            character = newChar,
                            centerX = centerX,
                            baseline = baseline,
                            face = face,
                            alpha = eased,
                            offsetY = shift * (1f - eased),
                            scale = 0.90f + 0.10f * eased,
                            entry = entrySlot
                        )
                    ) {
                        complete = false
                    }
                }
                target.restore()
                x += slot.advance
                globalSlot++
            }
        }
        return complete
    }

    /**
     * The Adaptive face: its digits are painted as a distance field straight
     * into the bitmap's pixels (see [AdaptiveClockFace]), then the date goes
     * on top exactly as for every other face.
     */
    private fun drawAdaptiveFace(
        target: Bitmap,
        canvas: Canvas,
        face: FaceLayout,
        uptimeMs: Long
    ): Boolean {
        val box = adaptiveScreenBox()
        adaptiveFace.step(box, screenAspect, uptimeMs, animate = animateEntry)
        // Keeps the entry's clock running for isAnimating; everything the face
        // does on arrival runs on its own curves inside AdaptiveClockFace.
        entryProgress(uptimeMs)
        val change = transitionProgress(uptimeMs)
        if (currentRows.isEmpty()) return true
        val width = target.width
        val height = target.height
        val unitPx = face.digitsWidth / AdaptiveClockGlyphs.boxWidth(style.stacked)
        // The whole clock, date included, drifts the last little way home as
        // it arrives. It comes from the side of the screen's middle — from
        // below, and in from whichever side the middle is — so the drift
        // always reads as the clock settling into its corner of the photo.
        val glide = adaptiveFace.glideRemaining(uptimeMs) *
            AdaptiveClockFace.GLIDE_UNITS * unitPx
        val (glideX, glideY) = if (glide > 0f) glideDirection(box) else 0f to 0f
        val pixels = adaptivePixels?.takeIf { it.size == width * height }
            ?: IntArray(width * height).also { adaptivePixels = it }
        pixels.fill(0)
        adaptiveFace.paint(
            pixels = pixels,
            stride = width,
            rows = height,
            originX = face.digitsLeft + glideX * glide,
            originY = face.digitsTop + glideY * glide,
            unitPx = unitPx,
            time = currentRows,
            outgoing = previousRows.takeIf { change != null },
            change = { slot ->
                val slots = if (style.stacked) 4 else AdaptiveClockGlyphs.SLOT_COUNT
                change?.let { staggered(it, slot, slots) }
            },
            uptimeMs = uptimeMs
        )
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        // The date does not fade or erode in: it arrives with the clock,
        // riding the same glide, and only the shading's colour changes on it.
        return drawDate(
            canvas,
            face,
            null,
            adaptiveFace.dateColor(uptimeMs),
            offsetX = glideX * glide,
            offsetY = glideY * glide
        )
    }

    /**
     * Unit vector, in bitmap pixels' orientation, from the clock's home
     * towards where it arrives from: towards the middle of the screen, but
     * always partly from below.
     */
    private fun glideDirection(box: ClockBoxRect): Pair<Float, Float> {
        val centreX = (box.left + box.right) / 2f
        val centreY = (box.top + box.bottom) / 2f
        // Screen fractions are not square; bring x into the same units as y.
        var dx = (0.5f - centreX) * screenAspect
        var dy = max(0.5f - centreY, 0f)
        // Mostly upward, whatever the placement: a clock low on the screen
        // still rises into place rather than dropping into it.
        dy = max(dy, abs(dx) * 1.4f + 0.05f)
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-5f) return 0f to 1f
        dx /= length
        dy /= length
        return dx to dy
    }

    /**
     * The separator between the two halves of a stacked face.
     *
     * A single row carries its colon inline. Stacked, the same two dots lie
     * side by side in the gap between the rows — a colon turned on its side,
     * which is what a separator between things stacked vertically has to be.
     * It is the face's own colon rotated rather than a pair of drawn circles,
     * so its dots are the size and spacing the typeface gives them (and a
     * rotation leaves a distance field measuring distance).
     */
    private fun drawStackSeparator(target: Canvas, face: FaceLayout, entry: Float?): Boolean {
        val centreY = face.separatorY ?: return true
        val tile = atlas.glyph(':') ?: return false
        val progress = entry?.let { easeOutCubic((staggeredEntry(it, 0) * 1.35f).coerceAtMost(1f)) }
            ?: 1f
        if (progress <= 0.004f) return true
        val scale = face.glyphScale * STACK_SEPARATOR_SCALE
        if (scale <= 0f) return true
        val inkCentreX = (tile.inkLeft + tile.inkRight) / 2f
        val inkCentreY = (tile.inkTop + tile.inkBottom) / 2f
        glyphMatrix.reset()
        glyphMatrix.setScale(scale, scale)
        // A quarter turn about the origin sends (x, y) to (-y, x), so the
        // translation that follows puts the ink's centre where it belongs.
        glyphMatrix.postRotate(90f)
        glyphMatrix.postTranslate(
            face.digitsLeft + face.digitsWidth / 2f + inkCentreY * scale,
            centreY - inkCentreX * scale
        )
        tilePaint.color = color
        tilePaint.alpha = erosionAlpha(progress)
        target.drawBitmap(tile.bitmap, glyphMatrix, tilePaint)
        return true
    }

    /**
     * The day and date line, drawn to fill the box the user placed for it.
     *
     * Its field is built at its own size, so the glass reads the same on the
     * date as on the digits however much smaller it is — which is what the
     * old fixed-width bevel could not do, and why the date used to come out
     * in patches beside a clock that looked right.
     */
    private fun drawDate(
        target: Canvas,
        face: FaceLayout,
        entry: Float?,
        dateColor: Int = color,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ): Boolean {
        val tile = face.dateTile ?: return true
        // Arrives with the first glyph rather than on its own schedule, so the
        // face reads as one thing coming into place.
        val progress = entry?.let { easeOutCubic((staggeredEntry(it, 0) * 1.35f).coerceAtMost(1f)) }
            ?: 1f
        if (progress <= 0.004f) return true
        val spread = ClockGlyphAtlas.SPREAD_EM * ClockGlyphAtlas.RUN_EM
        val inkHeight = max(tile.inkBottom - tile.inkTop, 1f)
        val scaleX = face.dateWidth / max(tile.advance, 1f)
        val scaleY = face.dateHeight / inkHeight
        glyphMatrix.reset()
        glyphMatrix.setScale(scaleX, scaleY)
        glyphMatrix.postTranslate(
            face.dateLeft - spread * scaleX + offsetX,
            face.dateTop - tile.inkTop * scaleY + offsetY
        )
        tilePaint.color = dateColor
        tilePaint.alpha = erosionAlpha(progress)
        target.drawBitmap(tile.bitmap, glyphMatrix, tilePaint)
        return true
    }

    /**
     * [entry] is this slot's own entry progress, 0..1, or null when no entry
     * animation is running. It composes multiplicatively with whatever the
     * digit transition is doing, so a minute rolling over mid-entry degrades
     * gracefully instead of fighting.
     */
    private fun drawGlyph(
        target: Canvas,
        character: Char,
        centerX: Float,
        baseline: Float,
        face: FaceLayout,
        alpha: Float,
        offsetY: Float,
        scale: Float,
        entry: Float?
    ): Boolean {
        // Alpha leads the motion slightly: a glyph that is still travelling
        // but already solid reads as arriving, where one that fades in on the
        // same curve as it moves reads as sluggish.
        val entryAlpha = entry?.let { easeOutCubic((it * 1.35f).coerceAtMost(1f)) } ?: 1f
        val clamped = (alpha * entryAlpha).coerceIn(0f, 1f)
        if (clamped <= 0.004f) return true
        val tile = atlas.glyph(character) ?: return false

        val entryRise = entry?.let {
            face.textSize * ENTRY_RISE_EM * (1f - easeOutQuint(it))
        } ?: 0f
        // A small overshoot on the way in — the settle is what makes it feel
        // deliberate rather than merely fast.
        val entryScale = entry?.let { ENTRY_SCALE_FROM + (1f - ENTRY_SCALE_FROM) * easeOutBack(it) } ?: 1f

        val glyphScale = face.glyphScale * scale * entryScale
        val spread = ClockGlyphAtlas.SPREAD_EM * ClockGlyphAtlas.CANONICAL_EM
        // A typeface sets its colon low, against the baseline, because it
        // normally separates words of lowercase. Between two digits it wants
        // to be in the middle of them, so it is moved there — the only glyph
        // that is placed by its ink rather than by its baseline.
        val centring = if (character == ':') {
            val tileInkCentre = (tile.inkTop + tile.inkBottom) / 2f - tile.baseline
            val digitsInkCentre = face.inkHeight / 2f - face.inkAboveBaseline
            digitsInkCentre - tileInkCentre * glyphScale
        } else {
            0f
        }
        // The advance box is centred on the slot and the tile's baseline lands
        // on the row's, so the glyph scales about the point it sits on rather
        // than drifting as it grows.
        glyphMatrix.reset()
        glyphMatrix.setScale(glyphScale, glyphScale)
        glyphMatrix.postTranslate(
            centerX - (tile.advance / 2f + spread) * glyphScale,
            baseline + offsetY + entryRise + centring - tile.baseline * glyphScale
        )
        tilePaint.color = color
        tilePaint.alpha = erosionAlpha(clamped)
        target.drawBitmap(tile.bitmap, glyphMatrix, tilePaint)
        return true
    }

    /**
     * Turns an animation's 0..1 into paint alpha.
     *
     * The texture holds a distance field, not coverage, so painting a tile at
     * half alpha does not draw half a glyph — it halves the field, and the
     * shader rebuilds the silhouette where the field crosses its midpoint. A
     * glyph on its way out therefore erodes inwards from its edges instead of
     * dissolving evenly, which is both what glass would do and the reason the
     * range starts at half: below that there is no glyph left to erode.
     */
    private fun erosionAlpha(progress: Float): Int {
        val eased = progress.coerceIn(0f, 1f)
        return ((0.5f + 0.5f * eased) * 255f).toInt().coerceIn(0, 255)
    }

    // -------------------------------------------------------------- layout

    private fun invalidateLayout() {
        layout = null
        bitmap?.recycle()
        bitmap = null
        canvas = null
        width = 0
        height = 0
        currentRows = emptyList()
        previousRows = emptyList()
        invalidate()
    }

    /**
     * Drops the layout but keeps the bitmap, for a change that only moves the
     * date. The bitmap is reused whenever the new layout happens to want the
     * same size, which — because the date's box is quantised — is most frames
     * of a drag.
     */
    private fun invalidateDateLayout() {
        layout = null
        invalidate()
    }

    /** The date's box relative to the digits', as the layout will draw it. */
    private fun dateBoxFor(contentAspect: Float, dateAspect: Float): ClockDateLayout =
        ClockBoxPlacement.relativeDateBox(
            clock = clockPlacement,
            date = datePlacement,
            contentAspect = contentAspect,
            dateAspect = dateAspect,
            screenAspect = screenAspect
        ).quantized()

    private fun ensureLayout(rows: List<String>, dateText: String?): FaceLayout {
        val existing = layout
        if (existing != null && existing.matches(rows, dateText)) {
            // Same shape; the date may still have been dragged since.
            if (
                dateText == null ||
                existing.dateBox == dateBoxFor(existing.contentAspect, existing.dateNaturalAspect)
            ) {
                return existing
            }
        }
        var built = buildLayout(rows, dateText, TEXT_SIZE_PX)
        // Two reasons to rasterise smaller than the nominal size, both about
        // a bitmap that is redrawn and re-uploaded whenever the face animates.
        //
        // The first is oversampling: the nominal size is fixed, so a small
        // clock was being drawn at three times the pixels it is displayed at.
        // The second is the date, which can be dragged far from the digits and
        // makes the bitmap that has to span both of them enormous — and that
        // is worst for exactly the small clock the first rule already shrinks.
        val displayedHeight = clockPlacement.height * screenHeightPx
        val nominalHeight = max(built.digitsHeight, 1f)
        val area = built.bitmapWidth * built.bitmapHeight
        val wanted = minOf(
            displayedHeight * MAX_OVERSAMPLE / nominalHeight,
            if (area > 0f) sqrt(MAX_FACE_PIXELS / area) else 1f
        )
        // The budget is allowed to take the face down to where the digits are
        // drawn at about the size they are displayed, and no further: a clock
        // blurred to fit the date's bitmap is a worse answer than a bitmap
        // over budget, and the date can be dragged back if it matters.
        val floor = min(1f, displayedHeight * MIN_SAMPLE / nominalHeight)
        val scale = min(1f, max(wanted, floor))
        if (scale < 0.99f) {
            built = buildLayout(
                rows = rows,
                dateText = dateText,
                textSize = max(TEXT_SIZE_PX * scale, MIN_TEXT_SIZE_PX)
            )
        }
        layout = built
        return built
    }

    private fun buildLayout(
        rows: List<String>,
        dateText: String?,
        textSize: Float
    ): FaceLayout {
        textPaint.typeface = style.typeface()
        textPaint.textSize = textSize
        textPaint.letterSpacing = style.letterSpacingEm
        // Set before the advances are measured, so the slots below are sized
        // for the condensed form rather than the wide one.
        textPaint.textScaleX = style.horizontalScale

        // Slot width is the widest digit, so the layout never reflows as the
        // time changes.
        var digitAdvance = 0f
        for (digit in '0'..'9') {
            digitAdvance = max(digitAdvance, textPaint.measureText(digit.toString()))
        }
        val separatorAdvance = textPaint.measureText(":")

        val stretch = style.verticalStretch
        // The Adaptive face draws its own digits from skeletons, in units of a
        // digit's width; its box is the row at full stretch.
        val adaptive = isAdaptive
        val unit = textSize * ADAPTIVE_UNIT_PER_EM
        val rowLayouts = rows.map { rowText ->
            val slots = rowText.mapIndexed { index, character ->
                Slot(
                    advance = when {
                        adaptive -> AdaptiveClockGlyphs.slotWidth(index) * unit
                        character == ':' -> separatorAdvance
                        else -> digitAdvance
                    }
                )
            }
            RowLayout(
                slots = slots,
                width = if (adaptive) {
                    AdaptiveClockGlyphs.boxWidth(style.stacked) * unit
                } else {
                    slots.sumOf { it.advance.toDouble() }.toFloat()
                }
            )
        }

        val digitsWidth = rowLayouts.maxOfOrNull { it.width } ?: digitAdvance

        // The digits' ink box. This is what the user drags, so it is measured
        // from the glyphs themselves rather than from the font's line box:
        // a line box includes room for accents no digit has, which made the
        // calibration frame sit visibly loose around the clock.
        val ink = android.graphics.Rect()
        textPaint.getTextBounds(DIGITS_SAMPLE, 0, DIGITS_SAMPLE.length, ink)
        val baselineFromInkTop = if (adaptive) 0f else -ink.top * stretch
        val inkHeight = if (adaptive) {
            AdaptiveClockGlyphs.boxHeight(style.stacked) * unit
        } else {
            (ink.bottom - ink.top) * stretch
        }
        // Rows are pitched on their ink rather than on the font's line box.
        // A line box carries room for ascenders and descenders no digit has,
        // and at this stretch that was two thirds of an em of empty space
        // between the two halves of the time — the stack read as two numbers
        // that happened to be near each other. The gap left is deliberate: it
        // is where the separator goes.
        val rowGap = if (rows.size > 1) textSize * STACK_GAP_EM else 0f
        val rowPitch = inkHeight + rowGap
        // The Adaptive box already holds both rows of its stack.
        val digitsHeight = if (adaptive) inkHeight else rowPitch * (rows.size - 1) + inkHeight
        val contentAspect = (digitsWidth / max(digitsHeight, 1f)).coerceIn(0.02f, 50f)

        // The date fills its own box, in digits-local coordinates for now:
        // (0, 0) is the digits' top-left corner.
        var dateBox: ClockDateLayout? = null
        var dateNaturalAspect = 1f
        var dateTile: ClockGlyphAtlas.Tile? = null
        var dateLeft = 0f
        var dateTop = 0f
        var dateRight = 0f
        var dateBottom = 0f
        if (dateText != null) {
            val tile = atlas.run(dateText)
            if (tile != null) {
                dateNaturalAspect =
                    (tile.advance / max(tile.inkBottom - tile.inkTop, 1f)).coerceIn(0.2f, 40f)
                val box = dateBoxFor(contentAspect, dateNaturalAspect)
                dateBox = box
                val boxWidth = box.width * digitsWidth
                val boxHeight = box.height * digitsHeight
                if (boxWidth >= MIN_DATE_PX && boxHeight >= MIN_DATE_PX) {
                    dateTile = tile
                    dateLeft = box.offsetX * digitsWidth
                    dateTop = box.offsetY * digitsHeight
                    dateRight = dateLeft + boxWidth
                    dateBottom = dateTop + boxHeight
                }
            }
        }
        val drawsDate = dateTile != null

        // Margin leaves room for whichever animation travels furthest, plus
        // the bloomed shadow and the scale overshoot, so a glyph mid-flight
        // is never clipped by the texture edge. Split per axis because the
        // vertical budget is much larger than the horizontal one and a shared
        // value would waste bitmap width on every upload.
        val travelEm = max(TRANSITION_TRAVEL_EM, ENTRY_RISE_EM)
        // The field runs a spread past the glyph on every side, and clipping
        // it would put a hard edge where the glass should be fading out.
        val fieldMargin = if (adaptive) {
            // Its field runs past the ink, and the whole face drifts into place
            // as it arrives.
            unit * (AdaptiveClockFace.SPREAD_UNITS + AdaptiveClockFace.GLIDE_UNITS) + 2f
        } else {
            textSize * ClockGlyphAtlas.SPREAD_EM
        }
        val marginY = if (adaptive) fieldMargin else textSize * travelEm + fieldMargin * stretch
        val marginX = if (adaptive) fieldMargin else fieldMargin + textSize * OVERSHOOT_MARGIN_EM
        val dateMargin = if (drawsDate) {
            max((dateBottom - dateTop) * DATE_MARGIN_FRACTION, 2f) +
                // The Adaptive face carries its date along as it drifts in.
                if (adaptive) unit * AdaptiveClockFace.GLIDE_UNITS else 0f
        } else {
            0f
        }

        // Horizontally the bitmap stays symmetric about the digits whatever
        // the date does: every renderer places the texture by the digits'
        // centre line, so a lop-sided bitmap would slide the clock sideways.
        val leftExtent = if (drawsDate) max(marginX, dateMargin - dateLeft) else marginX
        val rightExtent = if (drawsDate) {
            max(marginX, dateRight + dateMargin - digitsWidth)
        } else {
            marginX
        }
        val sideExtent = max(leftExtent, rightExtent)
        val topExtent = if (drawsDate) max(marginY, dateMargin - dateTop) else marginY
        // The Adaptive face's overshoot carries a long digit past the box before
        // it settles, so the bitmap runs on below it far enough to hold that.
        val belowDigits = if (adaptive) {
            marginY + unit * AdaptiveClockFace.overshootUnits(style.stacked)
        } else {
            marginY
        }
        val bottomExtent = if (drawsDate) {
            max(belowDigits, dateBottom + dateMargin - digitsHeight)
        } else {
            belowDigits
        }

        val digitsLeft = sideExtent
        val digitsTop = topExtent
        val baselines = FloatArray(rows.size)
        for (index in rows.indices) {
            baselines[index] = digitsTop + baselineFromInkTop + rowPitch * index
        }

        val face = FaceLayout(
            rows = rowLayouts,
            rowBaselines = baselines,
            rowTexts = rows,
            digitsLeft = digitsLeft,
            digitsTop = digitsTop,
            digitsWidth = digitsWidth,
            digitsHeight = digitsHeight,
            bitmapWidth = digitsWidth + sideExtent * 2f,
            bitmapHeight = digitsHeight + topExtent + bottomExtent,
            // Centred in the gap between the rows, and only there: a single
            // row carries its separator inline.
            // The stacked Adaptive face has no separator at all.
            separatorY = if (rows.size > 1 && !adaptive) {
                digitsTop + inkHeight + rowGap / 2f
            } else {
                null
            },
            inkAboveBaseline = baselineFromInkTop,
            inkHeight = inkHeight,
            contentAspect = contentAspect,
            dateText = dateText,
            dateBox = dateBox,
            dateNaturalAspect = dateNaturalAspect,
            dateTile = dateTile,
            dateLeft = digitsLeft + dateLeft,
            dateTop = digitsTop + dateTop,
            dateWidth = dateRight - dateLeft,
            dateHeight = dateBottom - dateTop,
            textSize = textSize,
            glyphScale = textSize / ClockGlyphAtlas.CANONICAL_EM
        )
        layout = face
        return face
    }

    private fun ensureBitmap(face: FaceLayout): Bitmap? {
        val targetWidth = face.bitmapWidth.roundToInt().coerceAtLeast(1)
        val targetHeight = face.bitmapHeight.roundToInt().coerceAtLeast(1)
        val existing = bitmap
        if (
            existing != null &&
            !existing.isRecycled &&
            existing.width == targetWidth &&
            existing.height == targetHeight
        ) {
            return existing
        }
        existing?.recycle()
        return try {
            val created = createBitmap(targetWidth, targetHeight)
            bitmap = created
            canvas = Canvas(created)
            created
        } catch (_: OutOfMemoryError) {
            bitmap = null
            canvas = null
            null
        }
    }

    // --------------------------------------------------------------- time

    private fun timeKey(nowMillis: Long): Long {
        // The clock never shows seconds, so a minute is the finest step.
        val divisor = 60_000L
        // Local-offset aware so a timezone change re-renders even when the
        // UTC minute has not rolled over.
        calendar.timeInMillis = nowMillis
        val offset = calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)
        return (nowMillis + offset) / divisor
    }

    private fun formatRows(nowMillis: Long): List<String> {
        calendar.timeInMillis = nowMillis
        val hour24 = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        // Always two digits, in both formats. A bare "1:30" is a digit
        // narrower than "12:30", and because the face is centred on screen
        // that missing digit pulls the whole clock off centre — the colon
        // lands right of where it sat a minute ago. Padding costs one leading
        // zero and buys a clock that never moves. Stacked faces needed this
        // anyway, since their rows are centred on each other.
        val hourText = if (is24Hour) {
            twoDigits(hour24)
        } else {
            twoDigits(if (hour24 % 12 == 0) 12 else hour24 % 12)
        }
        val minuteText = twoDigits(minute)

        return if (style.stacked) {
            listOf(hourText, minuteText)
        } else {
            listOf("$hourText:$minuteText")
        }
    }

    /** "Fri, 25 Oct" in the device's locale, or null when the date is off. */
    private fun dateText(nowMillis: Long): String? {
        if (!showDate) return null
        val locale = java.util.Locale.getDefault()
        val formatter = dateFormatter?.takeIf { dateFormatterLocale == locale } ?: try {
            java.text.SimpleDateFormat(
                DateFormat.getBestDateTimePattern(locale, DATE_SKELETON),
                locale
            ).also {
                dateFormatter = it
                dateFormatterLocale = locale
            }
        } catch (_: RuntimeException) {
            return null
        }
        return try {
            formatter.format(java.util.Date(nowMillis))
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun twoDigits(value: Int): String {
        val safe = abs(value) % 100
        return if (safe < 10) "0$safe" else safe.toString()
    }

    // ---------------------------------------------------------- animation

    private fun transitionProgress(uptimeMs: Long): Float? {
        if (!animateDigits || transitionStartUptimeMs == NO_TRANSITION) return null
        val elapsed = uptimeMs - transitionStartUptimeMs
        if (elapsed < 0L) {
            // Monotonic clock went backwards (should not happen, but a
            // paused-then-resumed engine can produce odd deltas): finish now
            // rather than animating for the length of a long.
            transitionStartUptimeMs = NO_TRANSITION
            return null
        }
        if (elapsed >= TRANSITION_DURATION_MS) {
            transitionStartUptimeMs = NO_TRANSITION
            return null
        }
        return elapsed.toFloat() / TRANSITION_DURATION_MS.toFloat()
    }

    /**
     * Whole-face entry progress, 0..1 across the staggered total. Individual
     * slots take their own slice of it in [staggeredEntry].
     */
    private fun entryProgress(uptimeMs: Long): Float? {
        if (!animateEntry || entryStartUptimeMs == NO_TRANSITION) return null
        val total = entryTotalDurationMs()
        val elapsed = uptimeMs - entryStartUptimeMs
        if (elapsed < 0L || elapsed >= total) {
            entryStartUptimeMs = NO_TRANSITION
            return null
        }
        return elapsed.toFloat() / total.toFloat()
    }

    private fun entryTotalDurationMs(): Long {
        // The Adaptive face arrives as one piece, the way the phone's does.
        if (isAdaptive) return AdaptiveClockFace.MOTION_MS
        val slots = layout?.rows?.sumOf { it.slots.size } ?: DEFAULT_SLOT_COUNT
        return ENTRY_DURATION_MS +
            ENTRY_STAGGER_MS * (slots - 1).coerceAtLeast(0)
    }

    /**
     * Leftmost slot leads, each slot to its right starting slightly later —
     * the opposite direction to [staggered], deliberately. A clock arriving
     * reads left to right like text; a clock rolling over cascades right to
     * left from the digit that actually changed.
     */
    private fun staggeredEntry(progress: Float, slotIndex: Int): Float {
        val total = entryTotalDurationMs().toFloat()
        if (total <= 0f) return 1f
        val delay = (ENTRY_STAGGER_MS * slotIndex).toFloat() / total
        val span = ENTRY_DURATION_MS.toFloat() / total
        if (span <= 0f) return 1f
        return ((progress - delay) / span).coerceIn(0f, 1f)
    }

    /**
     * Rightmost slot leads, each slot to its left starting slightly later,
     * so a rollover like 09:59 -> 10:00 cascades instead of flipping as one
     * block.
     */
    private fun staggered(progress: Float, slotIndex: Int, slotCount: Int): Float {
        val fromRight = (slotCount - 1 - slotIndex).coerceAtLeast(0)
        val delay = (fromRight * STAGGER_FRACTION).coerceIn(0f, 0.6f)
        val span = 1f - delay
        if (span <= 0f) return 1f
        return ((progress - delay) / span).coerceIn(0f, 1f)
    }

    private fun easeOutCubic(value: Float): Float {
        return 1f - (1f - value.coerceIn(0f, 1f)).pow(3)
    }

    private fun easeOutQuint(value: Float): Float {
        return 1f - (1f - value.coerceIn(0f, 1f)).pow(5)
    }

    /**
     * Overshoots slightly past 1 and settles back. [BACK_OVERSHOOT] is kept
     * small on purpose — the layout reserves padding for the overshoot, and
     * a larger one would cost bitmap area on every upload for a flourish
     * nobody asked for.
     */
    private fun easeOutBack(value: Float): Float {
        val t = value.coerceIn(0f, 1f) - 1f
        return 1f + (BACK_OVERSHOOT + 1f) * t.pow(3) + BACK_OVERSHOOT * t.pow(2)
    }

    private data class Slot(val advance: Float)

    private data class RowLayout(val slots: List<Slot>, val width: Float)

    private class FaceLayout(
        val rows: List<RowLayout>,
        /** The text baseline of each row, absolute in the bitmap. */
        val rowBaselines: FloatArray,
        val rowTexts: List<String>,
        /** The digits' ink box inside the bitmap, in pixels. */
        val digitsLeft: Float,
        val digitsTop: Float,
        val digitsWidth: Float,
        val digitsHeight: Float,
        val bitmapWidth: Float,
        val bitmapHeight: Float,
        /** Where the stacked separator's centre goes; null on a single row. */
        val separatorY: Float?,
        /** A row's ink above its baseline, and its full height, in pixels. */
        val inkAboveBaseline: Float,
        val inkHeight: Float,
        val contentAspect: Float,
        val dateText: String?,
        /** The date's box this layout was built for; a new one means a new layout. */
        val dateBox: ClockDateLayout?,
        val dateNaturalAspect: Float,
        /** Null when the date is off, or its box is too small to draw into. */
        val dateTile: ClockGlyphAtlas.Tile?,
        /** The date's box inside the bitmap, in pixels. */
        val dateLeft: Float,
        val dateTop: Float,
        val dateWidth: Float,
        val dateHeight: Float,
        val textSize: Float,
        /**
         * Scales a canonical-em glyph tile to this layout. The style's
         * vertical stretch is baked into the tile itself — stretching one axis
         * of a distance field stops it measuring distance — so this is the
         * only scaling left to do, and it is even.
         */
        val glyphScale: Float
    ) {
        /**
         * A layout is reusable while the *shape* is unchanged — same number
         * of rows and same slot pattern. "09:41" and "10:00" share a layout;
         * "9:41" and "10:41" do not, because the 12-hour hour field grows a
         * digit at ten o'clock.
         *
         * The date's placement is checked separately by the caller, which has
         * to convert it before it can be compared.
         */
        fun matches(candidate: List<String>, candidateDate: String?): Boolean {
            // The date's text decides its natural width, and so the layout's.
            if (dateText != candidateDate) return false
            if (candidate.size != rowTexts.size) return false
            for (index in candidate.indices) {
                val existing = rowTexts[index]
                val other = candidate[index]
                if (existing.length != other.length) return false
                for (position in existing.indices) {
                    if ((existing[position] == ':') != (other[position] == ':')) return false
                }
            }
            return true
        }
    }

    private companion object {
        /**
         * Nominal em size the face is rasterised at. The shader scales the
         * result to whatever size the user picked, so this only decides
         * sharpness, not how big the clock looks.
         *
         * Dropped 320 -> 280 alongside the vertical stretch, which is a
         * straight win rather than a compromise: the stretch multiplies the
         * glyph's height by 1.18-1.34, so 280px of em height rasterises
         * MORE vertical detail than the old 320 did while costing less
         * bitmap area. A Modern face measures ~946x888 here against
         * ~1302x778 before — 17% fewer pixels to re-upload on every
         * animation frame, which is the budget the 640 -> 320 change was
         * protecting in the first place.
         */
        const val TEXT_SIZE_PX = 280f
        /**
         * The gap between the two rows of a stacked face, as a fraction of
         * the em. Sized for the separator that sits in it.
         */
        const val STACK_GAP_EM = 0.24f
        /** How many frames a face may fail to finish before it is accepted. */
        const val MAX_INCOMPLETE_RETRIES = 4

        /** The separator's size relative to a digit's. */
        const val STACK_SEPARATOR_SCALE = 0.62f
        /**
         * Smallest the whole face may be rasterised at, whatever the date's
         * placement asks for. Below this the digits would be visibly soft.
         */
        const val MIN_TEXT_SIZE_PX = 48f
        /**
         * Ceiling on the face bitmap's area. It is re-uploaded on every
         * animation frame, so this is an upload budget as much as a memory
         * one; only a date placed far from the digits ever reaches it.
         */
        const val MAX_FACE_PIXELS = 2_000_000f
        /**
         * How many face pixels per displayed pixel is worth drawing. Above
         * this the extra detail cannot be seen, and the shader is downscaling
         * it away on every frame.
         */
        const val MAX_OVERSAMPLE = 1.6f
        /**
         * The fewest face pixels per displayed pixel the budget may force.
         * Slightly under 1:1 is barely perceptible; below it the digits are.
         */
        const val MIN_SAMPLE = 0.9f
        /** Transparent margin around the date, as a fraction of its height. */
        const val DATE_MARGIN_FRACTION = 0.08f
        /** Below this the date's box is too small to draw into. */
        const val MIN_DATE_PX = 4f
        const val DATE_TRACKING_EM = 0.02f
        /** Day, date and month, ordered and punctuated by the device's locale. */
        const val DATE_SKELETON = "EEEdMMM"

        const val TRANSITION_DURATION_MS = 520L
        const val TRANSITION_TRAVEL_EM = 0.42f
        const val STAGGER_FRACTION = 0.13f

        /** Per-glyph entry duration, before the stagger is added. */
        const val ENTRY_DURATION_MS = 620L
        const val ENTRY_STAGGER_MS = 70L
        const val ENTRY_RISE_EM = 0.34f
        const val ENTRY_SCALE_FROM = 0.88f
        const val BACK_OVERSHOOT = 0.9f
        /** Covers the easeOutBack overshoot plus antialiasing slack. */
        const val OVERSHOOT_MARGIN_EM = 0.06f
        /** Used only to size the entry before a layout exists ("00:00"). */
        const val DEFAULT_SLOT_COUNT = 5

        const val NO_TRANSITION = Long.MIN_VALUE

        /**
         * The Adaptive face's digit width per em of the nominal text size:
         * 0.28 puts a digit at about 78px at full size. The distance field
         * magnifies cleanly to any clock on screen, and the face is painted
         * per pixel on the render thread, so smaller is cheaper.
         */
        const val ADAPTIVE_UNIT_PER_EM = 0.28f
        const val DIGITS_SAMPLE = "0123456789"
    }
}

/**
 * The digits' extent inside a face bitmap, as fractions of its width and
 * height, plus the bitmap's own aspect ratio.
 *
 * The bitmap is larger than this: it carries margin for the animations and,
 * when the date is shown, whatever room the date needs wherever the user put
 * it. Only the digits are described here, because the digits are what the
 * user places — everything else follows from them.
 */
data class ClockFaceBox(
    val aspect: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val widthFraction: Float get() = (right - left).coerceIn(0.01f, 1f)
    val heightFraction: Float get() = (bottom - top).coerceIn(0.01f, 1f)

    /**
     * The digits' own width/height ratio in pixels — what the stored
     * placement is relative to, and unaffected by the date or by the
     * animation margin.
     */
    val contentAspect: Float
        get() = (aspect * widthFraction / heightFraction).coerceIn(0.02f, 50f)

    companion object {
        /** A face that is all digits: what a renderer with no layout reports. */
        val IDENTITY = ClockFaceBox(aspect = 1f, left = 0f, top = 0f, right = 1f, bottom = 1f)
    }
}

/**
 * Where the date sits, in units of the digits' box: (0, 0) is the digits'
 * top-left corner and (1, 1) the bottom-right, so 1 on [width] is exactly as
 * wide as the digits are.
 *
 * ## Why the date is stored relative to the digits
 *
 * The date is drawn into the same bitmap as the digits — one texture, one
 * rectangle, one sampler, on every effect and both backends. Its position
 * therefore has to be expressed against the digits rather than against the
 * screen, and [ClockBoxPlacement.relativeDateBox] is the one place that
 * converts the user's screen placement into this.
 */
data class ClockDateLayout(
    val offsetX: Float,
    val offsetY: Float,
    val width: Float,
    val height: Float
) {
    /**
     * Rounded to a fixed grid. Each distinct value is a bitmap of a different
     * size, so a drag that produced a new one on every frame would reallocate
     * (and re-upload) several megabytes 60 times a second. The grid is fine
     * enough to be invisible and coarse enough that most drag frames reuse
     * the bitmap they already have.
     */
    fun quantized(): ClockDateLayout = ClockDateLayout(
        offsetX = snap(offsetX),
        offsetY = snap(offsetY),
        width = snap(width),
        height = snap(height)
    )

    private fun snap(value: Float): Float =
        (value * QUANTIZE).roundToInt() / QUANTIZE

    private companion object {
        const val QUANTIZE = 384f
    }
}
