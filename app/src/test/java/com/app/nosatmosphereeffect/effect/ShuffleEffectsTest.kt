package com.app.nosatmosphereeffect.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleEffectsTest {

    private val all = listOf("ATMOSPHERE", "ATMOSPHERE_REVERSE", "ZOOM", "ZOOM_REVERSE", "DUO", "SHUFFLE")

    @Test
    fun `shuffle never picks itself`() {
        assertFalse("SHUFFLE" in ShuffleEffects.candidates(all, emptySet(), duoReady = true))
    }

    @Test
    fun `a family switched off leaves both its directions out`() {
        val candidates = ShuffleEffects.candidates(all, setOf("ZOOM"), duoReady = true)
        assertEquals(listOf("ATMOSPHERE", "ATMOSPHERE_REVERSE", "DUO"), candidates)
    }

    @Test
    fun `duo joins only once it has its second photo`() {
        assertFalse("DUO" in ShuffleEffects.candidates(all, emptySet(), duoReady = false))
        assertTrue("DUO" in ShuffleEffects.candidates(all, emptySet(), duoReady = true))
    }

    @Test
    fun `with every family off something still shows`() {
        val candidates = ShuffleEffects.candidates(all, setOf("ATMOSPHERE", "ZOOM", "DUO"), duoReady = true)
        assertEquals(listOf(ShuffleEffects.FALLBACK_EFFECT_ID), candidates)
    }

    @Test
    fun `the next effect is another one whenever there is another`() {
        val candidates = listOf("ATMOSPHERE", "ZOOM", "VHS")
        repeat(50) { seed ->
            assertNotEquals("ZOOM", ShuffleEffects.pick(candidates, "ZOOM", Random(seed)))
        }
    }

    @Test
    fun `the only effect stays`() {
        assertEquals("ZOOM", ShuffleEffects.pick(listOf("ZOOM"), "ZOOM", Random(1)))
    }
}
