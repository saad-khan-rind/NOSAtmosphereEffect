package com.app.nosatmosphereeffect.activity

import com.app.nosatmosphereeffect.activity.FineTuneRetention.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class FineTuneRetentionTest {

    @Test
    fun `new images under the live effect keep fine tuning but switch the clock off`() {
        assertEquals(Plan.KEEP_CLOCK_OFF, FineTuneRetention.plan("COLORFILL", "COLORFILL", imagesChanged = true))
    }

    @Test
    fun `a different effect starts fresh even with the same images`() {
        assertEquals(Plan.RESET, FineTuneRetention.plan("COLORFILL", "GLASS", imagesChanged = false))
        assertEquals(Plan.RESET, FineTuneRetention.plan("COLORFILL", "GLASS", imagesChanged = true))
    }

    @Test
    fun `the same effect again with the same images keeps everything`() {
        assertEquals(Plan.KEEP, FineTuneRetention.plan("HALFTONE", "HALFTONE", imagesChanged = false))
    }

    @Test
    fun `setting a wallpaper when none of ours is live starts fresh`() {
        // The first time, or after another wallpaper was used in between.
        assertEquals(Plan.RESET, FineTuneRetention.plan(null, "ORIGINAL", imagesChanged = true))
        assertEquals(Plan.RESET, FineTuneRetention.plan(null, "ORIGINAL", imagesChanged = false))
    }

    @Test
    fun `forward and reverse versions count as different effects`() {
        assertEquals(Plan.RESET, FineTuneRetention.plan("ORIGINAL", "REVERSE", imagesChanged = false))
    }
}
