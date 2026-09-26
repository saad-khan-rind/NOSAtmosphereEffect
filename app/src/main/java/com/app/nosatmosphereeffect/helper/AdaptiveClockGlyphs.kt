package com.app.nosatmosphereeffect.helper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Adaptive clock's digits, drawn from centre-line skeletons rather than a
 * typeface.
 *
 * ## Why not a font
 *
 * The face's defining trick is that every digit has its own height: a digit
 * with nothing under it runs long, one standing over the subject stops short.
 * Stretching a font glyph to do that stretches its curves too — a tall 0 would
 * become an ellipse and its stroke would thin along the sides. Samsung's
 * "stretch font" keeps every curve the same size at any length and lengthens
 * only the straight vertical runs, which is exactly what a skeleton does: the
 * straight segments get longer, the arcs keep their radius, and the stroke is
 * a distance from the centre line, so it cannot change with height.
 *
 * ## The shapes
 *
 * Traced from photographs of One UI 8's lock screen digits, each at its
 * natural height and stretched: a condensed, round-ended face whose curves
 * are half-circles the width of the digit, joined by straight uprights and a
 * few S-bends. Each feature is placed from both measurements — where it sits
 * at the natural height, and how far it moves as the digit stretches — so a
 * digit looks right at every length in between, not just at the two ends.
 *
 * ## Units
 *
 * One unit is a digit's width. A digit is [DIGIT_WIDTH] wide and at least
 * [MIN_HEIGHT] tall, with its top edge fixed — all digits hang from the same
 * line and only their bottoms move.
 *
 * Coordinates are y-down, and arc angles follow: 0° points right, 90° down,
 * 180° left, 270° up. Sweeps run clockwise on screen.
 */
internal object AdaptiveClockGlyphs {

    const val DIGIT_WIDTH = 1f
    const val DIGIT_GAP = 0.24f
    const val COLON_WIDTH = 0.36f
    const val COLON_GAP = 0.2f

    /**
     * A digit's natural height, and the shortest it gets. This is the compact
     * face the clock arrives as, and what a digit falls back to when the
     * subject reaches above it.
     */
    const val MIN_HEIGHT = 2.3f

    /** The longest a digit settles at, and the height of the box the user places. */
    const val MAX_HEIGHT = 4.3f

    /** Stroke width at the two ends of the weight slider. */
    const val THIN_STROKE = 0.12f
    const val BOLD_STROKE = 0.36f

    /** Width of "hh:mm" laid out in one row. */
    const val ROW_WIDTH =
        4f * DIGIT_WIDTH + 2f * DIGIT_GAP + 2f * COLON_GAP + COLON_WIDTH

    /** Slot count of "hh:mm". */
    const val SLOT_COUNT = 5

    /**
     * The stacked face: hours over minutes in two columns, no colon. Each
     * column is one length, fitted to the subject like a digit is, with the
     * hour hanging from the top line and the minute hanging just under it.
     */
    const val STACK_WIDTH = 2f * DIGIT_WIDTH + DIGIT_GAP
    const val STACK_HEIGHT = 7f
    /** Between an hour digit's end and the minute below it. */
    const val STACK_GAP = 0.3f

    /** The box the user places, in digit widths. */
    fun boxWidth(stacked: Boolean): Float = if (stacked) STACK_WIDTH else ROW_WIDTH
    fun boxHeight(stacked: Boolean): Float = if (stacked) STACK_HEIGHT else MAX_HEIGHT

    /**
     * Lanes are what the face fits and animates: each digit and the colon on
     * one row, each column of the stack.
     */
    fun laneCount(stacked: Boolean): Int = if (stacked) 2 else SLOT_COUNT
    fun laneLeft(stacked: Boolean, lane: Int): Float =
        if (stacked) lane * (DIGIT_WIDTH + DIGIT_GAP) else slotLeft(lane)
    fun laneWidth(stacked: Boolean, lane: Int): Float =
        if (stacked) DIGIT_WIDTH else slotWidth(lane)
    /** A lane's shortest length: one natural digit, or two and the gap between. */
    fun laneMin(stacked: Boolean): Float =
        if (stacked) 2f * MIN_HEIGHT + STACK_GAP else MIN_HEIGHT

    /**
     * How a stacked column of [length] splits: the hour's height, then the
     * minute's. Even halves, neither shorter than a natural digit.
     */
    fun stackSplit(length: Float): Pair<Float, Float> {
        val upper = max((length - STACK_GAP) / 2f, MIN_HEIGHT)
        val lower = max(length - STACK_GAP - upper, MIN_HEIGHT)
        return upper to lower
    }

    /** Stroke width for a 0..1 weight. */
    fun stroke(weight: Float): Float =
        THIN_STROKE + (BOLD_STROKE - THIN_STROKE) * weight.coerceIn(0f, 1f)

    /** Left edge of slot [index] of "hh:mm", in units from the row's left edge. */
    fun slotLeft(index: Int): Float = when (index) {
        0 -> 0f
        1 -> DIGIT_WIDTH + DIGIT_GAP
        2 -> 2f * DIGIT_WIDTH + DIGIT_GAP + COLON_GAP
        3 -> 2f * DIGIT_WIDTH + DIGIT_GAP + 2f * COLON_GAP + COLON_WIDTH
        else -> 3f * DIGIT_WIDTH + 2f * DIGIT_GAP + 2f * COLON_GAP + COLON_WIDTH
    }

    fun slotWidth(index: Int): Float = if (index == 2) COLON_WIDTH else DIGIT_WIDTH

    /**
     * The skeleton of [character] in a cell [slotWidth] wide and [height]
     * tall, for a stroke [stroke] wide. Null for anything that is not a digit
     * or a colon.
     */
    fun skeleton(character: Char, height: Float, stroke: Float): Skeleton? {
        val s = stroke.coerceIn(0.02f, 0.6f)
        val h = max(height, MIN_HEIGHT)
        val builder = Skeleton.Builder()
        // The stroke's centre line sits half a stroke inside the cell, so the
        // ink touches the cell's edges exactly whatever the weight.
        val left = s / 2f
        val right = DIGIT_WIDTH - s / 2f
        val top = s / 2f
        val bottom = h - s / 2f
        val centre = DIGIT_WIDTH / 2f
        val radius = (right - left) / 2f
        val span = bottom - top
        // How far past its natural height the digit has been stretched.
        val extra = h - MIN_HEIGHT

        when (character) {
            '0' -> builder.stadium(centre, radius, top, bottom)
            '1' -> {
                // A stem on the right, and a flag that dips straight away from
                // its top in one convex sweep, ending near the left edge about
                // three quarters of a width lower.
                val stem = min(0.78f, right)
                builder.line(stem, top, stem, bottom)
                builder.curve(stem, top + 0.02f, stem - 0.35f, top + 0.3f, left + 0.03f, top + 0.72f)
            }
            '2' -> {
                // Round hook over the top, its end hanging a little down the
                // left; straight down the right; an S across to the left;
                // straight down the left into the base; and a short stub
                // turning up at the base's right end.
                builder.arc(centre, top + radius, radius, 180f, 180f)
                builder.line(left, top + radius, left, top + radius + 0.22f + extra * 0.2f)
                val bendTop = top + 0.4f + extra * 0.2f
                val bendEnd = min(top + 1.3f + extra * 0.45f, bottom - s)
                builder.line(right, top + radius, right, bendTop)
                builder.sCurve(right, bendTop, left, bendEnd)
                builder.line(left, bendEnd, left, bottom)
                builder.line(left, bottom, right, bottom)
                val stub = 0.72f + extra * 0.45f - s / 2f
                builder.line(right, bottom, right, max(bottom - stub, bendEnd + s))
            }
            '3' -> {
                // Round top and bottom with the ends hanging towards each
                // other, one straight right side with a small notch at the
                // middle, and the middle bar stopping short of the left.
                val middle = top + span * 0.45f + extra * 0.25f
                val corner = min(0.24f, radius * 0.7f)
                builder.arc(centre, top + radius, radius, 180f, 180f)
                builder.line(left, top + radius, left, top + radius + 0.13f + extra * 0.34f)
                builder.line(right, top + radius, right, middle - corner)
                builder.arc(right - corner, middle - corner, corner, 0f, 90f)
                builder.line(right - corner, middle, left + 0.34f, middle)
                builder.arc(right - corner, middle + corner, corner, 270f, 90f)
                builder.line(right, middle + corner, right, bottom - radius)
                builder.hookedBottom(
                    centre, radius, left, bottom,
                    hookTop = max(top + span * HOOK_REACH, middle + s + 0.12f)
                )
            }
            '4' -> {
                // Not an open 4: the left stroke curves down from the top
                // middle to the left edge and on into the crossbar, and the
                // right stem only starts partway down.
                val cross = top + span * 0.72f
                val bendEnd = top + span * 0.5f
                val stemTop = top + span * 0.42f
                builder.sCurve(0.6f, top, left, bendEnd)
                builder.line(left, bendEnd, left, cross)
                builder.line(left, cross, right, cross)
                builder.line(right, stemTop, right, bottom)
            }
            '5' -> {
                // Bar across the top, the stem down the left, and the bowl
                // springing out of the stem as a half-circle into a straight
                // right side and a J at the bottom.
                val shoulder = min(top + span * 0.39f, bottom - 2f * radius - s)
                builder.line(right, top, left, top)
                builder.line(left, top, left, shoulder + radius)
                builder.arc(centre, shoulder + radius, radius, 180f, 180f)
                builder.line(right, shoulder + radius, right, bottom - radius)
                builder.hookedBottom(
                    centre, radius, left, bottom,
                    hookTop = max(top + span * HOOK_REACH, shoulder + radius + s + 0.12f)
                )
            }
            '6' -> {
                // A hook over the top whose right end hangs down, a straight
                // left side, and a bowl that grows as the digit stretches.
                val bowl = min(1.07f + extra * 0.64f, span)
                builder.arc(centre, top + radius, radius, 180f, 180f)
                builder.line(
                    right, top + radius,
                    right, min(top + radius + 0.15f + extra * 0.3f, bottom - bowl - s)
                )
                builder.line(left, top + radius, left, bottom - radius)
                builder.stadium(centre, radius, bottom - bowl, bottom)
            }
            '7' -> {
                // A bar with its left end hanging down, the right end turning
                // straight down, an S over to the left, and a straight stem.
                val hang = 0.55f + extra * 0.35f
                val foot = left + 0.18f
                val bendTop = top + 0.6f + extra * 1.1f
                val bendBottom = min(top + 1.66f + extra * 0.8f, bottom - s * 0.5f)
                builder.line(left, top + hang, left, top)
                builder.line(left, top, right, top)
                builder.line(right, top, right, bendTop)
                builder.sCurve(right, bendTop, foot, bendBottom)
                builder.line(foot, bendBottom, foot, bottom)
            }
            '8' -> {
                val middle = top + span * 0.44f
                val upper = min(radius * 0.88f, (middle - top) / 2f)
                builder.stadium(centre, upper, top, middle)
                val lower = min(radius, (bottom - middle) / 2f)
                builder.stadium(centre, lower, middle, bottom)
            }
            '9' -> {
                // A closed bowl on top that grows as the digit stretches,
                // the right stem on down, and a J at the bottom.
                val bowl = min(1.0f + extra * 0.6f, span)
                builder.stadium(centre, radius, top, top + bowl)
                builder.line(right, top + radius, right, bottom - radius)
                builder.hookedBottom(
                    centre, radius, left, bottom,
                    hookTop = max(top + span * HOOK_REACH, top + bowl + s + 0.1f)
                )
            }
            ':' -> {
                // Two upright pills a stroke wide, filling most of the
                // colon's own height and stretching with it like a digit.
                val x = COLON_WIDTH / 2f
                for (index in COLON_PILLS.indices step 2) {
                    val start = h * COLON_PILLS[index] + s / 2f
                    val end = max(h * COLON_PILLS[index + 1] - s / 2f, start)
                    builder.line(x, start, x, end)
                }
            }
            else -> return null
        }
        return builder.build()
    }

    /**
     * How far up the 5's and 9's bottom curve turns back, as a share of the
     * digit's height: a U whose left arm rises to about seven tenths of the
     * way down, as on the phone.
     */
    private const val HOOK_REACH = 0.69f

    /** Top and bottom of each colon pill, as fractions of the colon's height. */
    private val COLON_PILLS = floatArrayOf(0.2f, 0.44f, 0.56f, 0.8f)

    private const val DEG = PI / 180.0

    /**
     * Straight lines and circular arcs, with the distance to the nearest of
     * them. Built per digit per frame and queried per pixel, so the query is
     * arithmetic on flat arrays, and every primitive carries its bounding box
     * so the ones out of reach of a pixel cost four comparisons.
     */
    class Skeleton private constructor(
        /** x0, y0, x1, y1 per line. */
        private val lines: FloatArray,
        /** cx, cy, radius, start (radians), sweep (radians) per arc. */
        private val arcs: FloatArray,
        /** minX, minY, maxX, maxY per line, then per arc. */
        private val lineBounds: FloatArray,
        private val arcBounds: FloatArray,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float
    ) {
        /**
         * Distance from (x, y) to the nearest point on the skeleton, or
         * [cutoff] if nothing is nearer than that. Painting only cares about
         * distances within the field's reach, and the cutoff is what lets
         * most primitives be skipped outright.
         */
        fun distance(x: Float, y: Float, cutoff: Float = Float.MAX_VALUE): Float {
            var best = cutoff
            var index = 0
            var bound = 0
            while (index < lines.size) {
                if (x > lineBounds[bound] - best && x < lineBounds[bound + 2] + best &&
                    y > lineBounds[bound + 1] - best && y < lineBounds[bound + 3] + best
                ) {
                    val d = lineDistance(
                        x, y,
                        lines[index], lines[index + 1], lines[index + 2], lines[index + 3]
                    )
                    if (d < best) best = d
                }
                index += 4
                bound += 4
            }
            index = 0
            bound = 0
            while (index < arcs.size) {
                if (x > arcBounds[bound] - best && x < arcBounds[bound + 2] + best &&
                    y > arcBounds[bound + 1] - best && y < arcBounds[bound + 3] + best
                ) {
                    val cx = arcs[index]
                    val cy = arcs[index + 1]
                    val radius = arcs[index + 2]
                    val dx = x - cx
                    val dy = y - cy
                    // Nothing on the arc, ends included, is nearer than the
                    // circle it lies on — so most arcs are ruled out before
                    // the one expensive step, the angle test.
                    val radial = abs(sqrt(dx * dx + dy * dy) - radius)
                    if (radial < best) {
                        val d = arcDistance(x, y, cx, cy, radius, arcs[index + 3], arcs[index + 4])
                        if (d < best) best = d
                    }
                }
                index += 5
                bound += 4
            }
            return best
        }

        class Builder {
            private val lines = ArrayList<Float>(28)
            private val arcs = ArrayList<Float>(30)
            private val lineBounds = ArrayList<Float>(28)
            private val arcBounds = ArrayList<Float>(24)
            private var minX = Float.MAX_VALUE
            private var minY = Float.MAX_VALUE
            private var maxX = -Float.MAX_VALUE
            private var maxY = -Float.MAX_VALUE

            fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                lines += x0; lines += y0; lines += x1; lines += y1
                lineBounds += min(x0, x1); lineBounds += min(y0, y1)
                lineBounds += max(x0, x1); lineBounds += max(y0, y1)
                include(x0, y0)
                include(x1, y1)
            }

            /** [startDegrees] and [sweepDegrees] clockwise on screen (y-down). */
            fun arc(cx: Float, cy: Float, radius: Float, startDegrees: Float, sweepDegrees: Float) {
                arcs += cx; arcs += cy; arcs += radius
                arcs += (startDegrees * DEG).toFloat()
                arcs += (sweepDegrees * DEG).toFloat()
                arcBounds += cx - radius; arcBounds += cy - radius
                arcBounds += cx + radius; arcBounds += cy + radius
                include(cx - radius, cy - radius)
                include(cx + radius, cy + radius)
            }

            /**
             * The bottom of a 5 or 9: a half-circle from the right side round
             * to the left, then straight up to [hookTop]. When there is no
             * room for the whole half-circle below [hookTop] — a compact digit
             * — the curve simply stops early, so it never runs into what is
             * above it.
             */
            fun hookedBottom(cx: Float, radius: Float, left: Float, bottom: Float, hookTop: Float) {
                val centreY = bottom - radius
                if (hookTop <= centreY) {
                    arc(cx, centreY, radius, 0f, 180f)
                    line(left, centreY, left, hookTop)
                } else {
                    val rise = ((hookTop - centreY) / radius).coerceIn(0f, 1f)
                    val sweep = 180f - Math.toDegrees(kotlin.math.asin(rise).toDouble()).toFloat()
                    arc(cx, centreY, radius, 0f, max(sweep, 90f))
                }
            }

            /**
             * An S from ([x0], [y0]) down to ([x1], [y1]) that leaves and
             * arrives travelling straight down, so it runs smoothly out of
             * the stroke above and into the one below.
             */
            fun sCurve(x0: Float, y0: Float, x1: Float, y1: Float) {
                var px = x0
                var py = y0
                for (step in 1..S_STEPS) {
                    val t = step.toFloat() / S_STEPS
                    val nx = x0 + (x1 - x0) * (1f - cos(PI * t).toFloat()) / 2f
                    val ny = y0 + (y1 - y0) * t
                    line(px, py, nx, ny)
                    px = nx
                    py = ny
                }
            }

            /** A smooth quadratic curve from (x0, y0) to (x2, y2), pulled towards (x1, y1). */
            fun curve(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
                var px = x0
                var py = y0
                for (step in 1..S_STEPS) {
                    val t = step.toFloat() / S_STEPS
                    val u = 1f - t
                    val nx = u * u * x0 + 2f * u * t * x1 + t * t * x2
                    val ny = u * u * y0 + 2f * u * t * y1 + t * t * y2
                    line(px, py, nx, ny)
                    px = nx
                    py = ny
                }
            }

            /** A closed pill: round top and bottom, straight sides. */
            fun stadium(cx: Float, radius: Float, top: Float, bottom: Float) {
                val upper = top + radius
                val lower = max(bottom - radius, upper)
                arc(cx, upper, radius, 180f, 180f)
                arc(cx, lower, radius, 0f, 180f)
                line(cx - radius, upper, cx - radius, lower)
                line(cx + radius, upper, cx + radius, lower)
            }

            private fun include(x: Float, y: Float) {
                minX = min(minX, x); minY = min(minY, y)
                maxX = max(maxX, x); maxY = max(maxY, y)
            }

            fun build(): Skeleton = Skeleton(
                lines.toFloatArray(),
                arcs.toFloatArray(),
                lineBounds.toFloatArray(),
                arcBounds.toFloatArray(),
                minX, minY, maxX, maxY
            )
        }

        private companion object {
            const val TWO_PI = (2.0 * PI).toFloat()
            /** Segments per S-curve: enough that no facet shows at any size. */
            const val S_STEPS = 12

            fun lineDistance(
                px: Float, py: Float,
                x0: Float, y0: Float, x1: Float, y1: Float
            ): Float {
                val dx = x1 - x0
                val dy = y1 - y0
                val lengthSquared = dx * dx + dy * dy
                if (lengthSquared <= 1e-8f) return hypot(px - x0, py - y0)
                val t = (((px - x0) * dx + (py - y0) * dy) / lengthSquared).coerceIn(0f, 1f)
                val ex = px - (x0 + t * dx)
                val ey = py - (y0 + t * dy)
                return sqrt(ex * ex + ey * ey)
            }

            fun arcDistance(
                px: Float, py: Float,
                cx: Float, cy: Float, radius: Float, start: Float, sweep: Float
            ): Float {
                val dx = px - cx
                val dy = py - cy
                var angle = atan2(dy, dx) - start
                angle %= TWO_PI
                if (angle < 0f) angle += TWO_PI
                if (angle <= sweep) {
                    return abs(sqrt(dx * dx + dy * dy) - radius)
                }
                // Outside the arc's span: the nearer end cap.
                val endAngle = start + sweep
                val sx = cx + radius * cos(start)
                val sy = cy + radius * sin(start)
                val ex = cx + radius * cos(endAngle)
                val ey = cy + radius * sin(endAngle)
                return min(hypot(px - sx, py - sy), hypot(px - ex, py - ey))
            }
        }
    }
}
