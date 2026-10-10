package com.app.nosatmosphereeffect.helper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Where the clock's digits come from: a system typeface, or a face drawn by
 * [ClockDigitGeometry]. The glyph atlas and the face layout measure and draw
 * through this and do not care which.
 *
 * Every size is in pixels at the given em, relative to the glyph's origin on
 * the baseline, y down. Any stretch a face has is already applied.
 */
internal interface ClockGlyphSource {
    /** How far the face is stretched tall, already baked into its glyphs; 1 for faces drawn at their own height. */
    val verticalStretch: Float get() = 1f

    /** The distance field's reach for this face, in ems; see [ClockGlyphAtlas.SPREAD_EM]. */
    val spreadEm: Float get() = ClockGlyphAtlas.SPREAD_EM

    fun advance(character: Char, em: Float): Float

    /** The line box above the baseline (negative) and below it. */
    fun lineTop(em: Float): Float
    fun lineBottom(em: Float): Float

    fun ink(character: Char, em: Float, out: RectF)

    /** Draws [character] in white with its origin at ([x], [baseline]). */
    fun draw(canvas: Canvas, character: Char, x: Float, baseline: Float, em: Float)

    companion object {
        /**
         * Only the Normal look draws in a chosen font; the glass looks keep
         * their own typefaces. The colon is everyone's to hide.
         */
        fun of(style: ClockStyle, font: ClockFont): ClockGlyphSource {
            val digits = font.design.digits
            return when {
                !style.takesFont || font.isDefault -> TypefaceGlyphs(style, font.showColon)
                font.design == ClockDesign.HANZI -> HanziGlyphs(font.showColon)
                digits != null -> DrawnGlyphs(
                    when (style.treatment) {
                        ClockTreatment.TRANSLUCENT ->
                            ClockDigitGeometry.forGlass(digits, ClockDigitGeometry.TRANSLUCENT_MIN_STROKE)
                        ClockTreatment.GLASS -> ClockDigitGeometry.forGlass(digits)
                        else -> digits
                    },
                    font.showColon
                )
                else -> TypefaceGlyphs(style, font.showColon)
            }
        }
    }
}

/** The system faces: one typeface per look, stretched tall. */
private class TypefaceGlyphs(
    private val look: ClockStyle,
    private val showColon: Boolean
) : ClockGlyphSource {
    override val verticalStretch: Float get() = look.verticalStretch

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = look.typeface()
    }
    private val bounds = Rect()

    private fun at(em: Float): Paint = paint.apply {
        textSize = em
        letterSpacing = look.letterSpacingEm
        textScaleX = look.horizontalScale
    }

    private fun hidden(character: Char) = character == ':' && !showColon

    override fun advance(character: Char, em: Float): Float =
        if (hidden(character)) em * HIDDEN_COLON_EM else at(em).measureText(character.toString())

    override fun lineTop(em: Float): Float = at(em).fontMetrics.top * look.verticalStretch

    override fun lineBottom(em: Float): Float = at(em).fontMetrics.bottom * look.verticalStretch

    override fun ink(character: Char, em: Float, out: RectF) {
        if (hidden(character)) {
            out.setEmpty()
            return
        }
        at(em).getTextBounds(character.toString(), 0, 1, bounds)
        val stretch = look.verticalStretch
        out.set(bounds.left.toFloat(), bounds.top * stretch, bounds.right.toFloat(), bounds.bottom * stretch)
    }

    override fun draw(canvas: Canvas, character: Char, x: Float, baseline: Float, em: Float) {
        if (hidden(character)) return
        val text = character.toString()
        val advance = at(em).measureText(text)
        canvas.save()
        // The stretch is baked into the tile rather than applied when it is
        // drawn: a distance field survives being scaled evenly, but
        // stretching one axis stops it measuring distance.
        canvas.scale(1f, look.verticalStretch, x + advance / 2f, baseline)
        canvas.drawText(text, x, baseline, paint)
        canvas.restore()
    }
}

/** What a hidden colon leaves between hours and minutes, in ems. */
private const val HIDDEN_COLON_EM = 0.06f

/**
 * A face drawn from scratch: see [ClockDigitGeometry].
 *
 * Every design is drawn at the same digit height as the system faces, so a
 * font change neither shrinks the rasterised digits nor changes how much of
 * a stroke the glass bevel takes.
 */
private class DrawnGlyphs(private val design: DigitDesign, private val showColon: Boolean) : ClockGlyphSource {
    private val glyphs = HashMap<Char, GlyphInk?>()
    private val scale = ClockDigitGeometry.DIGIT_HEIGHT_EM / design.height

    override val spreadEm: Float = ClockDigitGeometry.spreadFor(design)

    private fun inkOf(character: Char): GlyphInk? =
        glyphs.getOrPut(character) { ClockDigitGeometry.glyph(design, character, showColon) }

    override fun advance(character: Char, em: Float): Float =
        ClockDigitGeometry.advance(design, character, showColon) * em * scale

    override fun lineTop(em: Float): Float = -(design.height + LINE_PAD_EM) * em * scale

    override fun lineBottom(em: Float): Float = LINE_PAD_EM * em * scale

    override fun ink(character: Char, em: Float, out: RectF) {
        val ink = inkOf(character)
        if (ink == null) {
            out.setEmpty()
            return
        }
        val size = em * scale
        val box = ClockDigitGeometry.bounds(ink)
        out.set(box[0] * size, box[1] * size, box[2] * size, box[3] * size)
    }

    override fun draw(canvas: Canvas, character: Char, x: Float, baseline: Float, em: Float) {
        inkOf(character)?.let { ClockInkPainter.draw(canvas, it, x, baseline, em * scale) }
    }

    private companion object {
        /** Room above and below the digits for a slant or a tilt to reach into. */
        const val LINE_PAD_EM = 0.12f
    }
}

/** Chinese financial numerals, drawn with the phone's own CJK font. */
private class HanziGlyphs(private val showColon: Boolean) : ClockGlyphSource {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bounds = Rect()

    private fun textOf(character: Char): String = when (character) {
        in '0'..'9' -> NUMERALS[character - '0'].toString()
        ':' -> if (showColon) ":" else ""
        else -> character.toString()
    }

    override fun advance(character: Char, em: Float): Float {
        paint.textSize = em
        val text = textOf(character)
        return if (text.isEmpty()) em * HIDDEN_COLON_EM else paint.measureText(text)
    }

    override fun lineTop(em: Float): Float {
        paint.textSize = em
        return paint.fontMetrics.top
    }

    override fun lineBottom(em: Float): Float {
        paint.textSize = em
        return paint.fontMetrics.bottom
    }

    override fun ink(character: Char, em: Float, out: RectF) {
        val text = textOf(character)
        if (text.isEmpty()) {
            out.setEmpty()
            return
        }
        paint.textSize = em
        paint.getTextBounds(text, 0, text.length, bounds)
        out.set(bounds)
    }

    override fun draw(canvas: Canvas, character: Char, x: Float, baseline: Float, em: Float) {
        val text = textOf(character)
        if (text.isEmpty()) return
        paint.textSize = em
        canvas.drawText(text, x, baseline, paint)
    }

    private companion object {
        const val NUMERALS = "零壹貳參肆伍陸柒捌玖"
    }
}

/** Paints a [GlyphInk] on an Android canvas; the JVM tests mirror this for their specimen sheet. */
internal object ClockInkPainter {
    private const val MITER_LIMIT = 2f
    /** The largest off-screen mask the line pen will cut; larger is skipped rather than risked. */
    private const val MAX_LINE_PIXELS = 4_000_000L

    fun draw(canvas: Canvas, ink: GlyphInk, x: Float, baseline: Float, em: Float) {
        canvas.save()
        canvas.translate(x, baseline)
        canvas.scale(em, em)
        if (ink.skew != 0f) {
            canvas.translate(0f, ink.pivotY)
            canvas.skew(ink.skew, 0f)
            canvas.translate(0f, -ink.pivotY)
        }
        if (ink.rotationDegrees != 0f) canvas.rotate(ink.rotationDegrees, ink.pivotX, ink.pivotY)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        when (val pen = ink.pen) {
            is ClockPen.Mono -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = pen.width
                paint.strokeCap = if (pen.roundEnds) Paint.Cap.ROUND else Paint.Cap.BUTT
                paint.strokeJoin = if (pen.sharpCorners) Paint.Join.MITER else Paint.Join.ROUND
                paint.strokeMiter = MITER_LIMIT
                ink.strokes.forEach { canvas.drawPath(pathOf(it), paint) }
            }
            is ClockPen.Lines -> drawLines(canvas, ink, pen, paint)
            is ClockPen.Nib -> {
                paint.style = Paint.Style.FILL
                ink.strokes.forEach { canvas.drawPath(nibPath(it, pen), paint) }
            }
            is ClockPen.Dots -> Unit
        }

        paint.style = Paint.Style.FILL
        paint.xfermode = null
        for (dot in ink.dots) {
            if (dot.square) {
                canvas.drawRect(dot.x - dot.radius, dot.y - dot.radius, dot.x + dot.radius, dot.y + dot.radius, paint)
            } else {
                canvas.drawCircle(dot.x, dot.y, dot.radius, paint)
            }
        }
        canvas.restore()
    }

    /**
     * Parallel lines: the glyph drawn solid off-screen, then cut into lines by
     * distance from its outline — see [ClockDigitGeometry.lineBands] — and
     * laid back down where it belongs.
     */
    private fun drawLines(canvas: Canvas, ink: GlyphInk, pen: ClockPen.Lines, paint: Paint) {
        @Suppress("DEPRECATION")
        val matrix = Matrix(canvas.matrix)
        val box = ClockDigitGeometry.bounds(GlyphInk(ink.strokes, emptyList(), pen))
        val device = RectF(box[0], box[1], box[2], box[3])
        matrix.mapRect(device)
        val left = kotlin.math.floor(device.left).toInt() - 2
        val top = kotlin.math.floor(device.top).toInt() - 2
        val width = kotlin.math.ceil(device.right).toInt() + 2 - left
        val height = kotlin.math.ceil(device.bottom).toInt() + 2 - top
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_LINE_PIXELS) return
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        try {
            val maskCanvas = Canvas(mask)
            maskCanvas.setMatrix(Matrix(matrix).apply { postTranslate(-left.toFloat(), -top.toFloat()) })
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = pen.width
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            ink.strokes.forEach { maskCanvas.drawPath(pathOf(it), paint) }

            val stride = mask.rowBytes
            val bytes = ByteArray(stride * height)
            mask.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
            val coverage = FloatArray(width * height) { (bytes[(it / width) * stride + it % width].toInt() and 0xFF) / 255f }
            val scale = matrix.mapRadius(1f)
            val lines = ClockDigitGeometry.lineBands(coverage, width, height, pen.width * scale, pen.count)
            for (index in lines.indices) {
                bytes[(index / width) * stride + index % width] = (lines[index] * 255f).roundToInt().toByte()
            }
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

            canvas.save()
            canvas.setMatrix(Matrix())
            paint.style = Paint.Style.FILL
            canvas.drawBitmap(mask, left.toFloat(), top.toFloat(), paint)
            canvas.restore()
        } finally {
            mask.recycle()
        }
    }

    /** A broad nib dragged along the stroke: its footprint stamped close enough to read as one shape. */
    private fun nibPath(stroke: InkStroke, pen: ClockPen.Nib): Path {
        val nib = Path().apply {
            addOval(RectF(-pen.thick / 2f, -pen.thin / 2f, pen.thick / 2f, pen.thin / 2f), Path.Direction.CW)
            transform(Matrix().apply { setRotate(pen.angleDegrees) })
        }
        val out = Path()
        val points = stroke.points
        val step = pen.thin * 0.3f
        val count = points.size / 2 + if (stroke.closed) 1 else 0
        for (segment in 0 until count - 1) {
            val x0 = points[(segment * 2) % points.size]
            val y0 = points[(segment * 2 + 1) % points.size]
            val x1 = points[((segment + 1) * 2) % points.size]
            val y1 = points[((segment + 1) * 2 + 1) % points.size]
            val steps = (kotlin.math.hypot(x1 - x0, y1 - y0) / step).toInt().coerceAtLeast(1)
            for (index in 0..steps) {
                val t = index / steps.toFloat()
                out.addPath(nib, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
            }
        }
        return out
    }

    private fun pathOf(stroke: InkStroke): Path {
        val path = Path()
        val points = stroke.points
        path.moveTo(points[0], points[1])
        var index = 2
        while (index < points.size) {
            path.lineTo(points[index], points[index + 1])
            index += 2
        }
        if (stroke.closed) path.close()
        return path
    }
}
