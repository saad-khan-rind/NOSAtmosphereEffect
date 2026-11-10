package com.app.nosatmosphereeffect.renderer.glass

import android.content.Context
import com.app.nosatmosphereeffect.effect.GlassTransitionStyle
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanGlassHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** Glass on whichever backend suits the phone (EffectBackends); what is Glass's own is here. */
class GlassRenderController private constructor(
    private val backends: EffectBackends<GlassRenderState, GlassRenderer, VulkanGlassHost>
) : EffectController by backends {

    constructor(context: Context, reverse: Boolean) : this(
        EffectBackends(
            context = context,
            effectId = if (reverse) "GLASS_REVERSE" else "GLASS",
            label = "Glass",
            initialState = GlassRenderState(),
            effect = GlassEffect(reverse)
        )
    )

    fun configure(
        dimLevel: Float,
        lineCount: Int,
        lineThickness: Float,
        transitionStyle: GlassTransitionStyle,
        backgroundOnly: Boolean
    ) {
        backends.update {
            it.copy(
                dimLevel = dimLevel,
                lineCount = lineCount,
                lineThickness = lineThickness,
                transitionStyle = transitionStyle,
                backgroundOnly = backgroundOnly
            )
        }
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    private class GlassEffect(
        private val reverse: Boolean
    ) : EffectBackends.Effect<GlassRenderState, GlassRenderer, VulkanGlassHost> {
        override fun createOpenGl(context: Context, state: GlassRenderState, engine: GLWallpaperService.GLEngine) =
            GlassRenderer(context).apply {
                applyToOpenGl(this, state)
                onRenderRetryRequested = engine::requestRender
                onSubjectMaskUpdated = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }

        override fun createVulkan(
            context: Context,
            state: GlassRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanGlassHost(
            context = context,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: GlassRenderer, state: GlassRenderState) {
            renderer.progress = state.progress
            renderer.dimLevel = state.dimLevel
            renderer.lineCount = state.lineCount
            renderer.lineThickness = state.lineThickness
            renderer.transitionStyle = state.transitionStyle
            renderer.configureBackgroundOnly(state.backgroundOnly)
            renderer.applyClockState(state.clock)
        }

        override fun applyToVulkan(host: VulkanGlassHost, state: GlassRenderState) = host.updateState(state)

        override fun sanitize(state: GlassRenderState) = state.sanitized()

        override fun clockOf(state: GlassRenderState) = state.clock

        override fun withClock(state: GlassRenderState, clock: ClockOverlayState) = state.copy(clock = clock)
    }
}
