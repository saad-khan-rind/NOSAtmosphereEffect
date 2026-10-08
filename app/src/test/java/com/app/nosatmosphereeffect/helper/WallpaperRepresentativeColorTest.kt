package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WallpaperRepresentativeColorTest {

    private fun hueOf(rgb: Int): Float {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val delta = high - low
        if (delta < 1e-6f) return 0f
        val hue = when (high) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return (hue + 360f) % 360f
    }

    private fun isGrey(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return abs(r - g) <= 2 && abs(g - b) <= 2
    }

    private val skyBlue = 0xFF4A90D9.toInt()
    private val brick = 0xFFB0402A.toInt()
    private val teal = 0xFF2AA6A0.toInt()

    @Test
    fun `the colour covering most of the picture leads`() {
        // A big blue sky and a small red roof reads as blue, not as the accent.
        val color = WallpaperColorExtractor.representativeColor(
            listOf(skyBlue to 9_000, brick to 800)
        )!!
        assertEquals(hueOf(skyBlue), hueOf(color), 25f)
    }

    @Test
    fun `opposite colours fall back to the larger one instead of a hue in between`() {
        val color = WallpaperColorExtractor.representativeColor(
            listOf(brick to 5_000, teal to 5_200)
        )!!
        assertEquals(hueOf(teal), hueOf(color), 1f)
    }

    @Test
    fun `a black and white picture gives a neutral colour`() {
        val color = WallpaperColorExtractor.representativeColor(
            listOf(0xFF202020.toInt() to 4_000, 0xFFDDDDDD.toInt() to 6_000, 0xFF808080.toInt() to 3_000)
        )!!
        assertTrue(isGrey(color))
    }

    @Test
    fun `no swatches means no colour`() {
        assertNull(WallpaperColorExtractor.representativeColor(emptyList()))
    }

    @Test
    fun `a nearly grey picture keeps its faint cast`() {
        // A dark misty forest: almost grey, slightly blue, with a few small
        // red sparks. The clock should come out blue, not grey or red.
        val color = WallpaperColorExtractor.representativeColor(
            listOf(
                0xFF4A4B52.toInt() to 9_000,
                0xFF2E3036.toInt() to 7_000,
                0xFF8E9096.toInt() to 3_000,
                0xFFD2452A.toInt() to 150
            )
        )!!
        assertTrue(!isGrey(color))
        val hue = hueOf(color)
        assertTrue("hue $hue", hue in 200f..250f)
    }
}
