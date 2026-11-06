package com.app.nosatmosphereeffect.renderer.zoom

import android.content.Context
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanZoomHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** Zoom on whichever backend suits the phone (EffectBackends); what is Zoom's own is here. */
class ZoomRenderController private constructor(
    private val backends: EffectBackends<ZoomRenderState, ZoomRenderer, VulkanZoomHost>
) : EffectController by backends {

    constructor(context: Context, isReverse: Boolean) : this(
        EffectBackends(
            context = context,
            effectId = if (isReverse) "ZOOM_REVERSE" else "ZOOM",
            label = "Zoom",
            initialState = ZoomRenderState(),
            effect = ZoomEffect(isReverse)
        )
    )

    fun configure(dimLevel: Float) {
        backends.update { it.copy(dimLevel = dimLevel) }
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    private class ZoomEffect(
        private val isReverse: Boolean
    ) : EffectBackends.Effect<ZoomRenderState, ZoomRenderer, VulkanZoomHost> {
        override fun createOpenGl(context: Context, state: ZoomRenderState, engine: GLWallpaperService.GLEngine) =
            ZoomRenderer(context, isReverse = isReverse).apply {
                applyToOpenGl(this, state)
                onSubjectMaskUpdated = engine::requestRender
                onRenderRetryRequested = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }

        override fun createVulkan(
            context: Context,
            state: ZoomRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanZoomHost(
            context = context,
            reverse = isReverse,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: ZoomRenderer, state: ZoomRenderState) {
            renderer.blurStrength = state.progress
            renderer.dimLevel = state.dimLevel
            renderer.applyClockState(state.clock)
        }

        override fun applyToVulkan(host: VulkanZoomHost, state: ZoomRenderState) = host.updateState(state)

        override fun sanitize(state: ZoomRenderState) = state.sanitized()

        override fun clockOf(state: ZoomRenderState) = state.clock

        override fun withClock(state: ZoomRenderState, clock: ClockOverlayState) = state.copy(clock = clock)
    }
}
