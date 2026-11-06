package com.app.nosatmosphereeffect.renderer.glass

import com.app.nosatmosphereeffect.effect.GlassEffectPolicy
import com.app.nosatmosphereeffect.effect.GlassTransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GlassRenderStateTest {
    @Test
    fun `sanitization matches the OpenGL glass policy`() {
        val state = GlassRenderState(
            progress = Float.NaN,
            dimLevel = Float.POSITIVE_INFINITY,
            lineCount = Int.MAX_VALUE,
            lineThickness = Float.NaN,
            transitionStyle = GlassTransitionStyle.FADE,
            backgroundOnly = true,
            scrollOffsetX = -4f,
            scrollWindowX = 0f
        ).sanitized()

        assertEquals(0f, state.progress, 0f)
        assertEquals(0f, state.dimLevel, 0f)
        assertEquals(GlassEffectPolicy.MAX_LINE_COUNT, state.lineCount)
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            state.lineThickness,
            0f
        )
        assertEquals(GlassTransitionStyle.FADE, state.transitionStyle)
        assertEquals(0f, state.scrollOffsetX, 0f)
        assertEquals(0.001f, state.scrollWindowX, 0f)
    }

    @Test
    fun `Vulkan transition selector uses the same two shader values as OpenGL`() {
        assertEquals(
            0f,
            GlassRenderState(
                transitionStyle = GlassTransitionStyle.RIGHT_TO_LEFT
            ).transitionStyleShaderValue,
            0f
        )
        assertEquals(
            1f,
            GlassRenderState(
                transitionStyle = GlassTransitionStyle.FADE
            ).transitionStyleShaderValue,
            0f
        )
    }

    @Test
    fun `valid state survives sanitization`() {
        val state = GlassRenderState(
            progress = 0.4f,
            dimLevel = 0.25f,
            lineCount = 24,
            lineThickness = 0.6f,
            backgroundOnly = false,
            scrollOffsetX = 0.2f,
            scrollWindowX = 0.75f
        )

        assertEquals(state, state.sanitized())
        assertFalse(state.sanitized().backgroundOnly)
    }
}
