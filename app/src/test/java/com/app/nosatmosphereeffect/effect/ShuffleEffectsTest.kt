package com.app.nosatmosphereeffect.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleEffectsTest {

    private val all = listOf("ATMOSPHERE", "ATMOSPHERE_REVERSE", "VHS", "VHS_REVERSE", "DUO", "SHUFFLE")

    /** Atmosphere runs photo first; VHS is the tape first, and its reverse the photo. */
    private val photoFirst = setOf("ATMOSPHERE", "VHS_REVERSE")

    private fun candidates(excluded: Set<String> = emptySet(), direction: ShuffleDirection = ShuffleDirection.RANDOM) =
        ShuffleEffects.candidates(all, excluded, direction) { it in photoFirst }

    @Test
    fun `shuffle never picks itself or duo`() {
        assertEquals(listOf("ATMOSPHERE", "ATMOSPHERE_REVERSE", "VHS", "VHS_REVERSE"), candidates())
    }

    @Test
    fun `a family switched off leaves both its directions out`() {
        assertEquals(listOf("ATMOSPHERE", "ATMOSPHERE_REVERSE"), candidates(excluded = setOf("VHS")))
    }

    @Test
    fun `a direction keeps the effects that run that way round`() {
        assertEquals(listOf("ATMOSPHERE", "VHS_REVERSE"), candidates(direction = ShuffleDirection.PHOTO_FIRST))
        assertEquals(listOf("ATMOSPHERE_REVERSE", "VHS"), candidates(direction = ShuffleDirection.EFFECT_FIRST))
    }

    @Test
    fun `with every family off something still shows`() {
        assertEquals(
            listOf(ShuffleEffects.FALLBACK_EFFECT_ID),
            candidates(excluded = setOf("ATMOSPHERE", "VHS"))
        )
    }

    @Test
    fun `the families offered leave out shuffle and duo`() {
        val keys = ShuffleEffects.families().map { it.key }
        assertFalse("SHUFFLE" in keys)
        assertFalse("DUO" in keys)
        assertTrue("ATMOSPHERE" in keys)
    }

    @Test
    fun `the next effect is another one whenever there is another`() {
        val three = listOf("ATMOSPHERE", "ZOOM", "VHS")
        repeat(50) { seed ->
            assertNotEquals("ZOOM", ShuffleEffects.pick(three, "ZOOM", Random(seed)))
        }
    }

    @Test
    fun `the only effect stays`() {
        assertEquals("ZOOM", ShuffleEffects.pick(listOf("ZOOM"), "ZOOM", Random(1)))
    }
}
