package com.app.nosatmosphereeffect.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FineTuneRetentionTest {

    @Test
    fun `a new image under the running effect keeps fine tuning`() {
        assertTrue(FineTuneRetention.decide("COLORFILL", null, "COLORFILL", whenUnknown = false))
    }

    @Test
    fun `a different effect starts fresh`() {
        assertFalse(FineTuneRetention.decide("COLORFILL", "COLORFILL", "GLASS", whenUnknown = true))
    }

    @Test
    fun `the live wallpaper outranks the stored effect`() {
        // Switched effects through the system picker since the last apply.
        assertFalse(FineTuneRetention.decide("GLASS", "COLORFILL", "COLORFILL", whenUnknown = false))
        assertTrue(FineTuneRetention.decide("GLASS", "COLORFILL", "GLASS", whenUnknown = false))
    }

    @Test
    fun `the stored effect decides when another wallpaper is live`() {
        assertTrue(FineTuneRetention.decide(null, "HALFTONE", "HALFTONE", whenUnknown = false))
        assertFalse(FineTuneRetention.decide(null, "HALFTONE", "NEON", whenUnknown = true))
    }

    @Test
    fun `with nothing to go on the caller decides`() {
        // A first install starts fresh; editing an existing playlist keeps what it had.
        assertFalse(FineTuneRetention.decide(null, null, "ORIGINAL", whenUnknown = false))
        assertTrue(FineTuneRetention.decide(null, null, "ORIGINAL", whenUnknown = true))
    }
}
