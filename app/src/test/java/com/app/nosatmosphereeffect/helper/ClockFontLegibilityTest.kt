package com.app.nosatmosphereeffect.helper

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every digit of every drawn face, put through what the wallpaper actually
 * does with it: rasterised at the atlas size, turned into a distance field,
 * then rebuilt the way the shaders rebuild it — as a solid silhouette (the
 * Normal look) and as the glass face's bevel (Glass), the face most likely to
 * lose a thin stroke.
 */
class ClockFontLegibilityTest {

    @Test
    fun `every digit survives the distance field as a solid face`() {
        forEachGlyph { name, character, tile ->
            val silhouette = tile.silhouette()
            val overlap = intersection(silhouette, tile.coverage) / max(union(silhouette, tile.coverage), 1f)
            assertTrue("$name '$character' rebuilt at only ${"%.2f".format(overlap)}", overlap > 0.9f)
        }
    }

    @Test
    fun `every digit keeps a lit rim under glass`() {
        val weak = ArrayList<String>()
        forEachGlyph(glass = true) { name, character, tile ->
            val lit = tile.litFraction()
            if (lit < MIN_LIT) weak += "$name '$character' ${"%.2f".format(lit)}"
        }
        assertTrue("Too little of these is lit under glass: $weak", weak.isEmpty())
    }

    /** Not an assertion: the glass and solid renderings side by side, in build/clock-glass.png. */
    @Test
    fun `draw a glass specimen sheet`() {
        val rows = drawnDesigns()
        val rowHeight = 150
        val image = BufferedImage(1600, rowHeight * rows.size, BufferedImage.TYPE_INT_RGB)
        rows.forEachIndexed { row, (name, design) ->
            var x = 4
            for (character in "0123456789:") {
                val tile = buildTile(ClockDigitGeometry.forGlass(design), character) ?: continue
                val scale = (rowHeight - 10f) / tile.height
                val glass = tile.glass()
                val width = (tile.width * scale).toInt()
                for (py in 0 until rowHeight - 10) {
                    for (px in 0 until width) {
                        val tx = (px / scale).toInt().coerceIn(0, tile.width - 1)
                        val ty = (py / scale).toInt().coerceIn(0, tile.height - 1)
                        val v = (glass[ty * tile.width + tx] * 255).toInt().coerceIn(0, 255)
                        if (x + px < image.width) image.setRGB(x + px, row * rowHeight + py, (v shl 16) or (v shl 8) or v)
                    }
                }
                x += width
            }
            image.graphics.apply {
                color = java.awt.Color.ORANGE
                drawString(name, 4, row * rowHeight + 12)
                dispose()
            }
        }
        ImageIO.write(image, "png", File("build/clock-glass.png"))
    }

    /** Not an assertion: chosen glyphs large, solid field on the left and glass on the right, in build/clock-zoom.png. */
    @Test
    fun `draw a zoom sheet`() {
        val picks = (System.getProperty("clock.zoom") ?: "POSTER:6,POSTER:3,DIDONE_HEAVY:6,BRUSH:4,DIDONE_LIGHT:2").split(",")
        val cell = 300
        val image = BufferedImage(cell * 2, cell * picks.size, BufferedImage.TYPE_INT_RGB)
        picks.forEachIndexed { row, pick ->
            val (name, char) = pick.split(":")
            val design = ClockDesign.valueOf(name).digits ?: return@forEachIndexed
            val tile = buildTile(design, char[0]) ?: return@forEachIndexed
            val glass = tile.glass()
            val scale = (cell - 10f) / max(tile.width, tile.height)
            for (py in 0 until cell) for (px in 0 until cell) {
                val tx = (px / scale).toInt()
                val ty = (py / scale).toInt()
                if (tx >= tile.width || ty >= tile.height) continue
                val f = tile.field[ty * tile.width + tx]
                val solid = if (f >= 0.5f) 255 else (f * 200).toInt()
                val g = (glass[ty * tile.width + tx] * 255).toInt().coerceIn(0, 255)
                image.setRGB(px, row * cell + py, (solid shl 16) or (solid shl 8) or solid)
                image.setRGB(cell + px, row * cell + py, (g shl 16) or (g shl 8) or g)
            }
        }
        ImageIO.write(image, "png", File("build/clock-zoom.png"))
    }

    // --- the pipeline ---

    private class Tile(val width: Int, val height: Int, val coverage: FloatArray, val field: FloatArray) {
        fun at(x: Float, y: Float): Float {
            val cx = x.coerceIn(0f, width - 1f)
            val cy = y.coerceIn(0f, height - 1f)
            val x0 = floor(cx).toInt()
            val y0 = floor(cy).toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val fx = cx - x0
            val fy = cy - y0
            val top = field[y0 * width + x0] * (1 - fx) + field[y0 * width + x1] * fx
            val bottom = field[y1 * width + x0] * (1 - fx) + field[y1 * width + x1] * fx
            return top * (1 - fy) + bottom * fy
        }

        fun silhouette(): FloatArray = FloatArray(width * height) { if (field[it] >= 0.5f) 1f else 0f }

        /** The glass face's rim, as the shader computes it: see clockGlass in atmosphere.frag. */
        fun rim(x: Int, y: Int): Float {
            val f = field[y * width + x]
            val gx = at(x + 1f, y.toFloat()) - at(x - 1f, y.toFloat())
            val gy = at(x.toFloat(), y + 1f) - at(x.toFloat(), y - 1f)
            val depth = ((f - 0.5f) * 2f).coerceIn(0f, 1f)
            val confidence = 1f - smoothstep(0.45f, 0.70f, depth)
            if (confidence <= 0.002f) return 0f
            val spreadTexels = (1f / max(hypot(gx, gy), 1e-4f)).coerceIn(2f, 48f)
            val reach = spreadTexels * 0.66f
            val aa = 0.5f / spreadTexels
            fun s(v: Float) = smoothstep(0.5f - aa, 0.5f + aa, v)
            val sx = 0.5f * (s(at(x + reach, y.toFloat())) - s(at(x - reach, y.toFloat())))
            val sy = 0.5f * (s(at(x.toFloat(), y + reach)) - s(at(x.toFloat(), y - reach)))
            val edge = (hypot(sx, sy) * confidence * 2f).coerceIn(0f, 1f)
            return smoothstep(0.12f, 0.85f, edge)
        }

        /**
         * How much of the outline catches the light. A stroke too thin for the
         * bevel leaves its whole outline dark, which is what makes it vanish;
         * a bold one is clear inside and bright at the edge, like the system face.
         */
        fun litFraction(): Float {
            var outline = 0
            var lit = 0
            for (y in 1 until height - 1) for (x in 1 until width - 1) {
                if (field[y * width + x] < 0.5f) continue
                val onEdge = field[y * width + x - 1] < 0.5f || field[y * width + x + 1] < 0.5f ||
                    field[(y - 1) * width + x] < 0.5f || field[(y + 1) * width + x] < 0.5f
                if (!onEdge) continue
                outline++
                if (rim(x, y) > 0.3f) lit++
            }
            return if (outline == 0) 1f else lit / outline.toFloat()
        }

        /** Grey wallpaper, clear glass, white rims: enough to see what reads. */
        fun glass(): FloatArray = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val background = 0.28f
            val f = field[index]
            val coverage = smoothstep(0.47f, 0.53f, f)
            if (coverage <= 0f) background else {
                val glass = background * 1.05f + 0.035f + rim(x, y) * 0.45f
                background + (glass - background) * coverage
            }
        }
    }

    private fun buildTile(design: DigitDesign, character: Char): Tile? {
        val ink = ClockDigitGeometry.glyph(design, character, showColon = true) ?: return null
        if (ink.strokes.isEmpty() && ink.dots.isEmpty()) return null
        // As DrawnGlyphs does it: every face at one digit height, in canonical ems.
        val size = CANONICAL_EM * ClockDigitGeometry.DIGIT_HEIGHT_EM / design.height
        val spread = ClockDigitGeometry.spreadFor(design) * CANONICAL_EM
        val advance = ClockDigitGeometry.advance(design, character, showColon = true) * size
        val box = ClockDigitGeometry.bounds(ink)
        val top = -(design.height + 0.12f) * size
        val width = ceil(max(advance, box[2] * size) + spread * 2).toInt()
        val height = ceil(0.12f * size - top + spread * 2).toInt()
        val baseline = spread - top
        val traceWidth = width * SUPERSAMPLE
        val traceHeight = height * SUPERSAMPLE
        val trace = BufferedImage(traceWidth, traceHeight, BufferedImage.TYPE_INT_ARGB)
        InkTestRenderer.draw(trace, ink, spread * SUPERSAMPLE, baseline * SUPERSAMPLE, size * SUPERSAMPLE)
        val traced = FloatArray(traceWidth * traceHeight) { (trace.getRGB(it % traceWidth, it / traceWidth) ushr 24) / 255f }
        val field = ClockDistanceField.build(traced, traceWidth, traceHeight, spread * SUPERSAMPLE)
        val down = FloatArray(width * height)
        val coverage = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            var f = 0f
            var c = 0f
            for (dy in 0 until SUPERSAMPLE) for (dx in 0 until SUPERSAMPLE) {
                val i = (y * SUPERSAMPLE + dy) * traceWidth + x * SUPERSAMPLE + dx
                f += field[i]
                c += traced[i]
            }
            down[y * width + x] = f / (SUPERSAMPLE * SUPERSAMPLE)
            coverage[y * width + x] = if (c / (SUPERSAMPLE * SUPERSAMPLE) >= 0.5f) 1f else 0f
        }
        return Tile(width, height, coverage, down)
    }

    private fun forEachGlyph(glass: Boolean = false, check: (String, Char, Tile) -> Unit) {
        drawnDesigns().forEach { (name, design) ->
            val drawn = if (glass) ClockDigitGeometry.forGlass(design) else design
            for (character in "0123456789:") buildTile(drawn, character)?.let { check(name, character, it) }
        }
    }

    private fun drawnDesigns(): List<Pair<String, DigitDesign>> =
        ClockDesign.entries.mapNotNull { design -> design.digits?.let { design.name to it } }

    private fun intersection(a: FloatArray, b: FloatArray) = a.indices.count { a[it] > 0.5f && b[it] > 0.5f }.toFloat()
    private fun union(a: FloatArray, b: FloatArray) = a.indices.count { a[it] > 0.5f || b[it] > 0.5f }.toFloat()

    private companion object {
        const val CANONICAL_EM = 256f
        const val SUPERSAMPLE = 2
        /** A glyph with less of its outline lit than this reads as a smear of clear glass. */
        const val MIN_LIT = 0.6f

        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
