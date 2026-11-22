package com.app.nosatmosphereeffect.service

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.effect.CanvasSubjectSettings
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.subject.SubjectIsolationPolicy
import com.app.nosatmosphereeffect.renderer.colorfill.ColorFillRenderController
import com.app.nosatmosphereeffect.renderer.duo.DuoHomeImage
import com.app.nosatmosphereeffect.renderer.duo.DuoRenderController
import com.app.nosatmosphereeffect.renderer.duo.DuoRenderState
import com.app.nosatmosphereeffect.renderer.duo.DuoStyle
import com.app.nosatmosphereeffect.renderer.frosted.FrostedRenderController
import com.app.nosatmosphereeffect.renderer.frosted.FrostedRenderer
import com.app.nosatmosphereeffect.renderer.halftone.HalftoneProgressPolicy
import com.app.nosatmosphereeffect.renderer.halftone.HalftoneRenderController
import com.app.nosatmosphereeffect.renderer.zoom.ZoomRenderState
import com.app.nosatmosphereeffect.renderer.zoom.ZoomRenderController
import com.app.nosatmosphereeffect.renderer.vhs.VhsRenderState
import com.app.nosatmosphereeffect.renderer.vhs.VhsRenderController
import com.app.nosatmosphereeffect.renderer.canvassketch.CanvasSketchRenderController
import java.util.concurrent.Executors

abstract class ColorFillWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<ColorFillRenderController>() {

    final override val effectId =
        if (reverseEffect) "COLOR_FILL_REVERSE" else "COLOR_FILL"
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
            readClockState(preferences)
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
            readClockState(preferences)
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

    final override val effectId = if (reverseEffect) "HALFTONE_REVERSE" else "HALFTONE"
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
            readClockState(preferences)
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

abstract class VhsWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<VhsRenderController>() {

    final override val effectId = if (reverseEffect) "VHS_REVERSE" else "VHS"
    final override val lockedProgress = VhsRenderState.LOCKED_PROGRESS
    final override val unlockedProgress = VhsRenderState.UNLOCKED_PROGRESS
    final override val defaultAnimationDurationMs = VhsRenderState.DEFAULT_DURATION_MS

    final override fun createEffectRenderer(): VhsRenderController {
        return VhsRenderController(applicationContext, isReverse = reverseEffect)
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: VhsRenderController
    ) {
        renderer.attach(engine)
    }

    final override fun configureRenderer(
        renderer: VhsRenderController,
        preferences: SharedPreferences
    ) {
        renderer.configure(dimLevel = preferences.readFloat("dim_level", 0f))
        // Playlist and theme modes rotate the image underneath the clock, so
        // the clock stays off there — a position calibrated against one photo
        // is wrong for the next. See AtmosphereClockPolicy.resolveEnabled.
        renderer.configureClock(
            readClockState(preferences)
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
        renderer: VhsRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(
        renderer: VhsRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: VhsRenderController) {
        renderer.reloadTexture()
    }

    final override fun queuePlaylistTransition(
        renderer: VhsRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    final override fun releaseRenderer(renderer: VhsRenderController) {
        renderer.release()
    }
}

abstract class DuoWallpaperService : AnimatedEffectWallpaperService<DuoRenderController>() {

    final override val effectId = DuoRenderController.EFFECT_ID
    final override val lockedProgress = DuoRenderState.LOCKED_PROGRESS
    final override val unlockedProgress = DuoRenderState.UNLOCKED_PROGRESS
    final override val defaultAnimationDurationMs = DuoRenderState.DEFAULT_DURATION_MS

    /** Copies the next home photo off the main thread. */
    private val homeRotation = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DuoHomeRotation").apply { isDaemon = true }
    }

    final override fun createEffectRenderer(): DuoRenderController {
        return DuoRenderController(applicationContext)
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: DuoRenderController
    ) {
        renderer.attach(engine)
    }

    // No clock here: Duo's two photos have no one subject to fit it to.
    final override fun configureRenderer(
        renderer: DuoRenderController,
        preferences: SharedPreferences
    ) {
        renderer.configure(
            dimLevel = preferences.readFloat("dim_level", 0f),
            style = DuoStyle.read(preferences)
        )
    }

    final override fun onEngineVisibilityChanged(
        renderer: DuoRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(
        renderer: DuoRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: DuoRenderController) {
        renderer.reloadTexture()
    }

    /** The lock photo's playlist. */
    final override fun queuePlaylistTransition(
        renderer: DuoRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    /** The home photo's own playlist (DuoHomeImage): another one at random on each lock. */
    final override fun rotateOwnImages(renderer: DuoRenderController) {
        val context = applicationContext
        homeRotation.execute {
            val changed = runCatching { DuoHomeImage.rotate(context) }
                .onFailure { Log.e("DuoWallpaperService", "Unable to move to the next home photo", it) }
                .getOrDefault(false)
            if (changed) renderer.reloadHomeImage()
        }
    }

    final override fun releaseRenderer(renderer: DuoRenderController) {
        renderer.release()
    }

    override fun onDestroy() {
        homeRotation.shutdown()
        super.onDestroy()
    }
}

abstract class ZoomWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<ZoomRenderController>() {

    final override val effectId = if (reverseEffect) "ZOOM_REVERSE" else "ZOOM"
    final override val lockedProgress = ZoomRenderState.LOCKED_PROGRESS
    final override val unlockedProgress = ZoomRenderState.UNLOCKED_PROGRESS
    final override val defaultAnimationDurationMs = ZoomRenderState.DEFAULT_DURATION_MS

    final override fun createEffectRenderer(): ZoomRenderController {
        return ZoomRenderController(applicationContext, isReverse = reverseEffect)
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: ZoomRenderController
    ) {
        renderer.attach(engine)
    }

    final override fun configureRenderer(
        renderer: ZoomRenderController,
        preferences: SharedPreferences
    ) {
        renderer.configure(dimLevel = preferences.readFloat("dim_level", 0f))
        // Playlist and theme modes rotate the image underneath the clock, so
        // the clock stays off there — a position calibrated against one photo
        // is wrong for the next. See AtmosphereClockPolicy.resolveEnabled.
        renderer.configureClock(
            readClockState(preferences)
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
        renderer: ZoomRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(
        renderer: ZoomRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: ZoomRenderController) {
        renderer.reloadTexture()
    }

    final override fun queuePlaylistTransition(
        renderer: ZoomRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    final override fun releaseRenderer(renderer: ZoomRenderController) {
        renderer.release()
    }
}

abstract class CanvasSketchWallpaperService protected constructor(
    private val reverseEffect: Boolean
) : AnimatedEffectWallpaperService<CanvasSketchRenderController>() {

    final override val effectId = if (reverseEffect) "CANVAS_SKETCH_REVERSE" else "CANVAS_SKETCH"
    final override val lockedProgress = 0f
    final override val unlockedProgress = 1f
    final override val defaultAnimationDurationMs = 1_000L

    final override fun createEffectRenderer(): CanvasSketchRenderController {
        return CanvasSketchRenderController(
            applicationContext,
            isReverse = reverseEffect
        )
    }

    final override fun attachEffectRenderer(
        engine: GLWallpaperService.GLEngine,
        renderer: CanvasSketchRenderController
    ) {
        renderer.attach(engine)
    }

    final override fun configureRenderer(
        renderer: CanvasSketchRenderController,
        preferences: SharedPreferences
    ) {
        renderer.configure(
            dimLevel = preferences.readFloat("dim_level", 0f),
            lineWidth = preferences.readFloat("canvas_sketch_line_width", 1.5f),
            sensitivity = preferences.readFloat("canvas_sketch_sensitivity", 0.5f),
            subjectSegmentationEnabled = preferences.readBoolean(
                CanvasSubjectSettings.ENABLED_KEY,
                false
            )
        )
        // Playlist and theme modes rotate the image underneath the clock, so
        // the clock stays off there — a position calibrated against one photo
        // is wrong for the next. See AtmosphereClockPolicy.resolveEnabled.
        renderer.configureClock(
            readClockState(preferences)
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
        renderer: CanvasSketchRenderController?,
        visible: Boolean
    ) {
        renderer?.setEngineVisible(visible)
    }

    final override fun setEffectProgress(
        renderer: CanvasSketchRenderController,
        progress: Float
    ) {
        renderer.setProgress(progress)
    }

    final override fun reloadRenderer(renderer: CanvasSketchRenderController) {
        renderer.reloadTexture()
    }

    final override fun queuePlaylistTransition(
        renderer: CanvasSketchRenderController,
        bitmap: Bitmap
    ) {
        renderer.queuePlaylistTransition(bitmap)
    }

    final override fun releaseRenderer(renderer: CanvasSketchRenderController) {
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
