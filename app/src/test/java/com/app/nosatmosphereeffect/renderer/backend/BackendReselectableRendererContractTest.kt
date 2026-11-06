package com.app.nosatmosphereeffect.renderer.backend

import com.app.nosatmosphereeffect.renderer.atmosphere.AtmosphereRenderController
import com.app.nosatmosphereeffect.renderer.colorfill.ColorFillRenderController
import com.app.nosatmosphereeffect.renderer.frosted.FrostedRenderController
import com.app.nosatmosphereeffect.renderer.glass.GlassRenderController
import com.app.nosatmosphereeffect.renderer.halftone.HalftoneRenderController
import com.app.nosatmosphereeffect.renderer.canvassketch.CanvasSketchRenderController
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendReselectableRendererContractTest {
    @Test
    fun everyLiveEffectControllerSupportsImmediateBackendReselection() {
        listOf(
            AtmosphereRenderController::class.java,
            GlassRenderController::class.java,
            ColorFillRenderController::class.java,
            CanvasSketchRenderController::class.java,
            FrostedRenderController::class.java,
            HalftoneRenderController::class.java
        ).forEach { controller ->
            assertTrue(
                "${controller.simpleName} must support live backend reselection",
                BackendReselectableRenderer::class.java.isAssignableFrom(controller)
            )
        }
    }
}
