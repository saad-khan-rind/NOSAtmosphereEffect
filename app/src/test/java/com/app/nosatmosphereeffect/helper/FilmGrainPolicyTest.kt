package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmGrainPolicyTest {

    @Test
    fun `the old default reads as very fine grain`() {
        val position = FilmGrainPolicy.sizePosition(FilmGrainPolicy.DEFAULT_SCALE)
        assertEquals("Very fine", FilmGrainPolicy.sizeLabel(position))
    }

    @Test
    fun `size positions round trip through the stored scale`() {
        for (position in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val scale = FilmGrainPolicy.scaleAt(position)
            assertEquals(position, FilmGrainPolicy.sizePosition(scale), 1e-4f)
        }
    }

    @Test
    fun `coarser positions mean fewer, larger grains`() {
        assertTrue(FilmGrainPolicy.scaleAt(1f) < FilmGrainPolicy.scaleAt(0f))
    }

    @Test
    fun `nonsense stored values fall back safely`() {
        assertEquals(0f, FilmGrainPolicy.sizePosition(Float.NaN), 0f)
        assertEquals(FilmGrainPolicy.DEFAULT_STRENGTH, FilmGrainPolicy.sanitizeStrength(Float.NaN), 0f)
        assertEquals(FilmGrainPolicy.MAX_STRENGTH, FilmGrainPolicy.sanitizeStrength(5f), 0f)
    }

    @Test
    fun `the default strength reads as medium`() {
        assertEquals("Medium", FilmGrainPolicy.strengthLabel(FilmGrainPolicy.DEFAULT_STRENGTH))
    }
}
