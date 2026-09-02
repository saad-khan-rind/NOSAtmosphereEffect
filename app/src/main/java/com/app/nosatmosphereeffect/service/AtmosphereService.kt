package com.app.nosatmosphereeffect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.ContextCompat
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.helper.ClockStyle
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.helper.GlassEffectPreferences
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderController

class AtmosphereService :
    AnimatedEffectWallpaperService<AtmosphereRenderController>() {

    override val effectId = "ORIGINAL"
    override val lockedProgress = 0f
    override val unlockedProgress = 1f
    override val defaultAnimationDurationMs = 2_500L

    /**
     * Drives the clock.
     *
     * The renderers only draw when something asks them to, and a wallpaper
     * sitting on the lock screen with transitions off asks for nothing. That
     * left the clock frozen at whatever minute it happened to be rendered
     * on. ACTION_TIME_TICK arrives once a minute for registered receivers
     * only, which is exactly the cadence the clock needs; the other two
     * actions cover manual time changes and travel across timezones, both of
     * which also change the 12/24-hour reading.
     */
    private var timeReceiver: BroadcastReceiver? = null

    override fun createEffectRenderer(): AtmosphereRenderController {
        return AtmosphereRenderController(applicationContext, reverse = false)
    }

    override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: AtmosphereRenderController
    ) {
        renderer.attach(engine)
    }

    override fun onRendererAttached(
        renderer: AtmosphereRenderController,
        requestRender: () -> Unit
    ) {
        super.onRendererAttached(renderer, requestRender)
        registerTimeReceiver(renderer)
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
            clockEnabled = preferences.readBoolean(
                AtmosphereClockPolicy.ENABLED_KEY,
                false
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
        unregisterTimeReceiver()
        renderer.release()
    }

    private fun registerTimeReceiver(renderer: AtmosphereRenderController) {
        unregisterTimeReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                renderer.onSystemTimeChanged()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            // ACTION_TIME_TICK is protected and can only be delivered to a
            // receiver registered in code, never a manifest one, so this has
            // to happen here rather than in AndroidManifest.xml.
            ContextCompat.registerReceiver(
                this,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            timeReceiver = receiver
        } catch (failure: RuntimeException) {
            // A failure here costs the clock its once-a-minute refresh, not
            // the wallpaper — degrade rather than take the service down.
            Log.w(TAG, "Could not register the clock time receiver", failure)
            timeReceiver = null
        }
    }

    private fun unregisterTimeReceiver() {
        val receiver = timeReceiver ?: return
        timeReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Already gone; nothing to do.
        }
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

    private fun SharedPreferences.readString(key: String, fallback: String): String {
        return try {
            getString(key, fallback) ?: fallback
        } catch (_: ClassCastException) {
            fallback
        }
    }

    private companion object {
        const val TAG = "AtmosphereService"
    }
}
