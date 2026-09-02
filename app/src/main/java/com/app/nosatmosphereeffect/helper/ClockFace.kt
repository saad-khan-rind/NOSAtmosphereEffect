package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.core.graphics.createBitmap
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

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
    /** Alpha applied to the ":" separator, 0..1. */
    val separatorAlpha: Float
) {
    MODERN(
        id = "modern",
        label = "Modern",
        description = "Light and airy, widely spaced",
        familyName = "sans-serif-thin",
        weight = 200,
        letterSpacingEm = 0.06f,
        stacked = false,
        separatorAlpha = 0.55f
    ),
    DISPLAY(
        id = "display",
        label = "Display",
        description = "Heavy, tightly set",
        familyName = "sans-serif",
        weight = 900,
        letterSpacingEm = -0.02f,
        stacked = false,
        separatorAlpha = 0.8f
    ),
    SERIF(
        id = "serif",
        label = "Serif",
        description = "Classic, editorial",
        familyName = "serif",
        weight = 400,
        letterSpacingEm = 0.02f,
        stacked = false,
        separatorAlpha = 0.7f
    ),
    MONO(
        id = "mono",
        label = "Mono",
        description = "Even, terminal-like",
        familyName = "monospace",
        weight = 400,
        letterSpacingEm = 0.04f,
        stacked = false,
        separatorAlpha = 0.6f
    ),
    STACKED(
        id = "stacked",
        label = "Stacked",
        description = "Hours above minutes",
        familyName = "sans-serif-condensed",
        weight = 700,
        letterSpacingEm = 0f,
        stacked = true,
        separatorAlpha = 0f
    );

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
        val DEFAULT = MODERN

        fun fromId(id: String?): ClockStyle {
            if (id == null) return DEFAULT
            return entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * Draws the clock face into a reusable bitmap, and owns the digit-change
 * animation.
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
 */
class ClockFaceRenderer(private val context: Context) {

    var style: ClockStyle = ClockStyle.DEFAULT
        set(value) {
            if (field != value) {
                field = value
                invalidateLayout()
            }
        }

    var showSeconds: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidateLayout()
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
                invalidate()
            }
        }

    var animateDigits: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) transitionStartUptimeMs = NO_TRANSITION
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

    private var is24Hour: Boolean = DateFormat.is24HourFormat(context)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Set per glyph in drawGlyph — alpha is animated, so the colour has
        // to be reapplied each time anyway.
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    private val calendar: Calendar = Calendar.getInstance()

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null

    private var layout: FaceLayout? = null
    private var currentRows: List<String> = emptyList()
    private var previousRows: List<String> = emptyList()

    private var lastRenderedKey: Long = Long.MIN_VALUE
    private var lastRenderUptimeMs: Long = 0L
    private var transitionStartUptimeMs: Long = NO_TRANSITION

    /**
     * True when a frame is due — either the displayed time changed or a
     * digit transition is still in flight. Callers use this both to decide
     * whether to redraw and to decide whether to schedule another frame.
     */
    fun needsRender(nowMillis: Long, uptimeMs: Long): Boolean {
        if (bitmap == null) return true
        if (timeKey(nowMillis) != lastRenderedKey) return true
        return isAnimating(uptimeMs)
    }

    fun isAnimating(uptimeMs: Long): Boolean {
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
        if (
            !timeChanged &&
            bitmap != null &&
            uptimeMs - lastRenderUptimeMs < minimumIntervalMs
        ) {
            return null
        }

        val rows = formatRows(nowMillis)
        if (timeChanged && currentRows.isNotEmpty() && rows != currentRows) {
            previousRows = currentRows
            if (animateDigits) transitionStartUptimeMs = uptimeMs
        }
        if (currentRows.isEmpty()) previousRows = rows
        currentRows = rows

        val face = ensureLayout(rows)
        val target = ensureBitmap(face) ?: return null
        val target2d = canvas ?: return null

        target.eraseColor(Color.TRANSPARENT)
        drawFace(target2d, face, uptimeMs)

        lastRenderedKey = key
        lastRenderUptimeMs = uptimeMs
        width = target.width
        height = target.height
        return target
    }

    /** Re-reads the system 12/24-hour setting; call on a config change. */
    fun refreshFormat() {
        val updated = DateFormat.is24HourFormat(context)
        if (updated != is24Hour) {
            is24Hour = updated
            invalidateLayout()
        }
    }

    /**
     * Forces the next [render] to redraw and re-upload without animating —
     * for surface/context loss, where the GPU-side copy is gone but the
     * displayed time has not actually changed.
     */
    fun invalidate() {
        lastRenderedKey = Long.MIN_VALUE
        transitionStartUptimeMs = NO_TRANSITION
    }

    fun release() {
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

    private fun drawFace(target: Canvas, face: FaceLayout, uptimeMs: Long) {
        val progress = transitionProgress(uptimeMs)
        val rowCount = face.rows.size
        for (rowIndex in 0 until rowCount) {
            val row = face.rows[rowIndex]
            val previousRow = previousRows.getOrNull(rowIndex)
            val currentRow = currentRows.getOrNull(rowIndex) ?: continue
            var x = face.padding + (face.contentWidth - row.width) / 2f
            val baseline = face.padding + face.rowBaselines[rowIndex]

            for (slotIndex in row.slots.indices) {
                val slot = row.slots[slotIndex]
                val newChar = currentRow.getOrNull(slotIndex) ?: continue
                val centerX = x + slot.advance / 2f

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
                    drawGlyph(target, newChar, centerX, baseline, face, 1f, 0f, 1f)
                } else {
                    val eased = easeOutCubic(slotProgress)
                    val shift = face.textSize * TRANSITION_TRAVEL_EM
                    // Outgoing digit rises and fades; incoming rises into
                    // place from below. Scale is nudged so the swap reads as
                    // depth rather than a flat slide.
                    drawGlyph(
                        target,
                        outgoing,
                        centerX,
                        baseline,
                        face,
                        alpha = 1f - eased,
                        offsetY = -shift * eased,
                        scale = 1f - 0.10f * eased
                    )
                    drawGlyph(
                        target,
                        newChar,
                        centerX,
                        baseline,
                        face,
                        alpha = eased,
                        offsetY = shift * (1f - eased),
                        scale = 0.90f + 0.10f * eased
                    )
                }
                x += slot.advance
            }
        }
    }

    private fun drawGlyph(
        target: Canvas,
        character: Char,
        centerX: Float,
        baseline: Float,
        face: FaceLayout,
        alpha: Float,
        offsetY: Float,
        scale: Float
    ) {
        val clamped = alpha.coerceIn(0f, 1f)
        if (clamped <= 0.004f) return
        val isSeparator = character == ':'
        val styleAlpha = if (isSeparator) style.separatorAlpha else 1f
        val finalAlpha = clamped * styleAlpha
        if (finalAlpha <= 0.004f) return

        textPaint.color = color
        textPaint.alpha = (finalAlpha * 255f).toInt().coerceIn(0, 255)
        // Shadow strength tracks alpha so a fading digit does not leave a
        // hard drop shadow behind it.
        textPaint.setShadowLayer(
            face.textSize * SHADOW_RADIUS_EM,
            0f,
            face.textSize * SHADOW_DY_EM,
            Color.argb((0x66 * finalAlpha).toInt().coerceIn(0, 255), 0, 0, 0)
        )

        val text = character.toString()
        val glyphWidth = textPaint.measureText(text)
        target.save()
        target.translate(0f, offsetY)
        if (scale != 1f) target.scale(scale, scale, centerX, baseline)
        target.drawText(text, centerX - glyphWidth / 2f, baseline, textPaint)
        target.restore()
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

    private fun ensureLayout(rows: List<String>): FaceLayout {
        val existing = layout
        if (existing != null && existing.matches(rows)) return existing

        textPaint.typeface = style.typeface()
        textPaint.textSize = TEXT_SIZE_PX
        textPaint.letterSpacing = style.letterSpacingEm
        textPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)

        // Slot width is the widest digit, so the layout never reflows.
        var digitAdvance = 0f
        for (digit in '0'..'9') {
            digitAdvance = max(digitAdvance, textPaint.measureText(digit.toString()))
        }
        val separatorAdvance = textPaint.measureText(":")

        val metrics = textPaint.fontMetrics
        val rowHeight = metrics.bottom - metrics.top
        val rowLayouts = rows.map { rowText ->
            val slots = rowText.map { character ->
                Slot(
                    advance = if (character == ':') separatorAdvance else digitAdvance
                )
            }
            RowLayout(slots = slots, width = slots.sumOf { it.advance.toDouble() }.toFloat())
        }

        val contentWidth = rowLayouts.maxOfOrNull { it.width } ?: digitAdvance
        val rowSpacing = if (rows.size > 1) TEXT_SIZE_PX * ROW_SPACING_EM else 0f
        val contentHeight = rowHeight * rows.size + rowSpacing * (rows.size - 1)

        // Vertical padding leaves room for the slide travel plus the shadow,
        // so an animating glyph is never clipped by the texture edge.
        val padding = TEXT_SIZE_PX * (TRANSITION_TRAVEL_EM + SHADOW_RADIUS_EM + 0.12f)

        val baselines = FloatArray(rows.size)
        for (index in rows.indices) {
            baselines[index] = (rowHeight + rowSpacing) * index - metrics.top
        }

        val face = FaceLayout(
            rows = rowLayouts,
            rowBaselines = baselines,
            rowTexts = rows,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            padding = padding,
            textSize = TEXT_SIZE_PX
        )
        layout = face
        return face
    }

    private fun ensureBitmap(face: FaceLayout): Bitmap? {
        val targetWidth = (face.contentWidth + face.padding * 2f).toInt().coerceAtLeast(1)
        val targetHeight = (face.contentHeight + face.padding * 2f).toInt().coerceAtLeast(1)
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
        val divisor = if (showSeconds) 1_000L else 60_000L
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
        val second = calendar.get(Calendar.SECOND)

        val hourText = if (is24Hour) {
            twoDigits(hour24)
        } else {
            val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
            hour12.toString()
        }
        val minuteText = twoDigits(minute)

        return if (style.stacked) {
            if (showSeconds) {
                listOf(hourText, minuteText, twoDigits(second))
            } else {
                listOf(hourText, minuteText)
            }
        } else {
            val single = buildString {
                append(hourText)
                append(':')
                append(minuteText)
                if (showSeconds) {
                    append(':')
                    append(twoDigits(second))
                }
            }
            listOf(single)
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

    private data class Slot(val advance: Float)

    private data class RowLayout(val slots: List<Slot>, val width: Float)

    private class FaceLayout(
        val rows: List<RowLayout>,
        val rowBaselines: FloatArray,
        val rowTexts: List<String>,
        val contentWidth: Float,
        val contentHeight: Float,
        val padding: Float,
        val textSize: Float
    ) {
        /**
         * A layout is reusable while the *shape* is unchanged — same number
         * of rows and same slot pattern. "09:41" and "10:00" share a layout;
         * "9:41" and "10:41" do not, because the 12-hour hour field grows a
         * digit at ten o'clock.
         */
        fun matches(candidate: List<String>): Boolean {
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
         * Rendered size of the face. The shader scales this to whatever the
         * user picked, so this only sets sharpness. 320px of cap height is
         * comfortably above the ~380px a 16%-of-screen clock occupies on a
         * 2400px-tall display, and keeps the bitmap around 1000x450 —
         * cheap enough to re-upload during the digit animation, where the
         * old 640px constant produced ~1900x900 uploads at frame rate.
         */
        const val TEXT_SIZE_PX = 320f
        const val ROW_SPACING_EM = 0.04f
        const val SHADOW_RADIUS_EM = 0.085f
        const val SHADOW_DY_EM = 0.022f
        const val TRANSITION_DURATION_MS = 520L
        const val TRANSITION_TRAVEL_EM = 0.42f
        const val STAGGER_FRACTION = 0.13f
        const val NO_TRANSITION = Long.MIN_VALUE
    }
}
