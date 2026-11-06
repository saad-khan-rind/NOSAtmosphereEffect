package com.app.nosatmosphereeffect.renderer.vhs

import android.content.Context
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanVhsHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** VHS on whichever backend suits the phone (EffectBackends); what is VHS's own is here. */
class VhsRenderController private constructor(
    private val backends: EffectBackends<VhsRenderState, VhsRenderer, VulkanVhsHost>
) : EffectController by backends {

    constructor(context: Context, isReverse: Boolean) : this(
        EffectBackends(
            context = context,
            effectId = if (isReverse) "VHS_REVERSE" else "VHS",
            label = "VHS",
            initialState = VhsRenderState(),
            effect = VhsEffect(isReverse)
        )
    )

    fun configure(dimLevel: Float) {
        backends.update { it.copy(dimLevel = dimLevel) }
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    private class VhsEffect(
        private val isReverse: Boolean
    ) : EffectBackends.Effect<VhsRenderState, VhsRenderer, VulkanVhsHost> {
        override fun createOpenGl(context: Context, state: VhsRenderState, engine: GLWallpaperService.GLEngine) =
            VhsRenderer(context, isReverse = isReverse).apply {
                applyToOpenGl(this, state)
                onSubjectMaskUpdated = engine::requestRender
                onRenderRetryRequested = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }

        override fun createVulkan(
            context: Context,
            state: VhsRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanVhsHost(
            context = context,
            reverse = isReverse,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: VhsRenderer, state: VhsRenderState) {
            renderer.blurStrength = state.progress
            renderer.dimLevel = state.dimLevel
            renderer.applyClockState(state.clock)
        }

        override fun applyToVulkan(host: VulkanVhsHost, state: VhsRenderState) = host.updateState(state)

        override fun sanitize(state: VhsRenderState) = state.sanitized()

        override fun clockOf(state: VhsRenderState) = state.clock

        override fun withClock(state: VhsRenderState, clock: ClockOverlayState) = state.copy(clock = clock)
    }
}
