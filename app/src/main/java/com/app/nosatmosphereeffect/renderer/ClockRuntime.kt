package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.util.Log
import com.app.nosatmosphereeffect.helper.ClockFramePump
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.ClockPalette
import java.util.concurrent.Executors

/**
 * The two pieces of clock machinery that live above the renderer rather than
 * inside it: the frame pump that makes a static wallpaper tick, and the
 * background resolution of the wallpaper-derived "auto" colour.
 *
 * Both were written inline in AtmosphereRenderController first. Repeating them
 * in five more controllers would have repeated their two non-obvious rules as
 * well:
 *
 * - The pump is per-engine, never per-service. A live wallpaper service hosts
 *   several engines over its lifetime — the settings preview and the real
 *   wallpaper can be alive at once — and anything service-wide gets torn down
 *   by whichever engine dies first, silently leaving the survivor without a
 *   clock that advances.
 * - Colour extraction decodes an image and runs Palette, so it cannot happen
 *   on the render or main thread, and its result has to be folded back in
 *   without clobbering whatever else changed meanwhile.
 */
class ClockRuntime(
    context: Context,
    workerName: String,
    /**
     * Called when the displayed time should advance. Implementations push the
     * change into their renderers and ask for a frame.
     */
    private val onTick: () -> Unit,
    /**
     * Called on a worker thread once a wallpaper-derived colour is available
     * and the user is actually asking for "auto". Implementations re-apply
     * their state with the new colour and ask for a frame.
     */
    private val onColorResolved: (Int) -> Unit
) {
    private val appContext = context.applicationContext
    private val pump = ClockFramePump(appContext) { onTick() }
    private val colorWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, workerName).apply { isDaemon = true }
    }

    @Volatile private var requestedColor: Int = ClockPalette.AUTO
    @Volatile private var resolvedAutoColor: Int? = null
    @Volatile private var closed = false

    /**
     * Folds the resolved colour into [state], starts or idles the frame pump
     * to match, and kicks off colour extraction when it is needed. Returns the
     * state the renderers should actually be given.
     */
    fun configure(state: ClockOverlayState): ClockOverlayState {
        requestedColor = state.requestedColor
        val resolved = state.copy(
            color = ClockPalette.resolve(state.requestedColor, resolvedAutoColor)
        ).sanitized()
        pump.configure(resolved.enabled, resolved.showSeconds)
        if (resolved.enabled && ClockPalette.isAuto(requestedColor)) {
            refreshAutoColor()
        }
        return resolved
    }

    /** Re-applies the cached colour to a state built elsewhere. */
    fun withResolvedColor(state: ClockOverlayState): ClockOverlayState {
        return state.copy(
            color = ClockPalette.resolve(state.requestedColor, resolvedAutoColor)
        ).sanitized()
    }

    fun setEngineVisible(visible: Boolean) {
        pump.setVisible(visible)
    }

    /**
     * Call when the wallpaper image is replaced: any tint derived from the
     * previous photo is stale.
     */
    fun invalidateWallpaperColor() {
        ClockPalette.invalidateAutoColor()
        if (ClockPalette.isAuto(requestedColor)) refreshAutoColor()
    }

    fun close() {
        closed = true
        pump.close()
        colorWorker.shutdownNow()
    }

    private fun refreshAutoColor() {
        val submitted = runCatching {
            colorWorker.execute {
                if (closed) return@execute
                val derived = ClockPalette.autoColorFor(appContext) ?: return@execute
                if (derived == resolvedAutoColor) return@execute
                resolvedAutoColor = derived
                // The user may have picked a fixed colour while this was in
                // flight; the cached value is still worth keeping for later.
                if (!ClockPalette.isAuto(requestedColor) || closed) return@execute
                onColorResolved(derived)
            }
        }
        if (submitted.isFailure) {
            Log.w(TAG, "Could not schedule clock colour extraction")
        }
    }

    private companion object {
        const val TAG = "ClockRuntime"
    }
}
