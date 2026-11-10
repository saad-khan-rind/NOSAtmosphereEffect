package com.app.nosatmosphereeffect.renderer.zoom

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomRenderStateTest {

    @Test
    fun `zoom zooms the photo in one way or the other`() {
        // Zoom in: the photo as it is on the lock screen, zoomed in at home.
        assertEquals(1f, ZoomRenderState.photoZoom(effectFirst = false, progress = 0f))
        assertEquals(1.25f, ZoomRenderState.photoZoom(effectFirst = false, progress = 1f))
        // Zoom out: zoomed in on the lock screen, the photo as it is at home.
        assertEquals(1.25f, ZoomRenderState.photoZoom(effectFirst = true, progress = 0f))
        assertEquals(1f, ZoomRenderState.photoZoom(effectFirst = true, progress = 2f))
    }
}
