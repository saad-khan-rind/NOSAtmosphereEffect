package com.app.nosatmosphereeffect.helper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Clock digits drawn from scratch, for the faces that are not a system font.
 *
 * ## How a digit is made
 *
 * Every digit is a centre-line skeleton — a few arcs and straight runs — drawn
 * with a pen. The skeletons are shared by every design; what makes one design
 * look nothing like another is three choices:
 *
 * - the bowl: an oval (a superellipse, round to squarish) or a box with
 *   rounded corners (a stadium when they are fully round, a rectangle when
 *   they are not), which every curved digit is cut from;
 * - the proportions: width, height, and a handful of letterform switches
 *   such as a 6 with a hooked or a straight tail;
 * - the pen: an even felt tip, a broad nib that is thick pulling down and a
 *   hairline across (the high-contrast serif faces), several parallel lines
 *   (the retro neon face), or a dot grid instead of strokes.
 *
 * ## Units
 *
 * Everything here is in ems, y down, with the digit standing on the baseline
 * at y = 0: ink runs from y = -height to 0. Nothing here touches Android, so
 * the same geometry is rendered on a Canvas in the app and checked by plain
 * JVM tests.
 */
internal object ClockDigitGeometry {

    /** The digit height every drawn face is scaled to, matching the system faces' stretched digits. */
    const val DIGIT_HEIGHT_EM = 1.0f

    /**
     * The distance-field spread, in ems once scaled to [DIGIT_HEIGHT_EM], that
     * keeps [design]'s thinnest stroke lit under glass.
     *
     * The glass bevel samples the silhouette two thirds of a spread to either
     * side and cancels on any stroke narrower than that, so a hairline under
     * the standard spread came out as clear, invisible glass. Three quarters
     * of the thinnest stroke leaves a rim on each side of it.
     */
    fun spreadFor(design: DigitDesign): Float {
        val thinnest = design.pen.thinnestStroke() * DIGIT_HEIGHT_EM / design.height
        return (thinnest * 0.75f).coerceIn(MIN_SPREAD_EM, STANDARD_SPREAD_EM)
    }

    private const val MIN_SPREAD_EM = 0.014f

    /** The thinnest stroke glass can light well, as a share of the digit height. */
    const val GLASS_MIN_STROKE = 0.035f

    /**
     * The same for the translucent look, which shows a stroke by bending the
     * wallpaper across its width rather than by lighting its edges, and so
     * needs more of one: its own system face runs at about twice this.
     */
    const val TRANSLUCENT_MIN_STROKE = 0.08f

    /**
     * [design] as the glass looks draw it: hairlines thickened to [minStroke]
     * of the digit height ([GLASS_MIN_STROKE] or [TRANSLUCENT_MIN_STROKE]). Glass shows a stroke by the light along its edges,
     * and a field narrow enough to light a hairline leaves the face's thick
     * strokes with almost no rim, so the whole clock read faintly. The Normal
     * look keeps every design exactly as drawn.
     */
    fun forGlass(design: DigitDesign, minStroke: Float = GLASS_MIN_STROKE): DigitDesign {
        val least = design.height * minStroke
        val pen = when (val p = design.pen) {
            is ClockPen.Mono -> p.copy(width = max(p.width, least))
            is ClockPen.Nib -> p.copy(thin = max(p.thin, least), thick = max(p.thick, least))
            else -> p
        }
        return design.copy(pen = pen)
    }
    /** The system faces' spread; see ClockGlyphAtlas.SPREAD_EM. */
    private const val STANDARD_SPREAD_EM = 0.062f

    /** The ink box of [ink], in ems relative to the glyph's origin, transform included. */
    fun bounds(ink: GlyphInk): FloatArray {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        val (padX, padY) = ink.pen.halfExtent()
        for (stroke in ink.strokes) {
            val points = stroke.points
            var index = 0
            while (index < points.size) {
                left = min(left, points[index] - padX)
                right = max(right, points[index] + padX)
                top = min(top, points[index + 1] - padY)
                bottom = max(bottom, points[index + 1] + padY)
                index += 2
            }
        }
        for (dot in ink.dots) {
            left = min(left, dot.x - dot.radius)
            right = max(right, dot.x + dot.radius)
            top = min(top, dot.y - dot.radius)
            bottom = max(bottom, dot.y + dot.radius)
        }
        if (left > right) return floatArrayOf(0f, 0f, 0f, 0f)
        if (ink.skew == 0f && ink.rotationDegrees == 0f) return floatArrayOf(left, top, right, bottom)
        // Transformed: bound the four corners of the upright box.
        val corners = floatArrayOf(left, top, right, top, right, bottom, left, bottom)
        var tLeft = Float.MAX_VALUE
        var tTop = Float.MAX_VALUE
        var tRight = -Float.MAX_VALUE
        var tBottom = -Float.MAX_VALUE
        var index = 0
        while (index < corners.size) {
            val (x, y) = ink.transform(corners[index], corners[index + 1])
            tLeft = min(tLeft, x)
            tRight = max(tRight, x)
            tTop = min(tTop, y)
            tBottom = max(tBottom, y)
            index += 2
        }
        return floatArrayOf(tLeft, tTop, tRight, tBottom)
    }

    /** How far [character] moves the next one along, in ems. */
    fun advance(design: DigitDesign, character: Char, showColon: Boolean): Float = when {
        character == ':' && !showColon -> NO_COLON_ADVANCE
        character == ':' -> design.colonWidth
        else -> design.width + 2f * design.sideBearing
    }

    /** The ink for [character], or null for a character the design does not draw. */
    fun glyph(design: DigitDesign, character: Char, showColon: Boolean): GlyphInk? {
        if (character == ':') return colon(design, showColon)
        if (character !in '0'..'9') return null
        if (design.pen is ClockPen.Dots) return dotMatrix(design, character)

        val (insetX, insetY) = design.pen.halfExtent()
        val w = design.width - 2f * insetX
        val h = design.height - 2f * insetY
        val builder = SkeletonBuilder(w, h, design.bowl, design.ballRadius(), insetX, insetY)
        builder.digit(character, design)
        val originX = design.sideBearing + insetX
        val originY = -design.height + insetY
        val strokes = builder.strokes.map { stroke ->
            val points = FloatArray(stroke.points.size)
            var index = 0
            while (index < points.size) {
                points[index] = originX + stroke.points[index]
                points[index + 1] = originY + stroke.points[index + 1]
                index += 2
            }
            InkStroke(points, stroke.closed)
        }
        val ballRadius = design.ballRadius()
        val dots = builder.balls.map { (x, y) -> InkDot(originX + x, originY + y, ballRadius, square = false) }
        // A playful face tips alternate digits a little either way.
        val rotation = if (design.wobbleDegrees != 0f) {
            if ((character - '0') % 2 == 0) design.wobbleDegrees else -design.wobbleDegrees
        } else {
            0f
        }
        return GlyphInk(
            strokes = strokes,
            dots = dots,
            pen = design.pen,
            skew = -tan(design.italicDegrees * DEG),
            rotationDegrees = rotation,
            pivotX = design.sideBearing + design.width / 2f,
            pivotY = -design.height / 2f
        )
    }

    /**
     * The parallel-line pen's lines, cut from the solid glyph by distance from
     * its outline: alternate bands, each [strokePx] / (2 [count] - 1) wide,
     * are kept. Taken from the merged glyph rather than stroke by stroke, the
     * lines follow its outline round every join instead of crossing where two
     * strokes overlap. [coverage] is the solid glyph, 0..1 per pixel; the
     * result is the lines' coverage, the same size.
     */
    fun lineBands(coverage: FloatArray, width: Int, height: Int, strokePx: Float, count: Int): FloatArray {
        // Measured well past the middle of one stroke: where two overlap the
        // glyph is thicker, and the lines carry on inwards rather than stopping
        // in a solid patch.
        val spread = strokePx * 2f + 1f
        val field = ClockDistanceField.build(coverage, width, height, spread)
        val band = strokePx / (2 * count - 1)
        return FloatArray(width * height) { index ->
            val solid = coverage[index]
            if (solid <= 0f) return@FloatArray 0f
            val depth = ((field[index] - 0.5f) * 2f * spread).coerceAtLeast(0f)
            val position = depth / band
            val bandIndex = position.toInt()
            val within = position - bandIndex
            val line = when {
                // The outermost line's outer edge is the glyph's own, already smoothed.
                bandIndex == 0 && within < 0.5f -> 1f
                bandIndex % 2 == 0 -> (0.5f + min(within, 1f - within) * band).coerceAtMost(1f)
                else -> (0.5f - min(within, 1f - within) * band).coerceAtLeast(0f)
            }
            min(solid, line)
        }
    }

    private fun colon(design: DigitDesign, showColon: Boolean): GlyphInk {
        if (!showColon) return GlyphInk(emptyList(), emptyList(), design.pen)
        val pen = design.pen
        val penRadius = when (pen) {
            is ClockPen.Mono -> pen.width * 0.62f
            is ClockPen.Nib -> pen.thick * 0.5f
            is ClockPen.Lines -> pen.width * 0.42f
            is ClockPen.Dots -> pen.pitch * DOT_RADIUS
        }
        val radius = max(penRadius, design.height * MIN_COLON_DOT)
        val x = design.colonWidth / 2f
        val dots = listOf(
            InkDot(x, -design.height * 0.7f, radius, design.squareDots),
            InkDot(x, -design.height * 0.28f, radius, design.squareDots)
        )
        return GlyphInk(
            strokes = emptyList(),
            dots = dots,
            pen = pen,
            skew = -tan(design.italicDegrees * DEG),
            pivotX = x,
            pivotY = -design.height / 2f
        )
    }

    private fun dotMatrix(design: DigitDesign, character: Char): GlyphInk {
        val pitch = (design.pen as ClockPen.Dots).pitch
        val rows = DOT_DIGITS[character - '0']
        val dots = ArrayList<InkDot>()
        for ((row, line) in rows.withIndex()) {
            for ((column, cell) in line.withIndex()) {
                if (cell != '#') continue
                dots += InkDot(
                    x = design.sideBearing + (column + 0.5f) * pitch,
                    y = -design.height + (row + 0.5f) * pitch,
                    radius = pitch * DOT_RADIUS,
                    square = design.squareDots
                )
            }
        }
        return GlyphInk(emptyList(), dots, design.pen)
    }

    private const val DEG = (PI / 180.0).toFloat()
    private const val DOT_RADIUS = 0.4f
    private const val NO_COLON_ADVANCE = 0.06f
    /** The smallest colon dot, as a share of the digit height: a hairline face's own would vanish. */
    private const val MIN_COLON_DOT = 0.045f

    /** A 5 by 7 grid per digit, top row first. */
    private val DOT_DIGITS = arrayOf(
        arrayOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        arrayOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        arrayOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        arrayOf("#####", "...#.", "..#..", "...#.", "....#", "#...#", ".###."),
        arrayOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        arrayOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        arrayOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        arrayOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        arrayOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        arrayOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..")
    )
}

/** How a digit's strokes are inked. Sizes are in ems. */
internal sealed class ClockPen {
    /** An even width all round, like a felt tip. */
    data class Mono(val width: Float, val roundEnds: Boolean = true, val sharpCorners: Boolean = false) : ClockPen()

    /** A broad nib held at [angleDegrees]: [thick] pulling down, [thin] pulling across. */
    data class Nib(val thick: Float, val thin: Float, val angleDegrees: Float = 0f) : ClockPen()

    /** [count] parallel lines along each stroke, evenly filling [width] with the gaps between. */
    data class Lines(val width: Float, val count: Int) : ClockPen()

    /** A grid of dots instead of strokes, [pitch] apart. */
    data class Dots(val pitch: Float) : ClockPen()

    /** The narrowest line this pen can leave. */
    fun thinnestStroke(): Float = when (this) {
        is Mono -> width
        is Nib -> thin
        is Lines -> width / (2 * count - 1)
        is Dots -> pitch * 0.8f
    }

    /** Half the pen's footprint across and down, which is how far ink reaches past a stroke's centre line. */
    fun halfExtent(): Pair<Float, Float> = when (this) {
        is Mono -> width / 2f to width / 2f
        is Lines -> width / 2f to width / 2f
        is Dots -> 0f to 0f
        is Nib -> {
            val a = thick / 2f
            val b = thin / 2f
            val angle = angleDegrees * (PI / 180.0).toFloat()
            sqrt((a * cos(angle)).pow(2) + (b * sin(angle)).pow(2)) to
                sqrt((a * sin(angle)).pow(2) + (b * cos(angle)).pow(2))
        }
    }
}

/** The shape every curved part of a digit is cut from. */
internal sealed class Bowl {
    /** A superellipse: [exponent] 2 is an ellipse, larger is squarer. */
    data class Oval(val exponent: Float = 2f) : Bowl()

    /** A box with rounded corners, [corner] as a fraction of the shorter half side: 1 is a stadium, 0 a rectangle. */
    data class Box(val corner: Float = 1f, val exponent: Float = 2f) : Bowl()
}

internal enum class SixTail { HOOK, DIAGONAL }

/** Everything that makes one drawn face differ from another. Sizes in ems. */
internal data class DigitDesign(
    val width: Float,
    val height: Float,
    val pen: ClockPen,
    val bowl: Bowl = Bowl.Oval(),
    val sideBearing: Float = 0.05f,
    val colonWidth: Float = 0.3f,
    val oneFlag: Boolean = true,
    val oneBase: Boolean = false,
    val openFour: Boolean = false,
    val sixTail: SixTail = SixTail.HOOK,
    val curvedTwo: Boolean = false,
    /** A squared, digital 2 for the boxy faces. */
    val boxyTwo: Boolean = false,
    val slashedZero: Boolean = false,
    /** Round blobs on the free ends of curves, the mark of the classic serif faces. */
    val balls: Boolean = false,
    /** Hairline feet and a top spur, also for the serif faces. */
    val serifs: Boolean = false,
    val italicDegrees: Float = 0f,
    val wobbleDegrees: Float = 0f,
    val squareDots: Boolean = false,
    /** Where 3 and 8 divide, as a fraction of the height from the top. */
    val split: Float = 0.47f
) {
    fun ballRadius(): Float = when (pen) {
        is ClockPen.Nib -> pen.thick * 0.56f
        is ClockPen.Mono -> pen.width * 0.7f
        is ClockPen.Lines -> pen.width * 0.5f
        is ClockPen.Dots -> 0f
    }
}

internal class InkStroke(val points: FloatArray, val closed: Boolean)

internal class InkDot(val x: Float, val y: Float, val radius: Float, val square: Boolean)

/** One glyph's ink: strokes for the pen, dots filled as they are, and a transform for both. */
internal class GlyphInk(
    val strokes: List<InkStroke>,
    val dots: List<InkDot>,
    val pen: ClockPen,
    /** Horizontal shear about [pivotY], the glyph's middle: x += skew * (y - pivotY). */
    val skew: Float = 0f,
    val rotationDegrees: Float = 0f,
    val pivotX: Float = 0f,
    val pivotY: Float = 0f
) {
    /** Where (x, y) lands once the italic shear and the tilt are applied. */
    fun transform(x: Float, y: Float): Pair<Float, Float> {
        var px = x
        var py = y
        if (rotationDegrees != 0f) {
            val angle = rotationDegrees * (PI / 180.0).toFloat()
            val dx = px - pivotX
            val dy = py - pivotY
            px = pivotX + dx * cos(angle) - dy * sin(angle)
            py = pivotY + dx * sin(angle) + dy * cos(angle)
        }
        return (px + skew * (py - pivotY)) to py
    }
}

/**
 * Builds one digit's centre lines inside a w by h box, origin top-left, y down.
 *
 * Arc angles are measured on the bowl squashed back to a circle, so 225° is
 * always the upper-left shoulder whatever the bowl's proportions: 0° right,
 * 90° down, 180° left, 270° up, sweeping clockwise on screen.
 */
private class SkeletonBuilder(
    val w: Float,
    val h: Float,
    val bowl: Bowl,
    val ballRadius: Float,
    /** How far ink reaches past the centre-line box, which a ball must stay within too. */
    val insetX: Float,
    val insetY: Float
) {
    /** Half the stroke: a nib is thick only pulling down, so it counts at its average. */
    private var halfStroke = max(insetX, insetY)

    /** How heavy the face is: its stroke over the centre-line height. Heavy faces need roomier shapes. */
    private val heaviness: Float get() = 2f * halfStroke / h
    class Stroke(val points: FloatArray, val closed: Boolean)

    val strokes = ArrayList<Stroke>()
    val balls = ArrayList<Pair<Float, Float>>()

    fun digit(character: Char, design: DigitDesign) {
        if (design.pen is ClockPen.Nib) halfStroke = (insetX + insetY) / 2f
        when (character) {
            '0' -> zero(design)
            '1' -> one(design)
            '2' -> two(design)
            '3' -> three(design)
            '4' -> four(design)
            '5' -> five(design)
            '6' -> six(design)
            '7' -> seven(design)
            '8' -> eight(design)
            '9' -> {
                six(design)
                // A 9 is a 6 turned half way round.
                strokes.replaceAll { stroke ->
                    val points = stroke.points.copyOf()
                    var index = 0
                    while (index < points.size) {
                        points[index] = w - points[index]
                        points[index + 1] = h - points[index + 1]
                        index += 2
                    }
                    Stroke(points, stroke.closed)
                }
                balls.replaceAll { (x, y) -> (w - x) to (h - y) }
            }
        }
    }

    private fun zero(design: DigitDesign) {
        ring(w / 2f, h / 2f, w / 2f, h / 2f)
        if (design.slashedZero) line(0f, h * 0.8f, w, h * 0.2f)
    }

    private fun one(design: DigitDesign) {
        val stem = w * 0.6f
        val points = ArrayList<Float>()
        if (design.oneFlag) points.add(stem - w * 0.42f, h * 0.22f)
        points.add(stem, 0f, stem, h)
        add(points)
        if (design.oneBase || design.serifs) line(stem - w * 0.34f, h, stem + w * 0.34f, h)
    }

    private fun two(design: DigitDesign) {
        if (design.boxyTwo) {
            // A digital 2: the upper box's right side, a bar back across, the left side down.
            val ry = h * design.split / 2f
            val points = arc(w / 2f, ry, w / 2f, ry, 205f, 450f)
            points.add(0f, h * design.split, 0f, h, w, h)
            add(points)
            return
        }
        val ry = h * 0.28f
        val points = arc(w / 2f, ry, w / 2f, ry, 200f, 390f)
        val endX = points[points.size - 2]
        val endY = points[points.size - 1]
        if (design.curvedTwo) {
            cubic(points, endX, endY, endX, endY + h * 0.2f, w * 0.12f, h * 0.74f, 0f, h)
        } else {
            points.add(0f, h)
        }
        points.add(w, h)
        add(points)
        if (design.balls) ball(points, start = true)
    }

    private fun three(design: DigitDesign) {
        val upperRy = h * design.split / 2f
        val upper = arc(w / 2f, upperRy, w * 0.46f, upperRy, 205f, 450f)
        add(upper)
        val lowerRy = h * (1f - design.split) / 2f
        val lower = ArrayList<Float>()
        // The short bar into the middle; parallel-line faces leave it out, it would cut across their lines.
        if (design.pen !is ClockPen.Lines && heaviness < HEAVY) lower.add(w * 0.3f, h * design.split)
        lower.addAll(arc(w / 2f, h * design.split + lowerRy, w / 2f, lowerRy, 270f, 515f))
        add(lower)
        if (design.balls) {
            ball(upper, start = true)
            ball(lower, start = false)
        }
    }

    private fun four(design: DigitDesign) {
        val bar = h * 0.66f
        val stem = w * 0.7f
        // The closed 4's counter is the triangle inside its diagonal, bar and
        // stem: its inscribed circle, less the stroke, is what stays open.
        val hypotenuse = sqrt(stem * stem + bar * bar)
        val counter = (stem + bar - hypotenuse) / 2f - halfStroke
        if (design.openFour || counter < h * 0.035f) {
            // Far enough right of the upright that the counter between them stays open.
            val openStem = (w * 0.06f + 3.6f * halfStroke).coerceIn(stem, w)
            add(arrayListOf(w * 0.06f, 0f, w * 0.06f, bar, w, bar))
            line(openStem, h * 0.3f, openStem, h)
        } else {
            add(arrayListOf(stem, 0f, 0f, bar, w, bar))
            line(stem, 0f, stem, h)
        }
        if (design.serifs) line(stem - w * 0.2f, h, stem + w * 0.2f, h)
    }

    private fun five(design: DigitDesign) {
        val ry = h * 0.32f
        val bowlPoints = arc(w / 2f, h - ry, w / 2f, ry, 236f, 515f)
        val points = arrayListOf(w * 0.92f, 0f, bowlPoints[0], 0f)
        points.addAll(bowlPoints)
        add(points)
        if (design.balls) ball(points, start = false)
    }

    private fun six(design: DigitDesign) {
        val loopRy = h * 0.31f
        ring(w / 2f, h - loopRy, w / 2f, loopRy)
        when (design.sixTail) {
            SixTail.HOOK -> {
                val points = arrayListOf(0f, h - loopRy)
                // A heavy hook that ran as far round as a light one would hang
                // over the loop and pinch a sliver of background between them.
                val reach = 325f - 40f * ((heaviness - 0.15f) / 0.2f).coerceIn(0f, 1f)
                points.addAll(arc(w / 2f, h / 2f, w / 2f, h / 2f, 180f, reach))
                add(points)
                // Only an even pen collides there; a nib's hook is a hairline where it passes the loop.
                if (heaviness >= BRACED && design.pen is ClockPen.Mono) {
                    // Heavy strokes leave only a wedge between the rising hook
                    // and the loop's shoulder, too narrow to read as a counter;
                    // a brace across it fills the crotch as a drawn face would.
                    val shoulder = arc(w / 2f, h - loopRy, w / 2f, loopRy, 232f, 232f)
                    line(0f, h - loopRy * 1.7f, shoulder[0], shoulder[1])
                }
                if (design.balls) ball(points, start = false)
            }
            SixTail.DIAGONAL -> {
                val start = arc(w / 2f, h - loopRy, w / 2f, loopRy, 200f, 200f)
                val points = arrayListOf(start[0], start[1], w * 0.8f, 0f)
                add(points)
                if (design.balls) ball(points, start = false)
            }
        }
    }

    private fun seven(design: DigitDesign) {
        add(arrayListOf(0f, 0f, w, 0f, w * 0.3f, h))
        if (design.serifs) line(0f, 0f, 0f, h * 0.14f)
    }

    private fun eight(design: DigitDesign) {
        val split = design.split - 0.01f
        val upperRy = h * split / 2f
        val lowerRy = h * (1f - split) / 2f
        ring(w / 2f, upperRy, w * 0.44f, upperRy)
        ring(w / 2f, h * split + lowerRy, w / 2f, lowerRy)
    }

    // --- primitives ---

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) = add(arrayListOf(x0, y0, x1, y1))

    private fun ring(cx: Float, cy: Float, rx: Float, ry: Float) {
        val outline = outline(rx, ry)
        val points = FloatArray(outline.size / 3 * 2)
        var target = 0
        var index = 0
        while (index < outline.size) {
            points[target++] = cx + outline[index + 1]
            points[target++] = cy + outline[index + 2]
            index += 3
        }
        strokes += Stroke(points, closed = true)
    }

    private fun add(points: List<Float>) {
        strokes += Stroke(points.toFloatArray(), closed = false)
    }

    /**
     * A ball on one end of [points], set back along the stroke so it does not
     * overhang the digit, with the stroke cut back to meet it: a nib's tip
     * left running past the ball pokes out of it as a spur.
     */
    private fun ball(points: List<Float>, start: Boolean) {
        val index = strokes.indexOfLast { stroke ->
            val p = stroke.points
            if (start) p[0] == points[0] && p[1] == points[1]
            else p[p.size - 2] == points[points.size - 2] && p[p.size - 1] == points[points.size - 1]
        }
        val source = if (index >= 0) strokes[index].points else points.toFloatArray()
        val path = if (start) source else reversedPairs(source)
        // Walk in from the end until the ball's set-back is used up.
        var left = ballRadius * 0.6f
        var cut = 0
        var x = path[0]
        var y = path[1]
        while (cut + 3 < path.size) {
            val nx = path[cut + 2]
            val ny = path[cut + 3]
            val step = sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y))
            if (step >= left && step > 0f) {
                x += (nx - x) * left / step
                y += (ny - y) * left / step
                break
            }
            left -= step
            x = nx
            y = ny
            cut += 2
        }
        val bx = x.coerceIn(ballRadius - insetX, w + insetX - ballRadius)
        val by = y.coerceIn(ballRadius - insetY, h + insetY - ballRadius)
        balls += bx to by
        if (index >= 0 && path.size - (cut + 2) >= 4) {
            val trimmed = FloatArray(path.size - (cut + 2) + 2)
            trimmed[0] = x
            trimmed[1] = y
            System.arraycopy(path, cut + 2, trimmed, 2, path.size - (cut + 2))
            strokes[index] = Stroke(if (start) trimmed else reversedPairs(trimmed), strokes[index].closed)
        }
    }

    private fun reversedPairs(points: FloatArray): FloatArray {
        val out = FloatArray(points.size)
        var index = 0
        while (index < points.size) {
            out[points.size - 2 - index] = points[index]
            out[points.size - 1 - index] = points[index + 1]
            index += 2
        }
        return out
    }

    private fun cubic(
        into: ArrayList<Float>,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float
    ) {
        for (step in 1..CURVE_STEPS) {
            val t = step / CURVE_STEPS.toFloat()
            val u = 1f - t
            into.add(
                u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3,
                u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3
            )
        }
    }

    /** The bowl's rim from [from]° to [to]° (to may pass 360), centred on (cx, cy). */
    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, from: Float, to: Float): ArrayList<Float> {
        val outline = outline(rx, ry)
        val start = ((from % 360f) + 360f) % 360f
        val end = start + (to - from)
        val points = ArrayList<Float>()
        points.addAll(pointAt(outline, start).let { listOf(cx + it.first, cy + it.second) })
        for (lap in 0..1) {
            var index = 0
            while (index < outline.size) {
                val angle = outline[index] + 360f * lap
                if (angle > start && angle < end) points.add(cx + outline[index + 1], cy + outline[index + 2])
                index += 3
            }
        }
        if (end > start) points.addAll(pointAt(outline, end % 360f).let { listOf(cx + it.first, cy + it.second) })
        return points
    }

    /** The rim point at [angle]°, interpolated between the outline's samples. */
    private fun pointAt(outline: FloatArray, angle: Float): Pair<Float, Float> {
        val count = outline.size / 3
        for (sample in 0 until count) {
            val a0 = outline[sample * 3]
            val next = (sample + 1) % count
            var a1 = outline[next * 3]
            if (a1 < a0) a1 += 360f
            var target = angle
            if (target < a0) target += 360f
            if (target in a0..a1) {
                val t = if (a1 > a0) (target - a0) / (a1 - a0) else 0f
                return (outline[sample * 3 + 1] + (outline[next * 3 + 1] - outline[sample * 3 + 1]) * t) to
                    (outline[sample * 3 + 2] + (outline[next * 3 + 2] - outline[sample * 3 + 2]) * t)
            }
        }
        return outline[1] to outline[2]
    }

    /**
     * The whole rim as (angle, x, y) triples, angle rising from 0 to 360,
     * centred on the origin.
     */
    private fun outline(rx: Float, ry: Float): FloatArray {
        val raw = ArrayList<Float>()
        when (bowl) {
            is Bowl.Oval -> {
                val power = 2f / bowl.exponent
                for (step in 0 until RIM_STEPS) {
                    val t = step * 2.0 * PI / RIM_STEPS
                    val c = cos(t).toFloat()
                    val s = sin(t).toFloat()
                    raw.add(rx * sign(c) * abs(c).pow(power), ry * sign(s) * abs(s).pow(power))
                }
            }
            is Bowl.Box -> {
                val corner = bowl.corner.coerceIn(0f, 1f) * min(rx, ry)
                val ix = rx - corner
                val iy = ry - corner
                val power = 2f / bowl.exponent
                fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
                    for (step in 0 until EDGE_STEPS) {
                        val t = step / EDGE_STEPS.toFloat()
                        raw.add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
                    }
                }
                fun quarter(cx: Float, cy: Float, fromDegrees: Int) {
                    if (corner <= 0f) {
                        raw.add(cx, cy)
                        return
                    }
                    for (step in 0 until CORNER_STEPS) {
                        val t = (fromDegrees + 90.0 * step / CORNER_STEPS) * PI / 180.0
                        val c = cos(t).toFloat()
                        val s = sin(t).toFloat()
                        raw.add(cx + corner * sign(c) * abs(c).pow(power), cy + corner * sign(s) * abs(s).pow(power))
                    }
                }
                edge(rx, 0f, rx, iy)
                quarter(ix, iy, 0)
                edge(ix, ry, -ix, ry)
                quarter(-ix, iy, 90)
                edge(-rx, iy, -rx, -iy)
                quarter(-ix, -iy, 180)
                edge(-ix, -ry, ix, -ry)
                quarter(ix, -iy, 270)
                edge(rx, -iy, rx, 0f)
            }
        }
        val out = FloatArray(raw.size / 2 * 3)
        var index = 0
        var target = 0
        while (index < raw.size) {
            val x = raw[index]
            val y = raw[index + 1]
            val angle = ((atan2(y / ry, x / rx) * 180.0 / PI).toFloat() + 360f) % 360f
            out[target++] = angle
            out[target++] = x
            out[target++] = y
            index += 2
        }
        return out
    }

    private fun ArrayList<Float>.add(vararg values: Float) {
        for (value in values) add(value)
    }

    private companion object {
        const val RIM_STEPS = 720
        const val EDGE_STEPS = 60
        const val CORNER_STEPS = 45
        const val CURVE_STEPS = 24
        /** Past this, a face counts as heavy: see [heaviness]. */
        const val HEAVY = 0.25f
        /** Past this, a 6's hook is braced into its loop. */
        const val BRACED = 0.22f
    }
}
