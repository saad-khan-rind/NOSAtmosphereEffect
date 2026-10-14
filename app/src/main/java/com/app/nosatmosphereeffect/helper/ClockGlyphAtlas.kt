package com.app.nosatmosphereeffect.helper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns glyphs into signed distance fields, and caches them.
 *
 * ## Why the clock is not drawn as glyphs any more
 *
 * The face texture used to hold plain coverage: 1 inside a digit, 0 outside,
 * with a pixel of anti-aliasing between. That is enough to *show* a digit and
 * not nearly enough to light one. Everything the glass needs — which way the
 * surface faces, how thick it is at this point, where the edge actually falls
 * when the texture is magnified — has to be recovered from that hard step, and
 * it cannot be. Reading it with a wide difference gave a flat band of constant
 * brightness (an outline, not a surface); reading it with a narrow one gave
 * nothing at all on small glyphs, which is why the date came out in patches
 * while the digits beside it looked fine.
 *
 * A distance field carries the answer directly. Each texel says how far it is
 * from the glyph's edge, normalised so that 0.5 is exactly on the edge and 1.0
 * is the middle of the stroke, whatever size the glyph was drawn at. From that
 * the shader gets a true surface normal (the gradient of a distance field is a
 * unit vector, everywhere), a thickness profile across the whole stroke rather
 * than a strip along its rim, and an edge it can reconstruct analytically — so
 * the clock stays crisp however far the texture is scaled up, instead of
 * showing the texels it was rasterised at.
 *
 * ## Why the tiles are cached at one size
 *
 * A distance field scales. One tile per character, rasterised once at
 * [CANONICAL_EM], is drawn at whatever size the layout asks for, which is what
 * keeps a drag of the date's box from re-rasterising the alphabet sixty times
 * a second.
 */
internal class ClockGlyphAtlas private constructor(
    private val style: ClockStyle,
    font: ClockFont
) {
    /** Where the digits come from; the date always uses the look's own typeface. */
    val source: ClockGlyphSource = ClockGlyphSource.of(style, font)

    /** One glyph, as a distance field, with where its ink sits inside the tile. */
    class Tile(
        val bitmap: Bitmap,
        /** Pixels from the tile's top to the glyph's baseline. */
        val baseline: Float,
        /** The glyph's own advance, for laying the row out. */
        val advance: Float,
        /** The ink box inside the tile, in tile pixels. */
        val inkLeft: Float,
        val inkTop: Float,
        val inkRight: Float,
        val inkBottom: Float,
        /** Where the glyph's origin sits from the tile's left edge: the spread its field was built with. */
        val origin: Float
    ) {
        val width: Float get() = bitmap.width.toFloat()
        val height: Float get() = bitmap.height.toFloat()
    }

    private val glyphs = HashMap<Char, Tile>()
    private var runs = HashMap<String, Tile>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }

    /** The spread the fields were built with, as a fraction of the em. */
    val spreadEm: Float get() = SPREAD_EM

    /**
     * The field for [character], built on first use.
     *
     * Returns null when the tile could not be rasterised — a glyph the device
     * has no outline for, or a bitmap it could not allocate. The caller draws
     * nothing rather than drawing something wrong.
     */
    fun glyph(character: Char): Tile? = synchronized(this) {
        glyphs[character]?.let { return it }
        val tile = buildGlyph(character)
        if (tile == null) {
            // The caller draws nothing and asks for another frame, so this is
            // recoverable — but it is also the only way a digit can go missing
            // from an otherwise working clock, so it says so.
            Log.w(TAG, "No field could be built for '$character'")
            return null
        }
        glyphs[character] = tile
        return tile
    }

    /**
     * The field for a whole line of text — the date, which is laid out as one
     * piece rather than per character because nothing animates within it.
     */
    fun run(text: String): Tile? = synchronized(this) {
        runs[text]?.let { return it }
        // Only ever a handful (the date changes once a day, and the format on
        // a locale change), but a clock that runs for months should not grow a
        // map for ever.
        if (runs.size > MAX_RUNS) runs = HashMap()
        val tile = buildRun(text) ?: return null
        runs[text] = tile
        return tile
    }

    // No release: the atlases are shared and live for the process. There are
    // only ever as many as there are faces, each about a megabyte, and the
    // alternative — one per renderer, rebuilt on every preview and every
    // thumbnail — is the same megabyte several times over plus the transform
    // that produced it.

    private fun configurePaint(em: Float) {
        paint.typeface = style.typeface()
        paint.textSize = em
        paint.letterSpacing = DATE_TRACKING_EM
        paint.textScaleX = 1f
    }

    private fun buildGlyph(character: Char): Tile? {
        val em = CANONICAL_EM
        val advance = source.advance(character, em)
        // A face's own spread: thin strokes need a narrower field, or the glass
        // bevel, which is measured off it, reaches clean across them.
        val spread = source.spreadEm * em
        val ink = RectF()
        source.ink(character, em, ink)
        // The tile is the line box, with room for the field to run out to its
        // full spread on every side — and wide enough for a slanted glyph
        // whose ink reaches past its own advance.
        val top = source.lineTop(em)
        val width = ceil(max(advance, ink.right) + spread * 2f).toInt().coerceAtLeast(1)
        val height = ceil(source.lineBottom(em) - top + spread * 2f).toInt().coerceAtLeast(1)
        val baseline = spread - top

        return rasterise(width, height, spread) { canvas ->
            source.draw(canvas, character, spread, baseline, em)
        }?.let { bitmap ->
            Tile(
                bitmap = bitmap,
                baseline = baseline,
                advance = advance,
                inkLeft = spread + ink.left,
                inkTop = baseline + ink.top,
                inkRight = spread + ink.right,
                inkBottom = baseline + ink.bottom,
                origin = spread
            )
        }
    }

    private fun buildRun(text: String): Tile? {
        if (text.isEmpty()) return null
        // Sized so its strokes carry the same share of the field as the
        // digits' do: the spread is a fraction of the em either way, so the
        // date is lit exactly like the clock rather than being swamped by a
        // bevel meant for a glyph ten times its size.
        configurePaint(RUN_EM)
        val advance = paint.measureText(text)
        if (advance <= 0f) return null
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        if (ink.isEmpty) return null
        val spread = SPREAD_EM * RUN_EM
        val width = ceil(advance + spread * 2f).toInt().coerceAtLeast(1)
        val metrics = paint.fontMetrics
        val height = ceil(metrics.bottom - metrics.top + spread * 2f).toInt().coerceAtLeast(1)
        val baseline = spread + (-metrics.top)

        return rasterise(width, height, spread) { canvas ->
            canvas.drawText(text, spread, baseline, paint)
        }?.let { bitmap ->
            Tile(
                bitmap = bitmap,
                baseline = baseline,
                advance = advance,
                inkLeft = spread + ink.left,
                inkTop = baseline + ink.top,
                inkRight = spread + ink.right,
                inkBottom = baseline + ink.bottom,
                origin = spread
            )
        }
    }

    /**
     * Draws a mask with [draw] and returns it as an ALPHA_8 distance field.
     *
     * The glyph is traced at [SUPERSAMPLE] times the tile's size and the
     * field is averaged back down. The transform measures distance in whole
     * pixels, and rounding the edge to the nearest one leaves about a third
     * of a pixel of error in the stored field — which is invisible at the
     * size the face was traced at and is not invisible once the shader has
     * magnified it two or three times over to fill a large clock. It showed
     * up as a ragged, low-resolution look along the outside of round strokes.
     * Tracing at twice the resolution halves that error, and averaging a
     * distance field is exact enough: it is smooth by construction, which is
     * the whole reason the shader can magnify it at all.
     */
    private fun rasterise(
        width: Int,
        height: Int,
        spread: Float,
        draw: (Canvas) -> Unit
    ): Bitmap? {
        if (width.toLong() * height > MAX_TILE_PIXELS) return null
        val traceWidth = width * SUPERSAMPLE
        val traceHeight = height * SUPERSAMPLE
        if (traceWidth.toLong() * traceHeight > MAX_TRACE_PIXELS) return null
        val trace = try {
            createBitmap(traceWidth, traceHeight, Bitmap.Config.ALPHA_8)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val tile = try {
            createBitmap(width, height, Bitmap.Config.ALPHA_8)
        } catch (_: OutOfMemoryError) {
            trace.recycle()
            return null
        }
        return try {
            val canvas = Canvas(trace)
            canvas.scale(SUPERSAMPLE.toFloat(), SUPERSAMPLE.toFloat())
            draw(canvas)
            val coverage = readAlpha(trace, traceWidth, traceHeight)
            val traced = ClockDistanceField.build(
                coverage = coverage,
                width = traceWidth,
                height = traceHeight,
                spread = spread * SUPERSAMPLE
            )
            writeAlpha(tile, downsample(traced, traceWidth, traceHeight, width, height))
            trace.recycle()
            tile
        } catch (_: RuntimeException) {
            trace.recycle()
            tile.recycle()
            null
        } catch (_: OutOfMemoryError) {
            trace.recycle()
            tile.recycle()
            null
        }
    }

    /** Averages [SUPERSAMPLE] by [SUPERSAMPLE] blocks of the traced field. */
    private fun downsample(
        field: FloatArray,
        traceWidth: Int,
        traceHeight: Int,
        width: Int,
        height: Int
    ): FloatArray {
        val out = FloatArray(width * height)
        val weight = 1f / (SUPERSAMPLE * SUPERSAMPLE)
        for (y in 0 until height) {
            val target = y * width
            for (x in 0 until width) {
                var total = 0f
                for (dy in 0 until SUPERSAMPLE) {
                    val row = (y * SUPERSAMPLE + dy).coerceAtMost(traceHeight - 1) * traceWidth
                    for (dx in 0 until SUPERSAMPLE) {
                        total += field[row + (x * SUPERSAMPLE + dx).coerceAtMost(traceWidth - 1)]
                    }
                }
                out[target + x] = total * weight
            }
        }
        return out
    }

    private fun readAlpha(bitmap: Bitmap, width: Int, height: Int): FloatArray {
        val buffer = ByteBuffer.allocate(bitmap.rowBytes * height)
        bitmap.copyPixelsToBuffer(buffer)
        val bytes = buffer.array()
        val stride = bitmap.rowBytes
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * stride
            val target = y * width
            for (x in 0 until width) {
                out[target + x] = (bytes[row + x].toInt() and 0xFF) / 255f
            }
        }
        return out
    }

    private fun writeAlpha(bitmap: Bitmap, field: FloatArray) {
        val width = bitmap.width
        val height = bitmap.height
        val stride = bitmap.rowBytes
        val bytes = ByteArray(stride * height)
        for (y in 0 until height) {
            val row = y * stride
            val source = y * width
            for (x in 0 until width) {
                val value = (field[source + x] * 255f).roundToInt().coerceIn(0, 255)
                bytes[row + x] = value.toByte()
            }
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    }

    companion object {
        private val shared = HashMap<Pair<ClockStyle, ClockFont>, ClockGlyphAtlas>()

        /**
         * The atlas for [style], built once for the whole process.
         *
         * The wallpaper renderer, the calibration preview and the style
         * thumbnails all want the same fields; building them per renderer
         * meant running the distance transform five times over for one clock.
         */
        fun of(style: ClockStyle, font: ClockFont = ClockFont.DEFAULT): ClockGlyphAtlas = synchronized(shared) {
            // Only the Normal look draws in the font; the others share one atlas per colon setting.
            val key = style to if (style.takesFont) font else ClockFont(ClockDesign.DEFAULT, font.showColon)
            shared.getOrPut(key) { ClockGlyphAtlas(key.first, key.second) }
        }

        /**
         * Em the tiles are rasterised at. Only the shape's fidelity depends on
         * it — the field itself is scale-free — so this trades a megabyte of
         * cache against how round a curve stays when the clock is set large.
         */
        const val CANONICAL_EM = 256f

        /**
         * The date's em; its own strokes get their own share of the field.
         *
         * Larger than the date is usually drawn at, because a date is sized
         * by dragging its box and can be made as big as the clock. The field
         * keeps the outline sharp at any magnification, but the shape itself
         * is only as round as the tile it was traced from — at 96 a date
         * enlarged a few times over started to show it in its curves.
         */
        const val RUN_EM = 160f

        /**
         * How far the field reaches either side of the edge, as a fraction of
         * the em. About half a stroke, so the field arrives at 1.0 in the
         * middle of a stroke and the shader's cross-section spans the whole
         * of it rather than a rim around it.
         */
        const val SPREAD_EM = 0.062f

        /** Matches ClockFaceRenderer's date tracking, so the tile measures the same. */
        private const val DATE_TRACKING_EM = 0.02f

        /** How much finer the glyph is traced than the tile it is stored in. */
        private const val SUPERSAMPLE = 2

        private const val TAG = "ClockGlyphAtlas"
        private const val MAX_RUNS = 8
        private const val MAX_TILE_PIXELS = 4_000_000L
        private const val MAX_TRACE_PIXELS = 16_000_000L
    }
}

/**
 * The distance transform itself, kept apart from anything that touches a
 * Bitmap so it can be tested.
 */
internal object ClockDistanceField {

    /**
     * Turns [coverage] (0..1 per pixel) into a field where 0.5 is the glyph's
     * edge, 1.0 is [spread] pixels or more inside it, and 0.0 is [spread]
     * pixels or more outside.
     */
    fun build(
        coverage: FloatArray,
        width: Int,
        height: Int,
        spread: Float
    ): FloatArray {
        require(coverage.size >= width * height) { "coverage is smaller than the tile" }
        val safeSpread = max(spread, 1e-3f)
        val inside = squaredDistance(coverage, width, height, invert = false)
        val outside = squaredDistance(coverage, width, height, invert = true)
        val field = FloatArray(width * height)
        for (index in 0 until width * height) {
            val solid = coverage[index] >= 0.5f
            val distance = if (solid) sqrt(inside[index]) else -sqrt(outside[index])
            // The mask is anti-aliased, so the edge rarely falls on a pixel
            // centre. Nudging by the part-covered pixel's own coverage puts it
            // back where it belongs, which is what stops a curve from wobbling
            // when the clock is drawn large.
            val subpixel = coverage[index] - 0.5f
            val refined = if (distance > -1.5f && distance < 1.5f) {
                distance + subpixel
            } else {
                distance
            }
            field[index] = (0.5f + 0.5f * (refined / safeSpread)).coerceIn(0f, 1f)
        }
        return field
    }

    /**
     * Squared distance from every pixel to the nearest pixel of the other
     * kind, by Felzenszwalb and Huttenlocher's transform: two passes of an
     * exact one-dimensional transform, which is linear in the pixel count
     * rather than quadratic like the naive search.
     */
    private fun squaredDistance(
        coverage: FloatArray,
        width: Int,
        height: Int,
        invert: Boolean
    ): FloatArray {
        val grid = FloatArray(width * height)
        for (index in 0 until width * height) {
            val solid = coverage[index] >= 0.5f
            // Seeds are the pixels we measure the distance TO.
            val seed = if (invert) solid else !solid
            grid[index] = if (seed) 0f else INFINITY
        }

        val size = max(width, height)
        val line = FloatArray(size)
        val result = FloatArray(size)
        val hull = IntArray(size)
        val breaks = FloatArray(size + 1)

        for (x in 0 until width) {
            for (y in 0 until height) line[y] = grid[y * width + x]
            transform(line, height, result, hull, breaks)
            for (y in 0 until height) grid[y * width + x] = result[y]
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) line[x] = grid[row + x]
            transform(line, width, result, hull, breaks)
            for (x in 0 until width) grid[row + x] = result[x]
        }
        return grid
    }

    /** One dimension of the transform: the lower envelope of the parabolas. */
    private fun transform(
        f: FloatArray,
        n: Int,
        d: FloatArray,
        v: IntArray,
        z: FloatArray
    ) {
        if (n == 0) return
        var k = 0
        v[0] = 0
        z[0] = -INFINITY
        z[1] = INFINITY
        for (q in 1 until n) {
            var s = intersection(f, q, v[k])
            // Guarded at 0 rather than run off the bottom: a column with no
            // seed in it at all makes every intersection -infinity, and
            // popping past the first parabola indexes off the front.
            while (k > 0 && s <= z[k]) {
                k--
                s = intersection(f, q, v[k])
            }
            if (s <= z[k]) {
                // This parabola is below all the others everywhere.
                k = 0
                v[0] = q
                z[0] = -INFINITY
                z[1] = INFINITY
            } else {
                k++
                v[k] = q
                z[k] = s
                z[k + 1] = INFINITY
            }
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val offset = (q - v[k]).toFloat()
            d[q] = offset * offset + f[v[k]]
        }
    }

    private fun intersection(f: FloatArray, q: Int, p: Int): Float {
        val fq = f[q]
        val fp = f[p]
        // Compared with >=, not ==: the second pass adds an offset to the
        // "no seed here" value carried over from the first.
        if (fq >= INFINITY) return INFINITY
        if (fp >= INFINITY) return -INFINITY
        val qf = q.toFloat()
        val pf = p.toFloat()
        return ((fq + qf * qf) - (fp + pf * pf)) / (2f * qf - 2f * pf)
    }

    /** Large enough to stand in for "no seed anywhere", small enough to square. */
    private const val INFINITY = 1e12f
}
