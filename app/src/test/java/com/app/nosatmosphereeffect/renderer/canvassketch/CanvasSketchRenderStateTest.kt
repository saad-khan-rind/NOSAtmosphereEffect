package com.app.nosatmosphereeffect.renderer.canvassketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CanvasSketchRenderStateTest {
    @Test
    fun invalidNumericValuesAreSanitized() {
        val state = CanvasSketchRenderState(
            progress = Float.NaN,
            dimLevel = Float.POSITIVE_INFINITY,
            lineWidth = -10f,
            sensitivity = 4f,
            subjectSegmentationEnabled = false
        ).sanitized()

        assertEquals(0f, state.progress)
        assertEquals(0f, state.dimLevel)
        assertEquals(0.25f, state.lineWidth)
        assertEquals(1f, state.sensitivity)
        assertFalse(state.subjectSegmentationEnabled)
    }

    @Test
    fun reverseSwapsSketchAndSharpEndpoints() {
        val sketchEndpoint = CanvasSketchRenderState(progress = 0f)
        val sharpEndpoint = CanvasSketchRenderState(progress = 1f)

        assertEquals(0f, sketchEndpoint.imageAmount(reverse = false))
        assertEquals(1f, sharpEndpoint.imageAmount(reverse = false))
        assertEquals(1f, sketchEndpoint.imageAmount(reverse = true))
        assertEquals(0f, sharpEndpoint.imageAmount(reverse = true))
    }
}
