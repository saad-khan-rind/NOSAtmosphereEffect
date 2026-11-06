package com.app.nosatmosphereeffect.renderer.atmosphere

import android.content.Context
import android.content.ContextWrapper
import com.app.nosatmosphereeffect.effect.GlassEffectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphereRenderStateTest {
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `sanitization keeps Vulkan uniforms finite and bounded`() {
        val state = AtmosphereRenderState(
            progress = Float.NaN,
            dimLevel = Float.NEGATIVE_INFINITY,
            noiseEnabled = true,
            noiseScale = -10f,
            noiseStrength = Float.NaN,
            saturation = -2f,
            contrast = Float.NaN,
            glassEnabled = true,
            glassLineCount = Int.MIN_VALUE,
            glassLineThickness = Float.POSITIVE_INFINITY,
            glassBackgroundOnly = true,
            drawerBlur = 3f,
            scrollOffsetX = Float.NaN,
            scrollWindowX = -1f
        ).sanitized()

        assertEquals(0f, state.progress, 0f)
        assertEquals(0.2f, state.dimLevel, 0f)
        assertTrue(state.noiseEnabled)
        assertEquals(0f, state.noiseScale, 0f)
        assertEquals(0.06f, state.noiseStrength, 0f)
        assertEquals(0f, state.saturation, 0f)
        assertEquals(1f, state.contrast, 0f)
        assertEquals(GlassEffectPolicy.MIN_LINE_COUNT, state.glassLineCount)
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            state.glassLineThickness,
            0f
        )
        assertTrue(state.glassBackgroundOnly)
        assertEquals(1f, state.drawerBlur, 0f)
        assertEquals(0.5f, state.scrollOffsetX, 0f)
        assertEquals(0.001f, state.scrollWindowX, 0f)
    }

    @Test
    fun `background isolation cannot remain active when atmosphere glass is off`() {
        val state = AtmosphereRenderState(
            glassEnabled = false,
            glassBackgroundOnly = true
        ).sanitized()

        assertFalse(state.glassBackgroundOnly)
    }

    @Test
    fun `valid atmosphere settings survive sanitization`() {
        val state = AtmosphereRenderState(
            progress = 0.7f,
            dimLevel = 0.3f,
            noiseEnabled = true,
            noiseScale = 1_000f,
            noiseStrength = 0.04f,
            saturation = 1.2f,
            contrast = 0.8f,
            glassEnabled = true,
            glassLineCount = 20,
            glassLineThickness = 0.5f,
            glassBackgroundOnly = true,
            drawerBlur = 0.2f,
            scrollOffsetX = 0.6f,
            scrollWindowX = 0.8f
        )

        assertEquals(state, state.sanitized())
    }

    @Test
    fun `fixed original state temporarily disables glass and restores preference`() {
        val controller = AtmosphereRenderController(context, reverse = false)
        controller.configure(
            dimLevel = 0.2f,
            saturation = 1f,
            contrast = 1f,
            noiseEnabled = false,
            noiseScale = 2_000f,
            noiseStrength = 0.06f,
            glassEnabled = true,
            glassLineCount = GlassEffectPolicy.DEFAULT_LINE_COUNT,
            glassLineThickness = GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            glassBackgroundOnly = true
        )

        controller.setFixedEffectApplied(false)
        assertFalse(controller.currentStateForTesting().glassEnabled)
        assertFalse(controller.currentStateForTesting().glassBackgroundOnly)

        controller.setFixedEffectApplied(true)
        assertTrue(controller.currentStateForTesting().glassEnabled)
        assertTrue(controller.currentStateForTesting().glassBackgroundOnly)
        controller.release()
    }

    @Test
    fun `blob control path may travel just outside the image like OpenGL`() {
        val positions = FloatArray(AtmosphereBlobFrame.MAX_BLOBS * 2)
        positions[0] = -0.08f
        positions[1] = 1.06f
        val frame = AtmosphereBlobFrame(
            positions = positions,
            count = 1
        )

        val sanitized = frame.sanitized()

        assertEquals(-0.08f, sanitized.positions[0], 0f)
        assertEquals(1.06f, sanitized.positions[1], 0f)
    }
}
