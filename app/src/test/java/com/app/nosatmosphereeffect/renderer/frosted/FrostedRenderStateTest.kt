package com.app.nosatmosphereeffect.renderer.frosted

import org.junit.Assert.assertEquals
import org.junit.Test

class FrostedRenderStateTest {
    @Test
    fun sanitizationKeepsNativeAndOpenGlInputsInTheSameRange() {
        val state = FrostedRenderState(
            progress = 4f,
            dimLevel = Float.NaN,
            noiseScale = -1f,
            noiseStrength = Float.POSITIVE_INFINITY,
            blurRadius = 205.6f,
            drawerBlur = -2f
        ).sanitized()

        assertEquals(1f, state.progress, 0f)
        assertEquals(0.2f, state.dimLevel, 0f)
        assertEquals(0f, state.noiseScale, 0f)
        assertEquals(0.06f, state.noiseStrength, 0f)
        assertEquals(206f, state.blurRadius, 0f)
        assertEquals(206, state.blurRadiusPixels)
        assertEquals(0f, state.drawerBlur, 0f)
    }

    @Test
    fun radiusIsBoundedToTheFineTuneSliderContract() {
        assertEquals(
            0,
            FrostedRenderState(blurRadius = -20f)
                .sanitized()
                .blurRadiusPixels
        )
        assertEquals(
            400,
            FrostedRenderState(blurRadius = 900f)
                .sanitized()
                .blurRadiusPixels
        )
    }
}
