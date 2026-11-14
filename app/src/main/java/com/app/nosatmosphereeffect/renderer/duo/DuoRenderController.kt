package com.app.nosatmosphereeffect.renderer.duo

import android.content.Context
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanDuoHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** Duo on whichever backend suits the phone (EffectBackends); what is Duo's own is here. */
class DuoRenderController private constructor(
    private val backends: EffectBackends<DuoRenderState, DuoRenderer, VulkanDuoHost>
) : EffectController by backends {

    constructor(context: Context) : this(
        EffectBackends(
            context = context,
            effectId = EFFECT_ID,
            label = "Duo",
            initialState = DuoRenderState(),
            effect = DuoEffect()
        )
    )

    fun configure(dimLevel: Float, style: DuoStyle) {
        backends.update { it.copy(dimLevel = dimLevel, style = style) }
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    /** The home photo's file changed (DuoHomeImage): draw the new one. */
    fun reloadHomeImage() {
        backends.withOpenGl { it.reloadHomeImage() }
        backends.withVulkan { it.reloadHomeImage() }
    }

    private class DuoEffect : EffectBackends.Effect<DuoRenderState, DuoRenderer, VulkanDuoHost> {
        override fun createOpenGl(context: Context, state: DuoRenderState, engine: GLWallpaperService.GLEngine) =
            DuoRenderer(context).apply {
                applyToOpenGl(this, state)
                onRenderRetryRequested = engine::requestRender
            }

        override fun createVulkan(
            context: Context,
            state: DuoRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanDuoHost(
            context = context,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: DuoRenderer, state: DuoRenderState) {
            renderer.progress = state.progress
            renderer.dimLevel = state.dimLevel
            renderer.style = state.style
        }

        override fun applyToVulkan(host: VulkanDuoHost, state: DuoRenderState) = host.updateState(state)

        override fun sanitize(state: DuoRenderState) = state.sanitized()

        // Duo has no clock: two photos have no one subject to fit it to.
        override fun clockOf(state: DuoRenderState) = ClockOverlayState()

        override fun withClock(state: DuoRenderState, clock: ClockOverlayState) = state
    }

    companion object {
        const val EFFECT_ID = "DUO"
    }
}
