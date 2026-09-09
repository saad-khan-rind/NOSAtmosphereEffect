package com.app.nosatmosphereeffect.service

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.ClockPreferences
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.helper.GlassEffectPreferences
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import com.app.nosatmosphereeffect.renderer.GlassRenderController

abstract class GlassWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<GlassRenderController>() {

    final override val effectId = if (reverseEffect) "GLASS_REVERSE" else "GLASS"
    final override val lockedProgress = GlassEffectPolicy.shaderProgress(0f, reverseEffect)
    final override val unlockedProgress = GlassEffectPolicy.shaderProgress(1f, reverseEffect)
    final override val defaultAnimationDurationMs = 1_200L
    final override val initialProgress: Float? = if (reverseEffect) 1f else null

    final override fun createEffectRenderer(): GlassRenderController {
        return GlassRenderController(applicationContext, reverseEffect)
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: GlassRenderController
    ) {
        renderer.attach(engine)
    }

    final override fun configureRenderer(
        renderer: GlassRenderController,
        preferences: SharedPreferences
    ) {
        val settings = GlassEffectPreferences.readAndMigrate(preferences)
        renderer.configure(
            dimLevel = preferences.readFloat("dim_level", 0f),
            lineCount = settings.lineCount,
            lineThickness = settings.lineThickness,
            transitionStyle = settings.transitionStyle,
            backgroundOnly = settings.backgroundOnly
        )
        // Playlist and theme modes rotate the image underneath the clock, so
        // the clock stays off there — a position calibrated against one photo
        // is wrong for the next. See AtmosphereClockPolicy.resolveEnabled.
        renderer.configureClock(
            ClockPreferences.read(
                preferences = preferences,
                effectId = effectId,
                singleImageMode = !PlaylistModeManager.isPlaylistMode(
                    applicationContext
                ),
                lockedProgress = lockedProgress,
                unlockedProgress = unlockedProgress
            )
        )
    }

    /**
     * The clock's frame cadence is owned by the controller's ClockFramePump,
     * one per engine — see the same override on the other effect services.
     */
    final override fun onEngineVisibilityChanged(
        renderer: GlassRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(
        renderer: GlassRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: GlassRenderController) {
        renderer.reloadTexture()
    }

    final override fun queuePlaylistTransition(
        renderer: GlassRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    final override fun releaseRenderer(renderer: GlassRenderController) {
        renderer.release()
    }

    private fun SharedPreferences.readFloat(key: String, fallback: Float): Float {
        return try {
            getFloat(key, fallback)
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Preference '$key' has the wrong type; using $fallback", failure)
            fallback
        }
    }

    private companion object {
        const val TAG = "GlassService"
    }
}

class GlassService : GlassWallpaperService(reverseEffect = false)

class GlassReverseService : GlassWallpaperService(reverseEffect = true)
