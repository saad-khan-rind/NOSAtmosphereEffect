package com.app.nosatmosphereeffect.activity

import com.app.nosatmosphereeffect.activity.FineTuneRetention.Plan
import org.junit.Assert.assertEquals
import org.junit.Test

class FineTuneRetentionTest {

    @Test
    fun `new images under the live effect keep fine tuning but switch the clock off`() {
        assertEquals(Plan.KEEP_CLOCK_OFF, FineTuneRetention.plan("COLOR_FILL", "COLOR_FILL", imagesChanged = true))
    }

    @Test
    fun `a different effect starts fresh even with the same images`() {
        assertEquals(Plan.RESET, FineTuneRetention.plan("COLOR_FILL", "GLASS", imagesChanged = false))
        assertEquals(Plan.RESET, FineTuneRetention.plan("COLOR_FILL", "GLASS", imagesChanged = true))
    }

    @Test
    fun `the same effect again with the same images keeps everything`() {
        assertEquals(Plan.KEEP, FineTuneRetention.plan("HALFTONE", "HALFTONE", imagesChanged = false))
    }

    @Test
    fun `setting a wallpaper when none of ours is live starts fresh`() {
        // The first time, or after another wallpaper was used in between.
        assertEquals(Plan.RESET, FineTuneRetention.plan(null, "ATMOSPHERE", imagesChanged = true))
        assertEquals(Plan.RESET, FineTuneRetention.plan(null, "ATMOSPHERE", imagesChanged = false))
    }

    @Test
    fun `forward and reverse versions count as different effects`() {
        assertEquals(Plan.RESET, FineTuneRetention.plan("ATMOSPHERE", "ATMOSPHERE_REVERSE", imagesChanged = false))
    }
}
