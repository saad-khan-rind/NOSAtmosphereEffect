package com.app.nosatmosphereeffect.renderer.atmosphere

import android.content.Context
import com.app.nosatmosphereeffect.clock.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.clock.ClockScreen
import com.app.nosatmosphereeffect.clock.ClockScreenPolicy
import com.app.nosatmosphereeffect.renderer.backend.EffectBackends
import com.app.nosatmosphereeffect.renderer.backend.EffectController
import com.app.nosatmosphereeffect.renderer.backend.EffectOpenGlRenderer
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanAtmosphereHost
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** Atmosphere's two OpenGL ES renderers, forward and reverse, both take its whole state. */
internal interface AtmosphereOpenGlRenderer : EffectOpenGlRenderer {
    fun applyState(state: AtmosphereRenderState)
}

/** Atmosphere on whichever backend suits the phone (EffectBackends); what is Atmosphere's own is here. */
class AtmosphereRenderController private constructor(
    private val effectId: String,
    private val backends: EffectBackends<AtmosphereRenderState, AtmosphereOpenGlRenderer, VulkanAtmosphereHost>
) : EffectController by backends {

    constructor(context: Context, reverse: Boolean) : this(
        if (reverse) "ATMOSPHERE_REVERSE" else "ATMOSPHERE",
        reverse,
        context
    )

    private constructor(effectId: String, reverse: Boolean, context: Context) : this(
        effectId,
        EffectBackends(
            context = context,
            effectId = effectId,
            label = "Atmosphere",
            initialState = AtmosphereRenderState(),
            effect = AtmosphereEffect(reverse)
        )
    )

    /** The glass the user set, which a fixed (transition-free) wallpaper turns off on its plain side. */
    @Volatile private var configuredGlassEnabled = false
    @Volatile private var configuredGlassBackgroundOnly = false

    fun configure(
        dimLevel: Float,
        saturation: Float,
        contrast: Float,
        noiseEnabled: Boolean,
        noiseScale: Float,
        noiseStrength: Float,
        glassEnabled: Boolean,
        glassLineCount: Int,
        glassLineThickness: Float,
        glassBackgroundOnly: Boolean
    ) {
        configuredGlassEnabled = glassEnabled
        configuredGlassBackgroundOnly = glassBackgroundOnly
        backends.update {
            it.copy(
                dimLevel = dimLevel,
                saturation = saturation,
                contrast = contrast,
                noiseEnabled = noiseEnabled,
                noiseScale = noiseScale,
                noiseStrength = noiseStrength,
                glassEnabled = glassEnabled,
                glassLineCount = glassLineCount,
                glassLineThickness = glassLineThickness,
                glassBackgroundOnly = glassBackgroundOnly
            )
        }
    }

    /**
     * The clock, with Atmosphere's rules collapsed here, once: an effect that
     * is only sharp on one side gets the clock on that side, whatever is
     * stored, so a preference carried over from another effect can't put it
     * somewhere illegible.
     */
    override fun configureClock(clock: ClockOverlayState) {
        val safe = clock.sanitized()
        backends.configureClock(
            safe.copy(
                enabled = AtmosphereClockPolicy.resolveEnabled(effectId, safe.enabled),
                screenId = ClockScreenPolicy.resolveScreen(effectId, ClockScreen.fromId(safe.screenId)).id
            )
        )
    }

    fun setProgress(progress: Float) {
        backends.update { it.copy(progress = progress) }
    }

    fun setFixedEffectApplied(effectApplied: Boolean) {
        backends.update {
            it.copy(
                glassEnabled = effectApplied && configuredGlassEnabled,
                glassBackgroundOnly = effectApplied && configuredGlassEnabled && configuredGlassBackgroundOnly
            )
        }
    }

    fun setDrawerBlurred(blurred: Boolean) {
        backends.update { it.copy(drawerBlur = if (blurred) 1f else 0f) }
    }

    internal fun currentStateForTesting(): AtmosphereRenderState = backends.current

    private class AtmosphereEffect(
        private val reverse: Boolean
    ) : EffectBackends.Effect<AtmosphereRenderState, AtmosphereOpenGlRenderer, VulkanAtmosphereHost> {
        override fun createOpenGl(
            context: Context,
            state: AtmosphereRenderState,
            engine: GLWallpaperService.GLEngine
        ): AtmosphereOpenGlRenderer = if (reverse) {
            AtmosphereReverseRenderer(context).apply {
                applyState(state)
                onRenderRetryRequested = engine::requestRender
                onSubjectMaskUpdated = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }
        } else {
            AtmosphereRenderer(context).apply {
                applyState(state)
                onRenderRetryRequested = engine::requestRender
                onSubjectMaskUpdated = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }
        }

        override fun createVulkan(
            context: Context,
            state: AtmosphereRenderState,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ) = VulkanAtmosphereHost(
            context = context,
            reverse = reverse,
            initialState = state,
            onFatalFailure = onFatalFailure,
            onVulkanActive = onVulkanActive
        )

        override fun applyToOpenGl(renderer: AtmosphereOpenGlRenderer, state: AtmosphereRenderState) =
            renderer.applyState(state)

        override fun applyToVulkan(host: VulkanAtmosphereHost, state: AtmosphereRenderState) = host.updateState(state)

        override fun sanitize(state: AtmosphereRenderState) = state.sanitized()

        override fun clockOf(state: AtmosphereRenderState) = state.clockOverlay()

        override fun withClock(state: AtmosphereRenderState, clock: ClockOverlayState) = state.withClock(clock)
    }
}
