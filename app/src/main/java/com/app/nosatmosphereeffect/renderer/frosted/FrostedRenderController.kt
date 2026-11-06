package com.app.nosatmosphereeffect.renderer.frosted

import android.content.Context
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanFrostedHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** Frosted on whichever backend suits the phone (EffectBackends); what is Frosted's own is here. */
class FrostedRenderController private constructor(
    private val backends: EffectBackends<FrostedRenderState, FrostedRenderer, VulkanFrostedHost>
) : EffectController by backends {

    constructor(context: Context, isReverse: Boolean) : this(
        EffectBackends(
            context = context,
            effectId = if (isReverse) "FROSTED_REVERSE" else "FROSTED",
            label = "Frosted",
            initialState = FrostedRenderState(),
            effect = FrostedEffect(isReverse)
        )
    )

    fun configure(dimLevel: Float, enableNoise: Boolean, noiseScale: Float, noiseStrength: Float, blurRadius: Float) {
        val previousRadius = backends.current.blurRadiusPixels
        val state = backends.update {
            it.copy(
                dimLevel = dimLevel,
                enableNoise = enableNoise,
                noiseScale = noiseScale,
                noiseStrength = noiseStrength,
                blurRadius = blurRadius
            )
        }
        // The blur is baked into the texture, so a new radius needs a reload.
        if (state.blurRadiusPixels != previousRadius) reloadTexture()
    }

    fun setDrawerBlurred(blurred: Boolean) {
        backends.update { it.copy(drawerBlur = if (blurred) 1f else 0f) }
    }

    /** A new image arrives sharp: the transition starts again from the photo. */
    override fun queuePlaylistTransition(bitmap: android.graphics.Bitmap) {
        backends.update { it.copy(progress = 0f) }
        backends.queuePlaylistTransition(bitmap)
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    private class FrostedEffect(
        private val isReverse: Boolean
    ) : EffectBackends.Effect<FrostedRenderState, FrostedRenderer, VulkanFrostedHost> {
        override fun createOpenGl(context: Context, state: FrostedRenderState, engine: GLWallpaperService.GLEngine) =
            FrostedRenderer(context).apply {
                applyToOpenGl(this, state)
                onAnimationFrameRequested = engine::requestRender
            }

        override fun createVulkan(
            context: Context,
            state: FrostedRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanFrostedHost(
            context = context,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: FrostedRenderer, state: FrostedRenderState) {
            renderer.blurStrength = state.progress
            renderer.dimLevel = state.dimLevel
            renderer.enableNoise = state.enableNoise
            renderer.noiseScale = state.noiseScale
            renderer.noiseStrength = state.noiseStrength
            renderer.blurRadius = state.blurRadius
            renderer.setDrawerBlurred(state.drawerBlur > 0.5f)
            renderer.applyClockState(state.clock)
        }

        override fun applyToVulkan(host: VulkanFrostedHost, state: FrostedRenderState) = host.updateState(state)

        override fun sanitize(state: FrostedRenderState) = state.sanitized()

        override fun clockOf(state: FrostedRenderState) = state.clock

        override fun withClock(state: FrostedRenderState, clock: ClockOverlayState) = state.copy(clock = clock)
    }
}
