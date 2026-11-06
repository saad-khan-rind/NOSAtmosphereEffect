package com.app.nosatmosphereeffect.renderer.colorfill

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorFillRenderStateTest {
    @Test
    fun invalidAndOutOfRangeValuesAreSanitized() {
        val sanitized = ColorFillRenderState(
            progress = Float.NaN,
            dimLevel = 2f,
            originX = Float.POSITIVE_INFINITY,
            originY = -1f,
            scrollOffsetX = 3f,
            scrollWindowX = 0f
        ).sanitized()

        assertEquals(0f, sanitized.progress)
        assertEquals(1f, sanitized.dimLevel)
        assertEquals(0.5f, sanitized.originX)
        assertEquals(0f, sanitized.originY)
        assertEquals(1f, sanitized.scrollOffsetX)
        assertEquals(0.001f, sanitized.scrollWindowX)
    }
}
