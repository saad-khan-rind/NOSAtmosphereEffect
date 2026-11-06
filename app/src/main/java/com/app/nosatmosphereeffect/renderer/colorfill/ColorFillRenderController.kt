package com.app.nosatmosphereeffect.renderer.colorfill

import android.content.Context
import com.app.nosatmosphereeffect.transition.WakeTransition
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanColorFillHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** Color Fill on whichever backend suits the phone (EffectBackends); what is Color Fill's own is here. */
class ColorFillRenderController private constructor(
    private val backends: EffectBackends<ColorFillRenderState, ColorFillRenderer, VulkanColorFillHost>
) : EffectController by backends {

    constructor(context: Context, isReverse: Boolean) : this(
        EffectBackends(
            context = context,
            effectId = if (isReverse) "COLOR_FILL_REVERSE" else "COLOR_FILL",
            label = "Color Fill",
            initialState = ColorFillRenderState(),
            effect = ColorFillEffect(isReverse)
        )
    )

    fun configure(dimLevel: Float, originX: Float, originY: Float) {
        backends.update { it.copy(dimLevel = dimLevel, originX = originX, originY = originY) }
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    private class ColorFillEffect(
        private val isReverse: Boolean
    ) : EffectBackends.Effect<ColorFillRenderState, ColorFillRenderer, VulkanColorFillHost> {
        override fun createOpenGl(context: Context, state: ColorFillRenderState, engine: GLWallpaperService.GLEngine) =
            ColorFillRenderer(context, isReverse = isReverse).apply {
                applyToOpenGl(this, state)
                onAnimationFrameRequested = engine::requestRender
            }

        override fun createVulkan(
            context: Context,
            state: ColorFillRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanColorFillHost(
            context = context,
            reverse = isReverse,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: ColorFillRenderer, state: ColorFillRenderState) {
            val shown = drawn(state)
            renderer.blurStrength = shown.progress
            renderer.dimLevel = shown.dimLevel
            renderer.originX = shown.originX
            renderer.originY = shown.originY
            renderer.applyClockState(shown.clock)
        }

        override fun applyToVulkan(host: VulkanColorFillHost, state: ColorFillRenderState) = host.updateState(drawn(state))

        override fun sanitize(state: ColorFillRenderState) = state.sanitized()

        override fun clockOf(state: ColorFillRenderState) = state.clock

        override fun withClock(state: ColorFillRenderState, clock: ClockOverlayState) = state.copy(clock = clock)

        /** With the wake transition filling from the centre, the unlock fills from there too. */
        private fun drawn(state: ColorFillRenderState) =
            if (WakeTransition.fillFromCentre) state.copy(originX = 0.5f, originY = 0.5f) else state
    }
}
