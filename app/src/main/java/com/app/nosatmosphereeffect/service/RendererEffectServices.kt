package com.app.nosatmosphereeffect.service

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.CanvasSubjectSettings
import com.app.nosatmosphereeffect.helper.ClockPreferences
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import com.app.nosatmosphereeffect.helper.SubjectIsolationPolicy
import com.app.nosatmosphereeffect.renderer.ColorFillRenderController
import com.app.nosatmosphereeffect.renderer.FrostedRenderController
import com.app.nosatmosphereeffect.renderer.FrostedRenderer
import com.app.nosatmosphereeffect.renderer.HalftoneProgressPolicy
import com.app.nosatmosphereeffect.renderer.HalftoneRenderController
import com.app.nosatmosphereeffect.renderer.NeonRenderController

abstract class ColorFillWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<ColorFillRenderController>() {

    final override val effectId =
        if (reverseEffect) "COLORFILL_REVERSE" else "COLORFILL"
    final override val lockedProgress = if (reverseEffect) 0f else 1f
    final override val unlockedProgress = if (reverseEffect) 1f else 0f
    final override val defaultAnimationDurationMs = 1_500L

    final override fun createEffectRenderer(): ColorFillRenderController {
        return ColorFillRenderController(applicationContext, isReverse = reverseEffect)
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: ColorFillRenderController
    ) {
        renderer.attach(engine)
    }

    final override fun configureRenderer(
        renderer: ColorFillRenderController,
        preferences: SharedPreferences
    ) {
        renderer.configure(
            dimLevel = preferences.getFloat("dim_level", 0f),
            originX = preferences.getFloat("origin_x", 0.5f),
            originY = preferences.getFloat("origin_y", 0.8f)
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
     * one per engine. This only tells it when to idle: a live wallpaper
     * service can have a settings-preview engine and the real wallpaper alive
     * at once, so anything service-wide would be torn down by whichever engine
     * happened to die first.
     */
    final override fun onEngineVisibilityChanged(
        renderer: ColorFillRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(renderer: ColorFillRenderController, progress: Float) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: ColorFillRenderController) {
        renderer.reloadTexture()
    }

    final override fun queuePlaylistTransition(
        renderer: ColorFillRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    final override fun releaseRenderer(renderer: ColorFillRenderController) {
        renderer.release()
    }
}

abstract class FrostedWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<FrostedRenderController>() {

    final override val effectId =
        if (reverseEffect) "FROSTED_REVERSE" else "FROSTED"
    final override val lockedProgress = if (reverseEffect) 1f else 0f
    final override val unlockedProgress = if (reverseEffect) 0f else 1f
    final override val defaultAnimationDurationMs = 500L
    final override val initialProgress: Float? = if (reverseEffect) 1f else null
    final override val blurDrawerWhenHidden = reverseEffect

    final override fun createEffectRenderer(): FrostedRenderController {
        return FrostedRenderController(applicationContext, isReverse = reverseEffect)
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: FrostedRenderController
    ) {
        renderer.attach(engine)
    }

    final override fun configureRenderer(
        renderer: FrostedRenderController,
        preferences: SharedPreferences
    ) {
        renderer.configure(
            dimLevel = preferences.getFloat("dim_level", 0.2f),
            enableNoise = preferences.getBoolean("enable_noise", false),
            noiseScale = preferences.getFloat("noise_scale", 2_000f),
            noiseStrength = preferences.getFloat("noise_strength", 0.06f),
            blurRadius = preferences.getFloat("frosted_blur_radius", 200f)
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
     * one per engine. This only tells it when to idle: a live wallpaper
     * service can have a settings-preview engine and the real wallpaper alive
     * at once, so anything service-wide would be torn down by whichever engine
     * happened to die first.
     */
    final override fun onEngineVisibilityChanged(
        renderer: FrostedRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(
        renderer: FrostedRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: FrostedRenderController) {
        renderer.reloadTexture()
    }

    final override fun queuePlaylistTransition(
        renderer: FrostedRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    final override fun setDrawerBlurred(
        renderer: FrostedRenderController,
        blurred: Boolean
    ) {
        renderer.setDrawerBlurred(blurred)
    }

    final override fun releaseRenderer(renderer: FrostedRenderController) {
        renderer.release()
    }
}

abstract class HalftoneWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<HalftoneRenderController>() {

    final override val effectId =
        if (reverseEffect) "HALFTONE_REVERSE" else "HALFTONE"
    final override val lockedProgress = HalftoneProgressPolicy.LOCKED_PROGRESS
    final override val unlockedProgress = HalftoneProgressPolicy.UNLOCKED_PROGRESS
    final override val defaultAnimationDurationMs = 500L

    final override fun createEffectRenderer(): HalftoneRenderController {
        return HalftoneRenderController(applicationContext, isReverse = reverseEffect)
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: HalftoneRenderController
    ) {
        renderer.attach(engine)
    }

    final override fun configureRenderer(
        renderer: HalftoneRenderController,
        preferences: SharedPreferences
    ) {
        renderer.configure(
            dimLevel = preferences.readFloat("dim_level", 0f),
            dotSize = preferences.readFloat("halftone_dot_size", 12f),
            grayscale = preferences.readBoolean("halftone_grayscale", false),
            backgroundOnly = preferences.readBoolean(
                SubjectIsolationPolicy.HALFTONE_BACKGROUND_ONLY_KEY,
                false
            )
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
     * one per engine. This only tells it when to idle: a live wallpaper
     * service can have a settings-preview engine and the real wallpaper alive
     * at once, so anything service-wide would be torn down by whichever engine
     * happened to die first.
     */
    final override fun onEngineVisibilityChanged(
        renderer: HalftoneRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(
        renderer: HalftoneRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: HalftoneRenderController) {
        renderer.reloadTexture()
    }

    final override fun queuePlaylistTransition(
        renderer: HalftoneRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    final override fun releaseRenderer(renderer: HalftoneRenderController) {
        renderer.release()
    }
}

abstract class NeonWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<NeonRenderController>() {

    final override val effectId = if (reverseEffect) "NEON_REVERSE" else "NEON"
    final override val lockedProgress = 0f
    final override val unlockedProgress = 1f
    final override val defaultAnimationDurationMs = 1_000L

    final override fun createEffectRenderer(): NeonRenderController {
        return NeonRenderController(
            applicationContext,
            isReverse = reverseEffect
        )
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: NeonRenderController
    ) {
        renderer.attach(engine)
    }

    final override fun configureRenderer(
        renderer: NeonRenderController,
        preferences: SharedPreferences
    ) {
        renderer.configure(
            dimLevel = preferences.readFloat("dim_level", 0f),
            lineWidth = preferences.readFloat("neon_line_width", 1.5f),
            sensitivity = preferences.readFloat("neon_sensitivity", 0.5f),
            subjectSegmentationEnabled = preferences.readBoolean(
                CanvasSubjectSettings.ENABLED_KEY,
                false
            )
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
     * one per engine. This only tells it when to idle: a live wallpaper
     * service can have a settings-preview engine and the real wallpaper alive
     * at once, so anything service-wide would be torn down by whichever engine
     * happened to die first.
     */
    final override fun onEngineVisibilityChanged(
        renderer: NeonRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(
        renderer: NeonRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: NeonRenderController) {
        renderer.reloadTexture()
    }

    final override fun queuePlaylistTransition(
        renderer: NeonRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    final override fun releaseRenderer(renderer: NeonRenderController) {
        renderer.release()
    }
}

private fun SharedPreferences.readBoolean(key: String, fallback: Boolean): Boolean {
    return try {
        getBoolean(key, fallback)
    } catch (failure: ClassCastException) {
        Log.w("EffectPreferences", "Preference '$key' has the wrong type", failure)
        fallback
    }
}

private fun SharedPreferences.readFloat(key: String, fallback: Float): Float {
    return try {
        getFloat(key, fallback)
    } catch (failure: ClassCastException) {
        Log.w("EffectPreferences", "Preference '$key' has the wrong type", failure)
        fallback
    }
}
