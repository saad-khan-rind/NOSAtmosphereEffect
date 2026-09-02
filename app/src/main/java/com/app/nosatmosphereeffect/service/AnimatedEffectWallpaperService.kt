package com.app.nosatmosphereeffect.service

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.SurfaceHolder
import android.view.animation.LinearInterpolator
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.helper.EffectStatePolicy
import com.app.nosatmosphereeffect.helper.PlaylistRotationController
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorPolicy
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorPreferences
import com.app.nosatmosphereeffect.helper.WallpaperBehaviorSettings
import com.app.nosatmosphereeffect.renderer.backend.BackendReselectableRenderer
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeSession
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatusRepository
import com.app.nosatmosphereeffect.renderer.status.VulkanDeviceCapability
import java.util.IdentityHashMap

abstract class AnimatedEffectWallpaperService<R : Any> : GLWallpaperService() {

    protected abstract val effectId: String
    protected abstract val lockedProgress: Float
    protected abstract val unlockedProgress: Float
    protected abstract val defaultAnimationDurationMs: Long
    protected open val initialProgress: Float? = null
    protected open val sleepingProgress: Float = 0f
    protected open val blurDrawerWhenHidden: Boolean = false

    private val activeEngines = mutableSetOf<EffectEngine>()

    protected abstract fun createEffectRenderer(): R
    protected abstract fun attachEffectRenderer(engine: GLEngine, renderer: R)
    protected abstract fun configureRenderer(renderer: R, preferences: SharedPreferences)
    protected abstract fun setEffectProgress(renderer: R, progress: Float)
    protected abstract fun reloadRenderer(renderer: R)
    protected abstract fun queuePlaylistTransition(renderer: R, bitmap: Bitmap)

    protected open fun setFixedEffectState(renderer: R, effectApplied: Boolean) {
        val endpoints = EffectStatePolicy.endpoints(effectId)
        setEffectProgress(
            renderer,
            if (effectApplied) endpoints.appliedProgress else endpoints.originalProgress
        )
    }

    protected open fun setDrawerBlurred(renderer: R, blurred: Boolean) = Unit

    protected open fun onRendererAttached(renderer: R, requestRender: () -> Unit) = Unit

    /**
     * Called on every engine visibility change, before the transition-specific
     * handling below. Subclasses use it to idle work that only matters while
     * the wallpaper is on screen.
     */
    protected open fun onEngineVisibilityChanged(renderer: R?, visible: Boolean) = Unit

    protected open fun releaseRenderer(renderer: R) = Unit

    final override fun onCreateEngine(): Engine {
        return EffectEngine().also(activeEngines::add)
    }

    final override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMode = when (
            newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        ) {
            Configuration.UI_MODE_NIGHT_YES -> true
            Configuration.UI_MODE_NIGHT_NO -> false
            else -> null
        } ?: return

        activeEngines.toList().forEach { engine ->
            engine.handleThemeChange(nightMode)
        }
    }

    inner class EffectEngine : GLEngine() {
        private val logTag = this@AnimatedEffectWallpaperService::class.java.simpleName
        private var renderer: R? = null
        private var animator: ValueAnimator? = null
        private var timing = EffectTiming(
            pollIntervalMs = defaultPollIntervalMs(),
            lockDelayMs = defaultLockDelayMs(),
            animationDurationMs = defaultAnimationDurationMs
        )
        private var behavior = WallpaperBehaviorSettings()
        private var keyguardManager: KeyguardManager? = null
        private var keyguardLookupAttempted = false
        private var keyguardFailureLogged = false

        private val events = WallpaperEventController(
            context = this@AnimatedEffectWallpaperService,
            logTag = logTag,
            timing = { timing },
            transitionsEnabled = { behavior.transitionsEnabled },
            isKeyguardLocked = ::isKeyguardLocked,
            onUnlock = ::playUnlockAnimation,
            onPrepareForLock = ::prepareForNextUnlock,
            onScreenOff = {
                if (!isPreview) {
                    rotateWallpaper()
                }
            },
            onReload = ::reloadWallpaper,
            onConfigUpdate = ::updateRendererConfig
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            val createdRenderer = try {
                createEffectRenderer()
            } catch (failure: RuntimeException) {
                Log.e(logTag, "Unable to create the wallpaper renderer", failure)
                return
            }

            renderer = createdRenderer
            initialProgress?.let { progress ->
                setEffectProgress(createdRenderer, progress)
            }

            runInitializationStep("load renderer settings") {
                applyRendererConfig(createdRenderer)
            }
            if (!behavior.transitionsEnabled) {
                applyFixedState(createdRenderer, isKeyguardLocked())
            }
            runInitializationStep("attach renderer callbacks") {
                onRendererAttached(createdRenderer, ::requestRender)
            }

            try {
                attachEffectRenderer(this, createdRenderer)
            } catch (failure: RuntimeException) {
                Log.e(logTag, "Unable to attach the wallpaper renderer", failure)
                releaseCurrentRenderer()
                return
            }

            requestRender()
            notifySystemColorsChanged()
            runInitializationStep("apply the current theme") {
                currentNightMode()?.let(::handleThemeChange)
            }
            runInitializationStep("start wallpaper event handling") {
                events.start(initiallyLocked = isKeyguardLocked())
            }
        }

        override fun onDestroy() {
            activeEngines.remove(this)
            events.close()
            animator?.cancel()
            animator = null
            try {
                super.onDestroy()
            } finally {
                releaseCurrentRenderer()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            onEngineVisibilityChanged(renderer, visible)
            if (!behavior.transitionsEnabled) {
                animator?.cancel()
                animator = null
                if (blurDrawerWhenHidden) {
                    renderer?.let { setDrawerBlurred(it, false) }
                }

                super.onVisibilityChanged(visible)
                if (!visible) return

                val locked = isKeyguardLocked()
                events.setLocked(locked)
                renderer?.let { applyFixedState(it, locked) }
                requestRender()
                return
            }

            if (!visible) {
                if (isDeviceInteractive()) {
                    if (blurDrawerWhenHidden) {
                        renderer?.let { setDrawerBlurred(it, true) }
                    }
                } else {
                    renderer?.let { setEffectProgress(it, sleepingProgress) }
                }
            }

            super.onVisibilityChanged(visible)

            if (!visible) return

            if (blurDrawerWhenHidden) {
                renderer?.let { setDrawerBlurred(it, false) }
            }

            val locked = isKeyguardLocked()
            events.setLocked(locked)
            if (locked) {
                animator?.cancel()
                renderer?.let { setEffectProgress(it, lockedProgress) }
                requestRender()
            } else {
                snapToHomeState()
            }
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            if (behavior.transitionsEnabled) return

            renderer?.let { currentRenderer ->
                applyFixedState(
                    currentRenderer = currentRenderer,
                    isLocked = isKeyguardLocked(),
                    wallpaperFlags = which
                )
            }
            requestRender()
        }

        fun handleThemeChange(isNightMode: Boolean) {
            rotateWallpaper(isThemeChange = true, currentNightMode = isNightMode)
        }

        private fun rotateWallpaper(
            isThemeChange: Boolean = false,
            currentNightMode: Boolean = false
        ) {
            try {
                PlaylistRotationController.rotateAsync(
                    context = applicationContext,
                    isThemeChange = isThemeChange,
                    currentNightMode = currentNightMode,
                    queueTransition = { bitmap ->
                        val currentRenderer = renderer
                        if (currentRenderer == null) {
                            bitmap.recycle()
                        } else {
                            queuePlaylistTransition(currentRenderer, bitmap)
                        }
                    },
                    requestRender = ::requestRender,
                    notifyColorsChanged = ::notifySystemColorsChanged
                )
            } catch (failure: RuntimeException) {
                Log.e(logTag, "Unable to rotate the wallpaper playlist", failure)
            }
        }

        private fun reloadWallpaper() {
            val currentRenderer = renderer
            if (currentRenderer == null) {
                Log.w(logTag, "Ignoring reload because the renderer is unavailable")
                return
            }

            val transitionsWereEnabled = behavior.transitionsEnabled
            runInitializationStep("refresh renderer settings") {
                applyRendererConfig(currentRenderer)
            }
            reconcileBehavior(currentRenderer, transitionsWereEnabled)
            reloadRenderer(currentRenderer)
            requestRender()
            notifySystemColorsChanged()
        }

        private fun updateRendererConfig() {
            val currentRenderer = renderer
            if (currentRenderer == null) {
                Log.w(logTag, "Ignoring configuration update because the renderer is unavailable")
                return
            }

            val transitionsWereEnabled = behavior.transitionsEnabled
            applyRendererConfig(currentRenderer)
            reconcileBehavior(currentRenderer, transitionsWereEnabled)
            (currentRenderer as? BackendReselectableRenderer)?.reselectBackend()
            requestRender()
            notifySystemColorsChanged()
        }

        private fun applyRendererConfig(currentRenderer: R) {
            timing = readTimingPreferences()
            behavior = WallpaperBehaviorPreferences.read(
                this@AnimatedEffectWallpaperService
            )
            val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            configureRenderer(currentRenderer, preferences)
        }

        private fun reconcileBehavior(
            currentRenderer: R,
            transitionsWereEnabled: Boolean
        ) {
            events.onTransitionModeChanged()
            when {
                !behavior.transitionsEnabled -> {
                    animator?.cancel()
                    animator = null
                    if (blurDrawerWhenHidden) {
                        setDrawerBlurred(currentRenderer, false)
                    }
                    applyFixedState(currentRenderer, isKeyguardLocked())
                }
                !transitionsWereEnabled -> {
                    animator?.cancel()
                    animator = null
                    if (isKeyguardLocked()) {
                        setEffectProgress(currentRenderer, lockedProgress)
                    } else {
                        setEffectProgress(currentRenderer, unlockedProgress)
                    }
                }
            }
        }

        private fun playUnlockAnimation() {
            val currentRenderer = renderer
            if (currentRenderer == null) {
                Log.w(logTag, "Ignoring unlock animation because the renderer is unavailable")
                return
            }

            if (!behavior.transitionsEnabled) {
                animator?.cancel()
                animator = null
                applyFixedState(currentRenderer, isLocked = false)
                requestRender()
                return
            }

            animator?.cancel()
            setEffectProgress(currentRenderer, lockedProgress)
            requestRender()
            animator = ValueAnimator.ofFloat(lockedProgress, unlockedProgress).apply {
                duration = timing.animationDurationMs
                interpolator = LinearInterpolator()
                addUpdateListener { valueAnimator ->
                    setEffectProgress(currentRenderer, valueAnimator.animatedValue as Float)
                    requestRender()
                }
                start()
            }
        }

        private fun snapToHomeState() {
            animator?.cancel()
            animator = null
            renderer?.let { currentRenderer ->
                if (behavior.transitionsEnabled) {
                    setEffectProgress(currentRenderer, unlockedProgress)
                } else {
                    applyFixedState(currentRenderer, isLocked = false)
                }
            }
            requestRender()
        }

        private fun prepareForNextUnlock() {
            animator?.cancel()
            animator = null
            renderer?.let { currentRenderer ->
                if (behavior.transitionsEnabled) {
                    setEffectProgress(currentRenderer, lockedProgress)
                } else {
                    applyFixedState(currentRenderer, isLocked = true)
                }
            }
            requestRender()
        }

        private fun applyFixedState(
            currentRenderer: R,
            isLocked: Boolean,
            wallpaperFlags: Int? = currentWallpaperFlags()
        ) {
            val surface = WallpaperBehaviorPolicy.resolveSurface(
                isHomeEngine = wallpaperFlags?.let {
                    it and WallpaperManager.FLAG_SYSTEM != 0
                } == true,
                isLockEngine = wallpaperFlags?.let {
                    it and WallpaperManager.FLAG_LOCK != 0
                } == true,
                isKeyguardLocked = isLocked
            )
            val effectApplied = behavior.alwaysAppliedTarget.showsEffectOn(surface)
            setFixedEffectState(currentRenderer, effectApplied)
        }

        private fun currentWallpaperFlags(): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
            return try {
                getWallpaperFlags()
            } catch (failure: RuntimeException) {
                Log.w(logTag, "Unable to identify the wallpaper destination", failure)
                null
            }
        }

        private fun readTimingPreferences(): EffectTiming {
            val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            return EffectTiming(
                pollIntervalMs = preferences.readValidatedLong(
                    key = POLL_INTERVAL_KEY,
                    fallback = defaultPollIntervalMs(),
                    minimum = 1L
                ),
                lockDelayMs = preferences.readValidatedLong(
                    key = LOCK_DELAY_KEY,
                    fallback = defaultLockDelayMs(),
                    minimum = 0L
                ),
                animationDurationMs = preferences.readValidatedLong(
                    key = ANIMATION_DURATION_KEY,
                    fallback = defaultAnimationDurationMs,
                    minimum = 0L
                )
            )
        }

        private fun SharedPreferences.readValidatedLong(
            key: String,
            fallback: Long,
            minimum: Long
        ): Long {
            val value = try {
                getLong(key, fallback)
            } catch (failure: ClassCastException) {
                Log.e(logTag, "Preference '$key' has the wrong type; using $fallback", failure)
                return fallback
            }

            if (value >= minimum) return value

            Log.w(logTag, "Preference '$key' is $value; using $fallback")
            return fallback
        }

        private fun currentNightMode(): Boolean? {
            return when (
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            ) {
                Configuration.UI_MODE_NIGHT_YES -> true
                Configuration.UI_MODE_NIGHT_NO -> false
                else -> null
            }
        }

        private fun isKeyguardLocked(): Boolean {
            if (!keyguardLookupAttempted) {
                keyguardLookupAttempted = true
                keyguardManager = try {
                    getSystemService(KeyguardManager::class.java)
                } catch (failure: RuntimeException) {
                    logKeyguardFailure("Unable to access the keyguard service", failure)
                    null
                }
            }

            val manager = keyguardManager
            if (manager == null) {
                logKeyguardFailure("Keyguard service is unavailable")
                return true
            }

            return try {
                manager.isKeyguardLocked
            } catch (failure: RuntimeException) {
                logKeyguardFailure("Unable to query the keyguard state", failure)
                true
            }
        }

        private fun logKeyguardFailure(message: String, failure: RuntimeException? = null) {
            if (keyguardFailureLogged) return
            keyguardFailureLogged = true
            if (failure == null) {
                Log.e(logTag, message)
            } else {
                Log.e(logTag, message, failure)
            }
        }

        private fun isDeviceInteractive(): Boolean {
            val powerManager = try {
                getSystemService(PowerManager::class.java)
            } catch (failure: RuntimeException) {
                Log.e(logTag, "Unable to access the power service", failure)
                null
            }

            if (powerManager == null) {
                Log.w(logTag, "Power service is unavailable; assuming the display is active")
                return true
            }

            return try {
                powerManager.isInteractive
            } catch (failure: RuntimeException) {
                Log.e(logTag, "Unable to query the display state", failure)
                true
            }
        }

        private fun releaseCurrentRenderer() {
            val currentRenderer = renderer ?: return
            renderer = null
            try {
                releaseRenderer(currentRenderer)
            } catch (failure: RuntimeException) {
                Log.e(logTag, "Unable to release the wallpaper renderer", failure)
            }
        }

        private inline fun runInitializationStep(
            step: String,
            action: () -> Unit
        ) {
            try {
                action()
            } catch (failure: RuntimeException) {
                Log.e(logTag, "Unable to $step; continuing with safe defaults", failure)
            }
        }
    }

    private fun defaultPollIntervalMs(): Long {
        return if (isSamsungDevice()) SAMSUNG_POLL_INTERVAL_MS else DEFAULT_POLL_INTERVAL_MS
    }

    private fun defaultLockDelayMs(): Long {
        return if (isSamsungDevice()) SAMSUNG_LOCK_DELAY_MS else DEFAULT_LOCK_DELAY_MS
    }

    private fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }

    private companion object {
        const val PREFERENCES_NAME = "app_prefs"
        const val POLL_INTERVAL_KEY = "poll_interval"
        const val LOCK_DELAY_KEY = "lock_delay"
        const val ANIMATION_DURATION_KEY = "anim_duration"
        const val DEFAULT_POLL_INTERVAL_MS = 50L
        const val DEFAULT_LOCK_DELAY_MS = 800L
        const val SAMSUNG_POLL_INTERVAL_MS = 30_000L
        const val SAMSUNG_LOCK_DELAY_MS = 0L
    }
}

abstract class GlAnimatedEffectWallpaperService<R : GLSurfaceView.Renderer> :
    AnimatedEffectWallpaperService<R>() {

    private val runtimeSessions = IdentityHashMap<R, RendererRuntimeSession>()

    final override fun attachEffectRenderer(engine: GLEngine, renderer: R) {
        engine.setRenderer(renderer)
        runCatching {
            val session = RendererRuntimeStatusRepository.recordSelection(
                context = applicationContext,
                effectId = effectId,
                selectedBackend = GraphicsBackend.OPENGL_ES,
                vulkanCapability = VulkanDeviceCapability.UNKNOWN,
                probedVulkanApiVersion = null
            )
            synchronized(runtimeSessions) {
                runtimeSessions[renderer] = session
            }
            RendererRuntimeStatusRepository.recordOpenGlActive(
                context = applicationContext,
                session = session
            )
        }.onFailure { failure ->
            Log.w(
                this::class.java.simpleName,
                "Unable to publish the OpenGL ES renderer status",
                failure
            )
        }
    }

    final override fun releaseRenderer(renderer: R) {
        val session = synchronized(runtimeSessions) {
            runtimeSessions.remove(renderer)
        }
        session?.let {
            runCatching {
                RendererRuntimeStatusRepository.recordReleased(
                    context = applicationContext,
                    session = it
                )
            }.onFailure { failure ->
                Log.w(
                    this::class.java.simpleName,
                    "Unable to publish the OpenGL ES renderer release",
                    failure
                )
            }
        }
        releaseOpenGlRenderer(renderer)
    }

    protected open fun releaseOpenGlRenderer(renderer: R) = Unit
}
