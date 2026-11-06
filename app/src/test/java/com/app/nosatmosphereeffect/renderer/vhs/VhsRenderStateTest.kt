package com.app.nosatmosphereeffect.renderer.vhs

import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreference
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class VhsRenderStateTest {

    @Test
    fun `vhs runs on vulkan where the device allows it`() {
        listOf("VHS", "VHS_REVERSE").forEach { id ->
            assertEquals(
                GraphicsBackend.VULKAN,
                GraphicsBackendSelector.select(
                    effectId = id,
                    hasVulkan11 = true,
                    nativeProbePassed = true,
                    blockedAfterFailure = false,
                    preference = GraphicsBackendPreference.AUTOMATIC
                )
            )
        }
    }

    @Test
    fun `state stays in range`() {
        val state = VhsRenderState(progress = 3f, dimLevel = Float.NaN, hasSubject = true).sanitized()
        assertEquals(1f, state.progress)
        assertEquals(0f, state.dimLevel)
        // No clock depth, so no subject mask is wanted.
        assertEquals(false, state.hasSubject)
    }
}
