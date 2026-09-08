package com.app.nosatmosphereeffect.service

import android.content.SharedPreferences
import android.graphics.Bitmap
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.helper.ClockStyle
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.helper.GlassEffectPreferences
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderController

class AtmosphereService :
    AnimatedEffectWallpaperService<AtmosphereRenderController>() {

    override val effectId = "ORIGINAL"
    override val lockedProgress = 0f
    override val unlockedProgress = 1f
    override val defaultAnimationDurationMs = 2_500L

    override fun createEffectRenderer(): AtmosphereRenderController {
        return AtmosphereRenderController(applicationContext, reverse = false)
    }

    override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: AtmosphereRenderController
    ) {
        renderer.attach(engine)
    }

    /**
     * The clock's frame cadence is owned by the controller's ClockFramePump,
     * one per engine. This only tells it when to idle: a live wallpaper
     * service can have a settings-preview engine and the real wallpaper alive
     * at once, so anything service-wide would be torn down by whichever
     * engine happened to die first.
     */
    override fun onEngineVisibilityChanged(
        renderer: AtmosphereRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
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
            noiseStrength = preferences.readFloat("noise_strength", 0.06f),
            // Playlist and theme modes rotate the image underneath the clock,
            // so the clock stays off there — see
            // AtmosphereClockPolicy.resolveEnabled.
            clockEnabled = AtmosphereClockPolicy.resolveEnabled(
                effectId = effectId,
                requested = preferences.readBoolean(
                    AtmosphereClockPolicy.ENABLED_KEY,
                    false
                ),
                singleImageMode = !PlaylistModeManager.isPlaylistMode(applicationContext)
            ),
            clockDepthEnabled = preferences.readBoolean(
                AtmosphereClockPolicy.DEPTH_KEY,
                AtmosphereClockPolicy.DEFAULT_DEPTH
            ),
            clockStyleId = preferences.readString(
                AtmosphereClockPolicy.STYLE_KEY,
                ClockStyle.DEFAULT.id
            ),
            clockShowSeconds = preferences.readBoolean(
                AtmosphereClockPolicy.SECONDS_KEY,
                AtmosphereClockPolicy.DEFAULT_SECONDS
            ),
            clockAnimate = preferences.readBoolean(
                AtmosphereClockPolicy.ANIMATE_KEY,
                AtmosphereClockPolicy.DEFAULT_ANIMATE
            ),
            clockCenterX = preferences.readFloat(
                AtmosphereClockPolicy.CENTER_X_KEY,
                AtmosphereClockPolicy.DEFAULT_CENTER_X
            ),
            clockTop = preferences.readFloat(
                AtmosphereClockPolicy.TOP_KEY,
                AtmosphereClockPolicy.DEFAULT_TOP
            ),
            clockHeight = preferences.readFloat(
                AtmosphereClockPolicy.HEIGHT_KEY,
                AtmosphereClockPolicy.DEFAULT_HEIGHT
            ),
            clockOpacity = preferences.readFloat(
                AtmosphereClockPolicy.OPACITY_KEY,
                AtmosphereClockPolicy.DEFAULT_OPACITY
            ),
            clockColor = preferences.readInt(
                AtmosphereClockPolicy.COLOR_KEY,
                AtmosphereClockPolicy.DEFAULT_COLOR
            ),
            clockHourFormat = preferences.readString(
                AtmosphereClockPolicy.HOUR_FORMAT_KEY,
                AtmosphereClockPolicy.DEFAULT_HOUR_FORMAT
            )
        )
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

    private fun SharedPreferences.readInt(key: String, fallback: Int): Int {
        return try {
            getInt(key, fallback)
        } catch (_: ClassCastException) {
            fallback
        }
    }

    private fun SharedPreferences.readString(key: String, fallback: String): String {
        return try {
            getString(key, fallback) ?: fallback
        } catch (_: ClassCastException) {
            fallback
        }
    }

}
