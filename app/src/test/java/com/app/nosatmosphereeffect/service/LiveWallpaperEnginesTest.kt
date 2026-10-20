package com.app.nosatmosphereeffect.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveWallpaperEnginesTest {

    @Test
    fun `the newest running engine answers, and none once all are gone`() {
        val home = Any()
        val lock = Any()
        assertNull(LiveWallpaperEngines.effectId())
        LiveWallpaperEngines.add(home, "FROSTED")
        LiveWallpaperEngines.add(lock, "HALFTONE")
        assertEquals("HALFTONE", LiveWallpaperEngines.effectId())
        LiveWallpaperEngines.remove(lock)
        assertEquals("FROSTED", LiveWallpaperEngines.effectId())
        LiveWallpaperEngines.remove(home)
        assertNull(LiveWallpaperEngines.effectId())
    }
}
