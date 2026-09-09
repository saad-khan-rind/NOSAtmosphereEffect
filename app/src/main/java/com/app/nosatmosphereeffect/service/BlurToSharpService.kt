package com.app.nosatmosphereeffect.service

import android.content.SharedPreferences
import android.graphics.Bitmap
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.helper.ClockPreferences
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.helper.GlassEffectPreferences
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderController

class BlurToSharpService :
    AnimatedEffectWallpaperService<AtmosphereRenderController>() {

    override val effectId = "REVERSE"
    override val lockedProgress = 1f
    override val unlockedProgress = 0f
    override val defaultAnimationDurationMs = 1_500L
    override val initialProgress: Float = 1f
    override val blurDrawerWhenHidden = true

    override fun createEffectRenderer(): AtmosphereRenderController {
        return AtmosphereRenderController(applicationContext, reverse = true)
    }

    override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: AtmosphereRenderController
    ) {
        renderer.attach(engine)
    }

    override fun configureRenderer(
        renderer: AtmosphereRenderController,
        preferences: SharedPreferences
    ) {
        val glassEnabled = preferences.readBoolean(
            AtmosphereGlassPolicy.ENABLED_KEY,
            false
        )
        val glassSettings = GlassEffectPreferences.readAndMigrate(preferences)
        renderer.configure(
            glassEnabled = glassEnabled,
            glassLineCount = glassSettings.lineCount,
            glassLineThickness = glassSettings.lineThickness,
            glassBackgroundOnly = glassEnabled && glassSettings.backgroundOnly,
            dimLevel = preferences.readFloat("dim_level", 0.2f),
            saturation = preferences.readFloat("blob_saturation", 1f),
            contrast = preferences.readFloat("blob_contrast", 1f),
            noiseEnabled = preferences.readBoolean("enable_noise", false),
            noiseScale = preferences.readFloat("noise_scale", 2_000f),
            noiseStrength = preferences.readFloat("noise_strength", 0.06f)
        )
        // Reverse Atmosphere shares every clock preference with the rest of
        // the app; only the transition endpoints differ, and those come from
        // this service's own lockedProgress/unlockedProgress. The controller
        // still takes the flat arguments its forward twin uses, so the shared
        // state object is unpacked here rather than passed through.
        val clock = ClockPreferences.read(
            preferences = preferences,
            effectId = effectId,
            singleImageMode = !PlaylistModeManager.isPlaylistMode(
                applicationContext
            ),
            lockedProgress = lockedProgress,
            unlockedProgress = unlockedProgress
        )
        renderer.configureClock(clock)
    }

    /**
     * The clock's frame cadence is owned by the controller's ClockFramePump,
     * one per engine — see the same override on AtmosphereService.
     */
    override fun onEngineVisibilityChanged(
        renderer: AtmosphereRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    override fun setEffectProgress(
        renderer: AtmosphereRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    override fun setFixedEffectState(
        renderer: AtmosphereRenderController,
        effectApplied: Boolean
    ) {
        renderer.setFixedEffectApplied(effectApplied)
        super.setFixedEffectState(renderer, effectApplied)
    }

    override fun reloadRenderer(renderer: AtmosphereRenderController) {
        renderer.reloadTexture()
    }

    override fun queuePlaylistTransition(
        renderer: AtmosphereRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    override fun setDrawerBlurred(
        renderer: AtmosphereRenderController,
        blurred: Boolean
    ) {
        renderer.setDrawerBlurred(blurred)
    }

    override fun releaseRenderer(renderer: AtmosphereRenderController) {
        renderer.release()
    }

    private fun SharedPreferences.readBoolean(key: String, fallback: Boolean): Boolean {
        return try {
            getBoolean(key, fallback)
        } catch (_: ClassCastException) {
            fallback
        }
    }

    private fun SharedPreferences.readFloat(key: String, fallback: Float): Float {
        return try {
            getFloat(key, fallback)
        } catch (_: ClassCastException) {
            fallback
        }
    }
}
