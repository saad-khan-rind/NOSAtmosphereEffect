package com.app.nosatmosphereeffect.clock

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockDistanceFieldTest {

    private val width = 41
    private val height = 41
    private val spread = 8f

    /** A disc of radius 12 centred in the tile, with a hard edge. */
    private fun disc(radius: Float): FloatArray {
        val coverage = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Centred on a pixel, not between two: the tests below sample
                // by index and would read half a pixel off otherwise.
                val dx = (x - width / 2).toFloat()
                val dy = (y - height / 2).toFloat()
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                coverage[y * width + x] = if (distance <= radius) 1f else 0f
            }
        }
        return coverage
    }

    private fun fieldOf(radius: Float = 12f) =
        ClockDistanceField.build(disc(radius), width, height, spread)

    private fun at(field: FloatArray, x: Int, y: Int) = field[y * width + x]

    @Test
    fun `the centre of a shape is fully inside`() {
        val field = fieldOf()
        assertEquals(1f, at(field, width / 2, height / 2), 1e-4f)
    }

    @Test
    fun `far outside is fully outside`() {
        val field = fieldOf()
        assertEquals(0f, at(field, 0, 0), 1e-4f)
    }

    @Test
    fun `the edge sits at one half`() {
        // The shader reconstructs the silhouette where the field crosses 0.5,
        // so that crossing has to land between the last pixel of the shape and
        // the first one outside it. It is the one value the whole pipeline
        // depends on.
        val field = fieldOf(radius = 12f)
        val centre = width / 2
        val lastInside = at(field, centre + 12, centre)
        val firstOutside = at(field, centre + 13, centre)
        assertTrue("inside the shape read $lastInside", lastInside > 0.5f)
        assertTrue("outside the shape read $firstOutside", firstOutside < 0.5f)
        assertTrue("the crossing is not at the edge", abs(lastInside - 0.5f) < 0.12f)
    }

    @Test
    fun `the field falls off evenly with distance`() {
        // Half a spread in from the edge should read about half way between
        // the edge and the core: that linearity is what lets the shader treat
        // it as a thickness.
        val field = fieldOf(radius = 12f)
        val centre = width / 2
        val quarter = at(field, centre + 12 - (spread / 2f).toInt(), centre)
        assertTrue("half a spread in read $quarter", quarter in 0.68f..0.82f)
    }

    @Test
    fun `the field is symmetric about the shape`() {
        val field = fieldOf()
        val centre = width / 2
        for (offset in 1..16) {
            assertEquals(
                "asymmetric at $offset",
                at(field, centre + offset, centre),
                at(field, centre - offset, centre),
                1e-4f
            )
            assertEquals(
                "asymmetric at $offset",
                at(field, centre, centre + offset),
                at(field, centre + offset, centre),
                0.02f
            )
        }
    }

    @Test
    fun `distance is measured diagonally, not along the axes`() {
        // A city-block transform would put the diagonal at 1.41 times the
        // distance it really is, and the glass would bulge at the corners.
        val field = fieldOf(radius = 12f)
        val centre = width / 2
        val diagonal = at(field, centre + 9, centre + 9)  // ~12.7 from the centre
        assertTrue("diagonal read $diagonal", diagonal < 0.5f)
    }

    @Test
    fun `an empty tile has no shape in it`() {
        val field = ClockDistanceField.build(FloatArray(width * height), width, height, spread)
        assertTrue(field.all { it == 0f })
    }

    @Test
    fun `a solid tile is entirely inside`() {
        val coverage = FloatArray(width * height) { 1f }
        val field = ClockDistanceField.build(coverage, width, height, spread)
        assertTrue(field.all { it == 1f })
    }
}
