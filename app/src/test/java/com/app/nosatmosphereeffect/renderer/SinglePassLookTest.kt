package com.app.nosatmosphereeffect.renderer

import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreference
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SinglePassLookTest {

    @Test
    fun `the vulkan direction flag says which variant shows the effect on the lock screen`() {
        // Halftone goes from the photo into dots; its reverse starts on dots.
        assertFalse(SinglePassLook.HALFTONE.effectFirst(reverse = false))
        assertTrue(SinglePassLook.HALFTONE.effectFirst(reverse = true))
        // VHS starts on tape and clears; its reverse rolls into tape.
        assertTrue(SinglePassLook.VHS.effectFirst(reverse = false))
        assertFalse(SinglePassLook.VHS.effectFirst(reverse = true))
    }

    @Test
    fun `the opengl shaders agree with that direction`() {
        assertEquals("shaders/vhs/vhs_to_sharp.frag", SinglePassLook.VHS.fragmentShader(reverse = false))
        assertEquals("shaders/vhs/sharp_to_vhs.frag", SinglePassLook.VHS.fragmentShader(reverse = true))
    }

    @Test
    fun `effect ids resolve to their look and direction`() {
        assertEquals(SinglePassLook.VHS, SinglePassLook.of("VHS_REVERSE"))
        assertTrue(SinglePassLook.isReverse("VHS_REVERSE"))
        assertFalse(SinglePassLook.isReverse("VHS"))
        assertEquals("VHS_REVERSE", SinglePassLook.VHS.effectId(reverse = true))
    }

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
    fun `zoom zooms the photo in one way or the other and nothing else zooms`() {
        // Zoom in: the photo as it is on the lock screen, zoomed in at home.
        assertFalse(SinglePassLook.ZOOM.effectFirst(reverse = false))
        assertEquals(1f, SinglePassLook.ZOOM.photoZoom(effectFirst = false, progress = 0f))
        assertEquals(1.25f, SinglePassLook.ZOOM.photoZoom(effectFirst = false, progress = 1f))
        assertEquals(1.25f, SinglePassLook.ZOOM.photoZoom(effectFirst = true, progress = 0f))
        assertEquals(1f, SinglePassLook.ZOOM.photoZoom(effectFirst = true, progress = 2f))
        assertEquals(SinglePassLook.ZOOM, SinglePassLook.of("ZOOM_REVERSE"))
        assertEquals("shaders/zoom/zoom.frag", SinglePassLook.ZOOM.fragmentShader(reverse = true))
        listOf(SinglePassLook.HALFTONE, SinglePassLook.VHS).forEach {
            assertEquals(1f, it.photoZoom(effectFirst = true, progress = 0.3f))
        }
    }
}
