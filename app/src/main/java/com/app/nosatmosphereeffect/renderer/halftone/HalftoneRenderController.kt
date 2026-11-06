package com.app.nosatmosphereeffect.renderer.halftone

import android.content.Context
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanHalftoneHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** Halftone on whichever backend suits the phone (EffectBackends); what is Halftone's own is here. */
class HalftoneRenderController private constructor(
    private val backends: EffectBackends<HalftoneRenderState, HalftoneRenderer, VulkanHalftoneHost>
) : EffectController by backends {

    constructor(context: Context, isReverse: Boolean) : this(
        EffectBackends(
            context = context,
            effectId = if (isReverse) "HALFTONE_REVERSE" else "HALFTONE",
            label = "Halftone",
            initialState = HalftoneRenderState(),
            effect = HalftoneEffect(isReverse)
        )
    )

    fun configure(dimLevel: Float, dotSize: Float, grayscale: Boolean, backgroundOnly: Boolean) {
        backends.update {
            it.copy(
                dimLevel = dimLevel,
                dotSize = dotSize,
                grayscale = grayscale,
                backgroundOnly = backgroundOnly,
                hasSubject = if (backgroundOnly) it.hasSubject else false
            )
        }
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    private class HalftoneEffect(
        private val isReverse: Boolean
    ) : EffectBackends.Effect<HalftoneRenderState, HalftoneRenderer, VulkanHalftoneHost> {
        override fun createOpenGl(context: Context, state: HalftoneRenderState, engine: GLWallpaperService.GLEngine) =
            HalftoneRenderer(context, isReverse = isReverse).apply {
                applyToOpenGl(this, state)
                onSubjectMaskUpdated = engine::requestRender
                onRenderRetryRequested = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }

        override fun createVulkan(
            context: Context,
            state: HalftoneRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanHalftoneHost(
            context = context,
            reverse = isReverse,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: HalftoneRenderer, state: HalftoneRenderState) {
            renderer.blurStrength = state.progress
            renderer.dimLevel = state.dimLevel
            renderer.dotSize = state.dotSize
            renderer.grayscale = state.grayscale
            renderer.configureBackgroundOnly(state.backgroundOnly)
            renderer.applyClockState(state.clock)
        }

        override fun applyToVulkan(host: VulkanHalftoneHost, state: HalftoneRenderState) = host.updateState(state)

        override fun sanitize(state: HalftoneRenderState) = state.sanitized()

        override fun clockOf(state: HalftoneRenderState) = state.clock

        override fun withClock(state: HalftoneRenderState, clock: ClockOverlayState) = state.copy(clock = clock)
    }
}
