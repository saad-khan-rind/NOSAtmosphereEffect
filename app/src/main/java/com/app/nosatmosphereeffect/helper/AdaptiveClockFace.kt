package com.app.nosatmosphereeffect.helper

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Adaptive clock: Samsung One UI's lock screen clock, rebuilt for the
 * wallpaper.
 *
 * Four tall, rounded digits hang from one line. Each runs down as far as it
 * can before it would touch the subject, so over open sky a digit is long and
 * over someone's head it stops short — the time wraps around whoever is in
 * the photo instead of sitting on them. The colon is two upright pills that
 * fit themselves the same way. Stacked, the face is two columns with no
 * colon: each column is fitted as one length, the minute hanging just under
 * the hour above it.
 *
 * ## Motion
 *
 * Every length moves like something elastic being pulled out: the clock
 * arrives at its natural height, each digit stretches out smoothly past its
 * length by about a third, and without pausing is drawn back, slowing as it
 * settles — one continuous motion, never stopping part-way. Over the first
 * half of that the whole clock, date included, glides a little way into
 * place from below and towards the middle of the screen. The curves are evaluated from the
 * time since they started rather than stepped frame by frame, so the motion
 * is the same however irregular the frames are. A new fit — the subject
 * found, the box moved — starts the stretch again from wherever each digit is
 * at that moment.
 *
 * This class owns everything that is particular to the face — fitting, the
 * the motion, the adaptive shading — and paints a distance field into a pixel
 * array. [ClockFaceRenderer] owns the bitmap, the date and the timing, exactly
 * as for the other faces, so the renderers and both backends treat this face
 * like any other.
 */
internal class AdaptiveClockFace {

    /** 0 thin .. 1 bold. Constant through every animation. */
    var weight: Float = AtmosphereClockPolicy.DEFAULT_WEIGHT

    /**
     * Shade the digits from [color] instead of filling them with it: deeper at
     * the top line and lighter the further down a digit reaches, so at any
     * given length every digit is the same shade, however long it is. Only
     * this shading animates on arrival — from a faded, even version of its
     * deepest shade into the shading, on the same curve as the motion. Every
     * other colour simply is its colour throughout.
     */
    var adaptiveColors: Boolean = false

    /**
     * Hours over minutes, in two columns and without a colon. Each column is
     * fitted and animated as one length; see [AdaptiveClockGlyphs.stackSplit].
     */
    var stacked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                reset()
            }
        }

    /**
     * The flat colour, already resolved. With [adaptiveColors] it is the
     * wallpaper's own colour — the one Auto uses — and the shading is made
     * from it.
     */
    var color: Int = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                shades = null
            }
        }

    val sceneSource = ClockSceneSource()

    /** The launcher's page offset, 0..1; decides which part of the image is on screen. */
    var scrollOffsetX: Float = 0.5f

    /**
     * True when the image is centre-cropped into the screen instead of panned
     * across it — the calibration screen, whose image need not match the
     * screen's shape.
     */
    var centerCropScene: Boolean = false

    // One motion per slot of "hh:mm", the colon included.
    private val motionFrom = FloatArray(AdaptiveClockGlyphs.SLOT_COUNT) { AdaptiveClockGlyphs.MIN_HEIGHT }
    private val motionTo = FloatArray(AdaptiveClockGlyphs.SLOT_COUNT) { AdaptiveClockGlyphs.MIN_HEIGHT }
    private val motionStart = LongArray(AdaptiveClockGlyphs.SLOT_COUNT)
    private val targets = FloatArray(AdaptiveClockGlyphs.SLOT_COUNT) { AdaptiveClockGlyphs.MIN_HEIGHT }

    private var fitKey: FitKey? = null
    private var pendingEntryUptimeMs: Long = NO_ENTRY
    /** When the whole clock's glide into place began, or [NO_ENTRY]. */
    private var glideStartUptimeMs: Long = NO_ENTRY
    /** When the shading began arriving from its faded start, or [NO_ENTRY]. */
    private var colorStartUptimeMs: Long = NO_ENTRY

    /** The shading down a digit, top to bottom; rebuilt when [color] changes. */
    private var shades: IntArray? = null
    private var rowColors = IntArray(0)

    /**
     * The clock is arriving: every length starts again from the natural
     * height and moves out to its fit. Applied on the next [step], once
     * the fit is known.
     */
    fun beginEntry(uptimeMs: Long) {
        pendingEntryUptimeMs = uptimeMs
    }

    /**
     * True when a frame is due for the face's own reasons: the scene or the
     * placement changed, or a length is still moving.
     */
    fun needsFrame(box: ClockBoxRect, screenAspect: Float, uptimeMs: Long): Boolean {
        if (keyFor(box, screenAspect) != fitKey) return true
        if (pendingEntryUptimeMs != NO_ENTRY) return true
        return !settled(uptimeMs)
    }

    /**
     * Refits the lengths if anything they depend on changed, and starts the
     * motion towards the new fit. With [animate] off every length simply
     * takes its fit.
     */
    fun step(box: ClockBoxRect, screenAspect: Float, uptimeMs: Long, animate: Boolean) {
        val key = keyFor(box, screenAspect)
        if (key != fitKey) {
            val firstFit = fitKey == null
            fitKey = key
            fit(box, screenAspect)
            for (slot in 0 until laneCount) {
                if (firstFit || !animate) {
                    snap(slot, targets[slot])
                } else if (targets[slot] != motionTo[slot]) {
                    motionFrom[slot] = heightAt(slot, uptimeMs)
                    motionTo[slot] = targets[slot]
                    motionStart[slot] = uptimeMs
                }
            }
        }
        val entry = pendingEntryUptimeMs
        if (entry != NO_ENTRY) {
            pendingEntryUptimeMs = NO_ENTRY
            if (animate) {
                val shortest = AdaptiveClockGlyphs.laneMin(stacked)
                for (slot in 0 until laneCount) {
                    motionFrom[slot] = shortest
                    motionTo[slot] = targets[slot]
                    motionStart[slot] = entry
                }
                glideStartUptimeMs = entry
                colorStartUptimeMs = entry
            }
        }
        if (!animate) {
            for (slot in 0 until laneCount) snap(slot, targets[slot])
            glideStartUptimeMs = NO_ENTRY
            colorStartUptimeMs = NO_ENTRY
        }
    }

    private val laneCount: Int
        get() = AdaptiveClockGlyphs.laneCount(stacked)

    /**
     * How far the shading has come from its faded start, 0..1 — the motion's
     * own curve, held at 1 once it gets there, so the colour arrives exactly
     * as the digits do.
     */
    private fun colorProgress(uptimeMs: Long): Float {
        val start = colorStartUptimeMs
        if (start == NO_ENTRY) return 1f
        val elapsed = uptimeMs - start
        if (elapsed >= MOTION_MS) {
            colorStartUptimeMs = NO_ENTRY
            return 1f
        }
        return motionCurve(elapsed).coerceIn(0f, 1f)
    }

    /**
     * How much of the arrival glide is still to go: 1 at the start, easing to
     * 0. The whole clock is drawn that share of its glide away from home.
     */
    fun glideRemaining(uptimeMs: Long): Float {
        val start = glideStartUptimeMs
        if (start == NO_ENTRY) return 0f
        val elapsed = uptimeMs - start
        if (elapsed >= GLIDE_MS) {
            glideStartUptimeMs = NO_ENTRY
            return 0f
        }
        if (elapsed <= 0L) return 1f
        val t = elapsed.toFloat() / GLIDE_MS
        // Home by halfway through the stretch: the clock settles into place
        // first, and the digits finish their stretch around it.
        return (1f + cos(PI_F * t)) / 2f
    }

    /** Forgets the fit, so the next frame measures from scratch without animating. */
    fun reset() {
        fitKey = null
    }

    /**
     * Paints the time — "hh:mm" in one row, or "hh" over "mm" when [stacked]
     * — into [pixels], a [stride]-wide ARGB array, with the box's top-left
     * corner at ([originX], [originY]) and [unitPx] pixels per digit width.
     *
     * [outgoing] and [change] describe a digit change in flight: the old rows
     * and, per slot counted across the whole face, how far that slot's change
     * has got (null when it is not changing).
     */
    fun paint(
        pixels: IntArray,
        stride: Int,
        rows: Int,
        originX: Float,
        originY: Float,
        unitPx: Float,
        time: List<String>,
        outgoing: List<String>?,
        change: (slot: Int) -> Float?,
        uptimeMs: Long
    ) {
        val stroke = AdaptiveClockGlyphs.stroke(weight)
        val colorAt = colorProgress(uptimeMs)
        if (!stacked) {
            val row = time.firstOrNull() ?: return
            for (slot in 0 until AdaptiveClockGlyphs.SLOT_COUNT) {
                val left = originX + AdaptiveClockGlyphs.slotLeft(slot) * unitPx
                paintSlot(
                    pixels, stride, rows, row.getOrNull(slot), outgoing?.firstOrNull()?.getOrNull(slot),
                    change(slot), left, originY, 0f, unitPx, heightAt(slot, uptimeMs), stroke, colorAt
                )
            }
            return
        }
        for (column in 0 until 2) {
            val (upper, lower) = AdaptiveClockGlyphs.stackSplit(heightAt(column, uptimeMs))
            val left = originX + AdaptiveClockGlyphs.laneLeft(true, column) * unitPx
            val lowerTop = upper + AdaptiveClockGlyphs.STACK_GAP
            paintSlot(
                pixels, stride, rows, time.getOrNull(0)?.getOrNull(column),
                outgoing?.getOrNull(0)?.getOrNull(column), change(column),
                left, originY, 0f, unitPx, upper, stroke, colorAt
            )
            paintSlot(
                pixels, stride, rows, time.getOrNull(1)?.getOrNull(column),
                outgoing?.getOrNull(1)?.getOrNull(column), change(2 + column),
                left, originY + lowerTop * unitPx, lowerTop, unitPx, lower, stroke, colorAt
            )
        }
    }

    /** One slot, morphing from [old] to [character] while a change is in flight. */
    private fun paintSlot(
        pixels: IntArray,
        stride: Int,
        rows: Int,
        character: Char?,
        old: Char?,
        progress: Float?,
        left: Float,
        top: Float,
        /** How far below the box's top line this glyph starts, in digit widths. */
        depth: Float,
        unitPx: Float,
        height: Float,
        stroke: Float,
        colorAt: Float
    ) {
        character ?: return
        if (old != null && progress != null && old != character) {
            val eased = smooth(progress)
            // The old digit thins back into its centre line and is gone
            // before the new one has finished growing out of its own, so
            // the two never read as one shape printed over the other.
            val leaving = (eased / MORPH_OUT).coerceIn(0f, 1f)
            val arriving = 1f - ((eased - MORPH_IN_START) / (1f - MORPH_IN_START)).coerceIn(0f, 1f)
            paintGlyph(pixels, stride, rows, old, left, top, depth, unitPx, height, stroke,
                erosion = leaving, colorAt = colorAt)
            paintGlyph(pixels, stride, rows, character, left, top, depth, unitPx, height, stroke,
                erosion = arriving, colorAt = colorAt)
        } else {
            paintGlyph(pixels, stride, rows, character, left, top, depth, unitPx, height, stroke,
                erosion = 0f, colorAt = colorAt)
        }
    }

    /** The date's colour: the digits' top shade (arriving with them), or the flat colour. */
    fun dateColor(uptimeMs: Long): Int {
        if (!adaptiveColors) return color
        return ColorUtils.blendARGB(fadedStart(), shadeAt(0f), colorProgress(uptimeMs))
    }

    // ---------------------------------------------------------------- motion

    private fun snap(slot: Int, height: Float) {
        motionFrom[slot] = height
        motionTo[slot] = height
        motionStart[slot] = 0L
    }

    private fun heightAt(slot: Int, uptimeMs: Long): Float {
        val from = motionFrom[slot]
        val to = motionTo[slot]
        if (from == to) return to
        val elapsed = uptimeMs - motionStart[slot]
        if (elapsed >= MOTION_MS) return to
        if (elapsed <= 0L) return from
        return from + (to - from) * motionCurve(elapsed)
    }

    private fun settled(uptimeMs: Long): Boolean {
        if (glideStartUptimeMs != NO_ENTRY && uptimeMs - glideStartUptimeMs < GLIDE_MS) {
            return false
        }
        if (colorStartUptimeMs != NO_ENTRY && uptimeMs - colorStartUptimeMs < MOTION_MS) {
            return false
        }
        for (slot in 0 until laneCount) {
            if (motionFrom[slot] != motionTo[slot] &&
                uptimeMs - motionStart[slot] < MOTION_MS
            ) {
                return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ fit

    private fun keyFor(box: ClockBoxRect, screenAspect: Float): FitKey = FitKey(
        revision = sceneSource.revision,
        left = quantize(box.left),
        top = quantize(box.top),
        width = quantize(box.width),
        height = quantize(box.height),
        aspect = quantize(screenAspect),
        // Coarser than the rest: while the pages scroll this changes every
        // frame, and each change is a refit and a redraw.
        scroll = (scrollOffsetX * SCROLL_STEPS).roundToInt(),
        centerCrop = centerCropScene,
        stacked = stacked
    )

    private fun viewport(scene: ClockScene, screenAspect: Float): ClockSceneViewport =
        if (centerCropScene) {
            ClockSceneViewport.forCenterCrop(screenAspect, scene.sourceAspect)
        } else {
            ClockSceneViewport.forScroll(scrollOffsetX, screenAspect, scene.sourceAspect)
        }

    /**
     * How long each slot may be: down to just above the highest point of the
     * subject under it, never shorter than its natural height and never
     * longer than the box.
     *
     * Until segmentation has answered the clock stays compact, so a slow
     * model sends the digits out when it answers rather than snapping them
     * back up. When it answers with no subject at all, everything runs full
     * length.
     */
    private fun fit(box: ClockBoxRect, screenAspect: Float) {
        val scene = sceneSource.scene
        val shortest = AdaptiveClockGlyphs.laneMin(stacked)
        if (scene == null || !scene.subjectKnown) {
            targets.fill(shortest)
            return
        }
        val view = viewport(scene, screenAspect)
        for (lane in 0 until laneCount) {
            targets[lane] = AdaptiveClockFit.digitHeight(
                slotLeft = AdaptiveClockGlyphs.laneLeft(stacked, lane),
                slotWidth = AdaptiveClockGlyphs.laneWidth(stacked, lane),
                box = box,
                boxWidthUnits = AdaptiveClockGlyphs.boxWidth(stacked),
                boxHeightUnits = AdaptiveClockGlyphs.boxHeight(stacked),
                minLength = shortest,
                subjectAt = { x, y -> scene.subjectAt(view.u(x), view.v(y)) }
            )
        }
    }

    // ---------------------------------------------------------------- paint

    /**
     * [erosion] 0 draws the glyph whole; towards 1 its stroke thins to the
     * centre line and then the field drops away entirely, so at 1 nothing is
     * left — not even the hairline a field merely lowered would leave.
     */
    private fun paintGlyph(
        pixels: IntArray,
        stride: Int,
        rows: Int,
        character: Char,
        cellLeft: Float,
        cellTop: Float,
        /** How far [cellTop] is below the box's top line, in digit widths. */
        depth: Float,
        unitPx: Float,
        height: Float,
        stroke: Float,
        erosion: Float,
        colorAt: Float
    ) {
        if (erosion >= 1f) return
        val skeleton = AdaptiveClockGlyphs.skeleton(character, height, stroke) ?: return
        val spread = SPREAD_UNITS
        val half = stroke / 2f
        val reach = half + spread
        val x0 = max(0, (cellLeft + (skeleton.minX - reach) * unitPx).toInt())
        val x1 = min(stride - 1, (cellLeft + (skeleton.maxX + reach) * unitPx).toInt() + 1)
        val y0 = max(0, (cellTop + (skeleton.minY - reach) * unitPx).toInt())
        val y1 = min(rows - 1, (cellTop + (skeleton.maxY + reach) * unitPx).toInt() + 1)
        if (x0 > x1 || y0 > y1) return
        val thickness = half * (1f - erosion)
        val lift = erosion * spread
        val inverseUnit = 1f / unitPx
        val inverseSpread = 1f / (2f * spread)

        // The shading only changes down a digit, so one colour per row keeps
        // colour work out of the pixel loop. It is measured from the box's top
        // line, not the digit's, so a stacked minute carries on where the hour
        // above it left off.
        val rowCount = y1 - y0 + 1
        if (rowColors.size < rowCount) rowColors = IntArray(rowCount)
        val faded = if (adaptiveColors && colorAt < 1f) fadedStart() else 0
        for (index in 0 until rowCount) {
            rowColors[index] = if (adaptiveColors) {
                val uy = depth + (y0 + index + 0.5f - cellTop) * inverseUnit
                val shade = shadeAt(uy)
                if (colorAt < 1f) ColorUtils.blendARGB(faded, shade, colorAt) else shade
            } else {
                color
            } and 0x00FFFFFF
        }

        for (py in y0..y1) {
            val uy = (py + 0.5f - cellTop) * inverseUnit
            val base = py * stride
            val rgb = rowColors[py - y0]
            for (px in x0..x1) {
                val ux = (px + 0.5f - cellLeft) * inverseUnit
                val distance = skeleton.distance(ux, uy, reach)
                if (distance >= reach) continue
                val signed = distance - thickness + lift
                if (signed >= spread) continue
                val field = (0.5f - signed * inverseSpread).coerceIn(0f, 1f)
                val alpha = (field * 255f).roundToInt()
                if (alpha <= 0) continue
                if (alpha <= pixels[base + px] ushr 24) continue
                pixels[base + px] = (alpha shl 24) or rgb
            }
        }
    }

    /** The shading's faded, even starting colour; see [fadedOf]. */
    private fun fadedStart(): Int = fadedOf(color)

    /** The shade at [unitY] digit widths below the top line. */
    private fun shadeAt(unitY: Float): Int {
        val table = shades ?: IntArray(SHADE_STEPS) { step ->
            shadeOf(color, step / (SHADE_STEPS - 1f))
        }.also { shades = it }
        val index = ((unitY / AdaptiveClockGlyphs.boxHeight(stacked)) * (SHADE_STEPS - 1))
            .roundToInt()
            .coerceIn(0, SHADE_STEPS - 1)
        return table[index]
    }

    private data class FitKey(
        val revision: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val aspect: Int,
        val scroll: Int,
        val centerCrop: Boolean,
        val stacked: Boolean
    )

    private fun quantize(value: Float): Int = (value * 512f).roundToInt()

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        /** The distance field's reach past the ink, in digit widths. */
        const val SPREAD_UNITS = 0.08f

        /**
         * How far past the box the overshoot can carry a digit: [OVERSHOOT] of
         * the longest possible stretch, with room to spare. The face bitmap
         * leaves this much below the box.
         */
        fun overshootUnits(stacked: Boolean): Float =
            (AdaptiveClockGlyphs.boxHeight(stacked) - AdaptiveClockGlyphs.laneMin(stacked)) * 0.36f

        /**
         * The motion: a gentle rise that turns over [OVERSHOOT] past the
         * target, then a return that pulls back hard at first and slows into
         * place — how a stretched elastic behaves. The return is a critically
         * damped decay, and the two are joined where both are at rest with
         * the same pull on them, so the turn at the top is one smooth motion
         * with no pause in it.
         */
        const val OVERSHOOT = 0.32f
        const val RISE_MS = 567L
        const val SETTLE_MS = 833L
        const val MOTION_MS = RISE_MS + SETTLE_MS

        /** The return's decay rate, per second: all but 1% gone by [SETTLE_MS]. */
        private const val SETTLE_RATE = 6.6f / (SETTLE_MS / 1000f)

        /** Progress 0 → 1 at [elapsedMs], running past 1 by [OVERSHOOT] on the way. */
        fun motionCurve(elapsedMs: Long): Float {
            if (elapsedMs <= 0L) return 0f
            val peak = 1f + OVERSHOOT
            if (elapsedMs < RISE_MS) {
                val t = elapsedMs.toFloat() / RISE_MS
                return peak * (1f - cos(PI_F * t)) / 2f
            }
            if (elapsedMs >= MOTION_MS) return 1f
            val seconds = (elapsedMs - RISE_MS) / 1000f
            val end = settle(SETTLE_MS / 1000f)
            val remaining = (settle(seconds) - end) / (1f - end)
            return 1f + OVERSHOOT * remaining
        }

        /** Critically damped decay from rest: 1 at 0, falling to nearly 0. */
        private fun settle(seconds: Float): Float {
            val x = SETTLE_RATE * seconds
            return (1f + x) * exp(-x)
        }

        private const val PI_F = PI.toFloat()

        /**
         * How far the whole clock glides into place on arrival, in digit
         * widths — a small drift, not a slide — and how long it takes: half
         * the stretch, so it is home before the digits have settled.
         */
        const val GLIDE_UNITS = 0.1f
        const val GLIDE_MS = MOTION_MS / 2

        /** The share of a digit change over which the old digit leaves. */
        private const val MORPH_OUT = 0.55f
        /** Where in a digit change the new digit starts growing. */
        private const val MORPH_IN_START = 0.3f

        private const val NO_ENTRY = Long.MIN_VALUE
        private const val SCROLL_STEPS = 96f
        private const val SHADE_STEPS = 96

        /**
         * Lightness of the shading at the top line and at full length. The
         * bottom stops well short of white: it is a light shade of the
         * wallpaper's colour, so the digits still look made from the photo
         * where they run longest.
         */
        private const val TOP_LIGHTNESS = 0.62f
        private const val BOTTOM_LIGHTNESS = 0.83f
        /** The least saturation a coloured wallpaper's shading is given. */
        private const val MIN_SHADE_SATURATION = 0.22f

        /**
         * The Adaptive shading of [base] — the wallpaper's own colour, as
         * Auto uses it — at [fraction] of the way from the top line (0) to
         * full length (1): its hue, from a deeper shade at the top to a light
         * one at the bottom. Past full length (an overshoot) it stays at the
         * lightest. A grey wallpaper shades grey to off-white.
         */
        fun shadeOf(base: Int, fraction: Float): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(base, hsl)
            val t = fraction.coerceIn(0f, 1f)
            if (hsl[1] >= 0.05f) hsl[1] = max(hsl[1], MIN_SHADE_SATURATION)
            hsl[2] = TOP_LIGHTNESS + (BOTTOM_LIGHTNESS - TOP_LIGHTNESS) * t
            return ColorUtils.HSLToColor(hsl) or (0xFF shl 24)
        }

        /**
         * Where the shading starts on arrival: one even colour, a faded
         * version of its deepest shade — the same hue, washed out and a
         * little lighter.
         */
        fun fadedOf(base: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(shadeOf(base, 0f), hsl)
            hsl[1] *= 0.35f
            hsl[2] = min(hsl[2] + 0.1f, 1f)
            return ColorUtils.HSLToColor(hsl) or (0xFF shl 24)
        }
    }
}

/**
 * The measurement that makes the face adaptive, kept free of Android so it
 * can be tested.
 */
internal object AdaptiveClockFit {

    /** Space left between a digit's end and the subject, in digit widths. */
    const val CLEARANCE = 0.2f

    private const val COLUMNS = 5
    private const val STEP = 0.04f
    private const val SUBJECT_THRESHOLD = 0.5f

    /**
     * How tall the slot whose cell starts [slotLeft] units into the row and
     * is [slotWidth] wide may be, given the digits' box on screen and the
     * subject coverage at screen coordinates.
     */
    fun digitHeight(
        slotLeft: Float,
        box: ClockBoxRect,
        slotWidth: Float = AdaptiveClockGlyphs.DIGIT_WIDTH,
        /** The box's size in digit widths: one row, or the stack. */
        boxWidthUnits: Float = AdaptiveClockGlyphs.ROW_WIDTH,
        boxHeightUnits: Float = AdaptiveClockGlyphs.MAX_HEIGHT,
        minLength: Float = AdaptiveClockGlyphs.MIN_HEIGHT,
        subjectAt: (screenX: Float, screenY: Float) -> Float
    ): Float {
        val maxHeight = boxHeightUnits
        var reach = maxHeight + CLEARANCE
        for (column in 0 until COLUMNS) {
            // Inset from the cell's edges: the ink's own rounded corners do
            // not come that close to a neighbour's subject.
            val unitX = slotLeft + slotWidth * (0.08f + 0.84f * column / (COLUMNS - 1f))
            val screenX = box.left + unitX / boxWidthUnits * box.width
            var unitY = 0f
            while (unitY < reach) {
                val screenY = box.top + unitY / maxHeight * box.height
                if (subjectAt(screenX, screenY) >= SUBJECT_THRESHOLD) {
                    reach = unitY
                    break
                }
                unitY += STEP
            }
        }
        return (reach - CLEARANCE).coerceIn(minLength, maxHeight)
    }
}
