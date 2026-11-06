package com.app.nosatmosphereeffect.renderer.canvassketch

import android.content.Context
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanCanvasSketchHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** Canvas Sketch on whichever backend suits the phone (EffectBackends); what is Canvas Sketch's own is here. */
class CanvasSketchRenderController private constructor(
    private val backends: EffectBackends<CanvasSketchRenderState, CanvasSketchRenderer, VulkanCanvasSketchHost>
) : EffectController by backends {

    constructor(context: Context, isReverse: Boolean) : this(
        EffectBackends(
            context = context,
            effectId = if (isReverse) "CANVAS_SKETCH_REVERSE" else "CANVAS_SKETCH",
            label = "Canvas Sketch",
            initialState = CanvasSketchRenderState(),
            effect = CanvasSketchEffect(isReverse)
        )
    )

    fun configure(dimLevel: Float, lineWidth: Float, sensitivity: Float, subjectSegmentationEnabled: Boolean) {
        backends.update {
            it.copy(
                dimLevel = dimLevel,
                lineWidth = lineWidth,
                sensitivity = sensitivity,
                subjectSegmentationEnabled = subjectSegmentationEnabled
            )
        }
        // Only here: rebuilding the sketch is expensive, never on a progress tick.
        backends.withOpenGl { it.rebuildSketch() }
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    private class CanvasSketchEffect(
        private val isReverse: Boolean
    ) : EffectBackends.Effect<CanvasSketchRenderState, CanvasSketchRenderer, VulkanCanvasSketchHost> {
        override fun createOpenGl(context: Context, state: CanvasSketchRenderState, engine: GLWallpaperService.GLEngine) =
            CanvasSketchRenderer(context, isReverse = isReverse).apply {
                applyToOpenGl(this, state)
                onSketchUpdated = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }

        override fun createVulkan(
            context: Context,
            state: CanvasSketchRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanCanvasSketchHost(
            context = context,
            reverse = isReverse,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: CanvasSketchRenderer, state: CanvasSketchRenderState) {
            renderer.blurStrength = state.progress
            renderer.dimLevel = state.dimLevel
            renderer.lineWidth = state.lineWidth
            renderer.sensitivity = state.sensitivity
            renderer.configureSubjectSegmentation(state.subjectSegmentationEnabled)
            renderer.applyClockState(state.clock)
        }

        override fun applyToVulkan(host: VulkanCanvasSketchHost, state: CanvasSketchRenderState) = host.updateState(state)

        override fun sanitize(state: CanvasSketchRenderState) = state.sanitized()

        override fun clockOf(state: CanvasSketchRenderState) = state.clock

        override fun withClock(state: CanvasSketchRenderState, clock: ClockOverlayState) = state.copy(clock = clock)
    }
}
