package com.app.nosatmosphereeffect.ui.preview

import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderState
import com.app.nosatmosphereeffect.renderer.ColorFillRenderState
import com.app.nosatmosphereeffect.renderer.FrostedRenderState
import com.app.nosatmosphereeffect.renderer.GlassRenderState
import com.app.nosatmosphereeffect.renderer.HalftoneRenderState
import com.app.nosatmosphereeffect.renderer.NeonRenderState

internal sealed interface EffectPreviewRenderState {
    data class Atmosphere(val value: AtmosphereRenderState) : EffectPreviewRenderState

    data class Frosted(val value: FrostedRenderState) : EffectPreviewRenderState

    data class Glass(val value: GlassRenderState) : EffectPreviewRenderState

    data class Halftone(val value: HalftoneRenderState) : EffectPreviewRenderState

    data class ColorFill(val value: ColorFillRenderState) : EffectPreviewRenderState

    data class Neon(val value: NeonRenderState) : EffectPreviewRenderState
}

internal sealed interface EffectPreviewOpenGlFrameUpdate {
    val progress: Float

    data class Atmosphere(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class Frosted(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class Glass(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class Halftone(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class ColorFill(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class Neon(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate
}

internal object EffectPreviewStatePolicy {

    /** The clock settings carried by [state], whichever effect it is. */
    fun clockOf(state: EffectPreviewRenderState): ClockOverlayState {
        return when (state) {
            // Atmosphere keeps flat clock fields; clockOverlay() bridges them
            // to the shared object the other five carry directly.
            is EffectPreviewRenderState.Atmosphere -> state.value.clockOverlay()
            is EffectPreviewRenderState.Frosted -> state.value.clock
            is EffectPreviewRenderState.Glass -> state.value.clock
            is EffectPreviewRenderState.Halftone -> state.value.clock
            is EffectPreviewRenderState.ColorFill -> state.value.clock
            is EffectPreviewRenderState.Neon -> state.value.clock
        }
    }

    /**
     * Replaces the clock settings on [state], whichever effect it is.
     *
     * Exists so the calibration screen can drive a live preview of ANY
     * effect on EITHER backend. The previous version reached straight into
     * an AtmosphereRenderer and gave up otherwise, which meant the clock
     * simply did not appear while adjusting it on a Vulkan device or on any
     * effect but Atmosphere — it only showed up once the settings had been
     * saved and the real wallpaper reloaded them.
     */
    fun withClock(
        state: EffectPreviewRenderState,
        clock: ClockOverlayState
    ): EffectPreviewRenderState {
        val safe = clock.sanitized()
        return when (state) {
            is EffectPreviewRenderState.Atmosphere ->
                EffectPreviewRenderState.Atmosphere(
                    state.value.copy(
                        clockEnabled = safe.enabled,
                        clockDepthEnabled = safe.depthEnabled,
                        clockStyleId = safe.styleId,
                        clockShowSeconds = safe.showSeconds,
                        clockAnimate = safe.animate,
                        clockCenterX = safe.centerX,
                        clockTop = safe.top,
                        clockHeight = safe.height,
                        clockWidthScale = safe.widthScale,
                        clockHeightScale = safe.heightScale,
                        clockOpacity = safe.opacity,
                        clockColor = safe.color,
                        clockHourFormat = safe.hourFormat,
                        clockScreenId = safe.screenId
                    ).sanitized()
                )
            is EffectPreviewRenderState.Frosted ->
                EffectPreviewRenderState.Frosted(
                    state.value.copy(clock = safe).sanitized()
                )
            is EffectPreviewRenderState.Glass ->
                EffectPreviewRenderState.Glass(
                    state.value.copy(clock = safe).sanitized()
                )
            is EffectPreviewRenderState.Halftone ->
                EffectPreviewRenderState.Halftone(
                    state.value.copy(clock = safe).sanitized()
                )
            is EffectPreviewRenderState.ColorFill ->
                EffectPreviewRenderState.ColorFill(
                    state.value.copy(clock = safe).sanitized()
                )
            is EffectPreviewRenderState.Neon ->
                EffectPreviewRenderState.Neon(
                    state.value.copy(clock = safe).sanitized()
                )
        }
    }

    fun withProgress(
        state: EffectPreviewRenderState,
        progress: Float,
        atmosphereGlassEnabled: Boolean,
        atmosphereGlassBackgroundOnly: Boolean
    ): EffectPreviewRenderState {
        return when (state) {
            is EffectPreviewRenderState.Atmosphere -> {
                EffectPreviewRenderState.Atmosphere(
                    state.value.copy(
                        progress = progress,
                        glassEnabled = atmosphereGlassEnabled,
                        glassBackgroundOnly =
                            atmosphereGlassEnabled && atmosphereGlassBackgroundOnly
                    ).sanitized()
                )
            }
            is EffectPreviewRenderState.Frosted -> {
                EffectPreviewRenderState.Frosted(
                    state.value.copy(progress = progress).sanitized()
                )
            }
            is EffectPreviewRenderState.Glass -> {
                EffectPreviewRenderState.Glass(
                    state.value.copy(progress = progress).sanitized()
                )
            }
            is EffectPreviewRenderState.Halftone -> {
                EffectPreviewRenderState.Halftone(
                    state.value.copy(progress = progress).sanitized()
                )
            }
            is EffectPreviewRenderState.ColorFill -> {
                EffectPreviewRenderState.ColorFill(
                    state.value.copy(progress = progress).sanitized()
                )
            }
            is EffectPreviewRenderState.Neon -> {
                EffectPreviewRenderState.Neon(
                    state.value.copy(progress = progress).sanitized()
                )
            }
        }
    }

    fun progress(state: EffectPreviewRenderState): Float {
        return when (state) {
            is EffectPreviewRenderState.Atmosphere -> state.value.progress
            is EffectPreviewRenderState.Frosted -> state.value.progress
            is EffectPreviewRenderState.Glass -> state.value.progress
            is EffectPreviewRenderState.Halftone -> state.value.progress
            is EffectPreviewRenderState.ColorFill -> state.value.progress
            is EffectPreviewRenderState.Neon -> state.value.progress
        }
    }

    fun openGlFrameUpdate(
        state: EffectPreviewRenderState
    ): EffectPreviewOpenGlFrameUpdate {
        return when (state) {
            is EffectPreviewRenderState.Atmosphere ->
                EffectPreviewOpenGlFrameUpdate.Atmosphere(state.value.progress)
            is EffectPreviewRenderState.Frosted ->
                EffectPreviewOpenGlFrameUpdate.Frosted(state.value.progress)
            is EffectPreviewRenderState.Glass ->
                EffectPreviewOpenGlFrameUpdate.Glass(state.value.progress)
            is EffectPreviewRenderState.Halftone ->
                EffectPreviewOpenGlFrameUpdate.Halftone(state.value.progress)
            is EffectPreviewRenderState.ColorFill ->
                EffectPreviewOpenGlFrameUpdate.ColorFill(state.value.progress)
            is EffectPreviewRenderState.Neon ->
                EffectPreviewOpenGlFrameUpdate.Neon(state.value.progress)
        }
    }
}
