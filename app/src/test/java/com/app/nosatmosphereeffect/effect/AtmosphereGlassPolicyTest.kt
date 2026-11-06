package com.app.nosatmosphereeffect.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphereGlassPolicyTest {

    @Test
    fun `only the two atmosphere directions support image glass`() {
        assertTrue(AtmosphereGlassPolicy.supportsEffect("ORIGINAL"))
        assertTrue(AtmosphereGlassPolicy.supportsEffect("REVERSE"))

        listOf(
            null,
            "",
            "GLASS",
            "GLASS_REVERSE",
            "FROSTED",
            "COLORFILL",
            "NEON",
            "HALFTONE",
            "NOT_AN_EFFECT"
        ).forEach { effectId ->
            assertFalse(
                "$effectId unexpectedly supports Atmosphere image glass",
                AtmosphereGlassPolicy.supportsEffect(effectId)
            )
        }
    }

    @Test
    fun `requested state is forced off for unsupported effects`() {
        assertTrue(AtmosphereGlassPolicy.resolveEnabled("ORIGINAL", requested = true))
        assertTrue(AtmosphereGlassPolicy.resolveEnabled("REVERSE", requested = true))
        assertFalse(AtmosphereGlassPolicy.resolveEnabled("ORIGINAL", requested = false))
        assertFalse(AtmosphereGlassPolicy.resolveEnabled("GLASS", requested = true))
        assertFalse(AtmosphereGlassPolicy.resolveEnabled(null, requested = true))
    }

    @Test
    fun `preference key remains stable`() {
        assertEquals("atmosphere_glass_enabled", AtmosphereGlassPolicy.ENABLED_KEY)
    }
}
