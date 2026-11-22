package com.app.nosatmosphereeffect.ui.preview

import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.renderer.atmosphere.AtmosphereRenderState
import com.app.nosatmosphereeffect.renderer.colorfill.ColorFillRenderState
import com.app.nosatmosphereeffect.renderer.frosted.FrostedRenderState
import com.app.nosatmosphereeffect.renderer.glass.GlassRenderState
import com.app.nosatmosphereeffect.renderer.halftone.HalftoneRenderState
import com.app.nosatmosphereeffect.renderer.duo.DuoRenderState
import com.app.nosatmosphereeffect.renderer.vhs.VhsRenderState
import com.app.nosatmosphereeffect.renderer.zoom.ZoomRenderState
import com.app.nosatmosphereeffect.renderer.canvassketch.CanvasSketchRenderState

internal sealed interface EffectPreviewRenderState {
    data class Atmosphere(val value: AtmosphereRenderState) : EffectPreviewRenderState

    data class Frosted(val value: FrostedRenderState) : EffectPreviewRenderState

    data class Glass(val value: GlassRenderState) : EffectPreviewRenderState

    data class Halftone(val value: HalftoneRenderState) : EffectPreviewRenderState

    data class Vhs(val value: VhsRenderState) : EffectPreviewRenderState

    data class Zoom(val value: ZoomRenderState) : EffectPreviewRenderState

    data class Duo(val value: DuoRenderState) : EffectPreviewRenderState

    data class ColorFill(val value: ColorFillRenderState) : EffectPreviewRenderState

    data class CanvasSketch(val value: CanvasSketchRenderState) : EffectPreviewRenderState
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

    data class Vhs(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class Zoom(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class Duo(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class ColorFill(override val progress: Float) :
        EffectPreviewOpenGlFrameUpdate

    data class CanvasSketch(override val progress: Float) :
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
            is EffectPreviewRenderState.Vhs -> state.value.clock
            is EffectPreviewRenderState.Zoom -> state.value.clock
            // Duo has no clock.
            is EffectPreviewRenderState.Duo -> ClockOverlayState()
            is EffectPreviewRenderState.ColorFill -> state.value.clock
            is EffectPreviewRenderState.CanvasSketch -> state.value.clock
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
                EffectPreviewRenderState.Atmosphere(state.value.withClock(safe))
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
            is EffectPreviewRenderState.Vhs ->
                EffectPreviewRenderState.Vhs(
                    state.value.copy(clock = safe).sanitized()
                )
            is EffectPreviewRenderState.Zoom ->
                EffectPreviewRenderState.Zoom(
                    state.value.copy(clock = safe).sanitized()
                )
            // Duo has no clock.
            is EffectPreviewRenderState.Duo -> state
            is EffectPreviewRenderState.ColorFill ->
                EffectPreviewRenderState.ColorFill(
                    state.value.copy(clock = safe).sanitized()
                )
            is EffectPreviewRenderState.CanvasSketch ->
                EffectPreviewRenderState.CanvasSketch(
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
            is EffectPreviewRenderState.Vhs -> {
                EffectPreviewRenderState.Vhs(
                    state.value.copy(progress = progress).sanitized()
                )
            }
            is EffectPreviewRenderState.Zoom -> {
                EffectPreviewRenderState.Zoom(
                    state.value.copy(progress = progress).sanitized()
                )
            }
            is EffectPreviewRenderState.Duo -> {
                EffectPreviewRenderState.Duo(
                    state.value.copy(progress = progress).sanitized()
                )
            }
            is EffectPreviewRenderState.ColorFill -> {
                EffectPreviewRenderState.ColorFill(
                    state.value.copy(progress = progress).sanitized()
                )
            }
            is EffectPreviewRenderState.CanvasSketch -> {
                EffectPreviewRenderState.CanvasSketch(
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
            is EffectPreviewRenderState.Vhs -> state.value.progress
            is EffectPreviewRenderState.Zoom -> state.value.progress
            is EffectPreviewRenderState.Duo -> state.value.progress
            is EffectPreviewRenderState.ColorFill -> state.value.progress
            is EffectPreviewRenderState.CanvasSketch -> state.value.progress
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
            is EffectPreviewRenderState.Vhs ->
                EffectPreviewOpenGlFrameUpdate.Vhs(state.value.progress)
            is EffectPreviewRenderState.Zoom ->
                EffectPreviewOpenGlFrameUpdate.Zoom(state.value.progress)
            is EffectPreviewRenderState.Duo ->
                EffectPreviewOpenGlFrameUpdate.Duo(state.value.progress)
            is EffectPreviewRenderState.ColorFill ->
                EffectPreviewOpenGlFrameUpdate.ColorFill(state.value.progress)
            is EffectPreviewRenderState.CanvasSketch ->
                EffectPreviewOpenGlFrameUpdate.CanvasSketch(state.value.progress)
        }
    }
}
