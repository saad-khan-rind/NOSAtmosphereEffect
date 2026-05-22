package com.app.nosatmosphereeffect.service

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.animation.LinearInterpolator
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.renderer.HalftoneRenderer
import java.io.File
import androidx.core.content.edit

class HalftoneService : GLWallpaperService() {

    private val activeEngines = mutableSetOf<HalftoneEngine>()

    override fun onCreateEngine(): Engine {
        val engine = HalftoneEngine()
        activeEngines.add(engine)
        return engine
    }

    override fun getRenderer(): GLSurfaceView.Renderer {
        return HalftoneRenderer(applicationContext, isReverse = false)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val uiMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES ||
            uiMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            val isNightMode = (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            activeEngines.forEach { engine -> engine.handleThemeChange(isNightMode) }
        }
    }

    inner class HalftoneEngine : GLEngine() {
        private var cachedColors: WallpaperColors? = null
        private var pollInterval: Long = 50L
        private var lockDelay: Long = 0L
        private var animDuration: Long = 500L

        private var myRenderer: HalftoneRenderer? = null
        private var blurAnimator: ValueAnimator? = null
        private var isLocked: Boolean = true
        private var enableSystemColorUpdate: Boolean = false
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        private val resetRunnable = Runnable {
            prepareForNextUnlock()
        }

        private val rotationRunnable = Runnable {
            rotateWallpaper()
        }

        fun handleThemeChange(isNightMode: Boolean) {
            rotateWallpaper(isThemeChange = true, currentNightMode = isNightMode)
        }

        private fun rotateWallpaper(isThemeChange: Boolean = false, currentNightMode: Boolean = false) {
            Thread {
                val playlistDir = File(filesDir, "playlist")
                val playlistFiles = playlistDir.listFiles { _, name -> name.endsWith(".jpg") }
                if (playlistFiles == null || playlistFiles.size <= 1) return@Thread

                val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
                val intervalMinutes = prefs.getLong("rotation_interval_minutes", 0)

                if (isThemeChange) {
                    if (intervalMinutes == -1L) {
                        val savedTheme = prefs.getInt("active_theme_state", -1)
                        val newThemeState = if (currentNightMode) 1 else 0
                        if (savedTheme != newThemeState) {
                            prefs.edit { putInt("active_theme_state", newThemeState) }
                            executeRotationRingBuffer(prefs)
                        }
                    }
                    return@Thread
                }

                if (intervalMinutes > 0) {
                    val lastRotationTime = prefs.getLong("last_rotation_timestamp", 0)
                    val currentTime = System.currentTimeMillis()
                    val diffMinutes = (currentTime - lastRotationTime) / 60000
                    if (diffMinutes < intervalMinutes) return@Thread
                } else if (intervalMinutes == -1L) {
                    return@Thread
                }
                executeRotationRingBuffer(prefs)
            }.start()
        }

        private fun executeRotationRingBuffer(prefs: android.content.SharedPreferences) {
            val nextFile = File(filesDir, "next_wallpaper.jpg")
            val activeFile = File(filesDir, "wallpaper.jpg")

            if (nextFile.exists()) {
                try {
                    val nextBitmap = BitmapFactory.decodeFile(nextFile.absolutePath)
                    if (nextBitmap != null) {
                        myRenderer?.queuePlaylistTransition(nextBitmap)
                        requestRender()
                        if (activeFile.exists()) activeFile.delete()
                        nextFile.renameTo(activeFile)
                        cachedColors = null
                        prefs.edit {
                            putLong(
                                "last_rotation_timestamp",
                                System.currentTimeMillis()
                            )
                        }
                        notifyColorsChanged()
                    }
                } catch (e: Exception) { e.printStackTrace() }
                prepareNextWallpaper()
            } else {
                prepareNextWallpaper()
            }
        }

        private fun prepareNextWallpaper() {
            Thread {
                try {
                    val playlistDir = File(filesDir, "playlist")
                    if (playlistDir.exists() && playlistDir.isDirectory) {
                        val files = playlistDir.listFiles { _, name -> name.endsWith(".jpg") }
                        if (!files.isNullOrEmpty() && files.size > 1) {
                            val prefs = getSharedPreferences("wallpaper_prefs", MODE_PRIVATE)
                            val lastUsedName = prefs.getString("last_playlist_image", "")
                            val candidates = files.filter { it.name != lastUsedName }
                            val validFiles = candidates.ifEmpty { files.toList() }
                            val randomFile = validFiles.random()
                            prefs.edit { putString("last_playlist_image", randomFile.name) }
                            val nextFile = File(filesDir, "next_wallpaper.jpg")
                            randomFile.copyTo(nextFile, overwrite = true)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }.start()
        }

        override fun onComputeColors(): WallpaperColors? {
            if (!enableSystemColorUpdate) {
                if (cachedColors != null) cachedColors = null
                return super.onComputeColors()
            }
            if (cachedColors != null) return cachedColors
            try {
                val file = File(filesDir, "wallpaper.jpg")
                if (file.exists()) {
                    val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                    if (bitmap != null) {
                        cachedColors = WallpaperColors.fromBitmap(bitmap)
                        bitmap.recycle()
                        return cachedColors
                    }
                }
            } catch (_: Exception) { }
            return super.onComputeColors()
        }

        private val unlockChecker = object : Runnable {
            override fun run() {
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                if (!keyguardManager.isKeyguardLocked) {
                    isLocked = false
                    playUnlockAnimation()
                    handler.removeCallbacks(this)
                } else {
                    handler.postDelayed(this, pollInterval)
                }
            }
        }

        private val systemEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        isLocked = true
                        handler.removeCallbacks(unlockChecker)
                        handler.removeCallbacks(rotationRunnable)
                        handler.post(unlockChecker)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        handler.removeCallbacks(unlockChecker)
                        isLocked = true
                        handler.postDelayed(resetRunnable, lockDelay)
                        handler.postDelayed(rotationRunnable, lockDelay)
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        handler.removeCallbacks(resetRunnable)
                        handler.removeCallbacks(rotationRunnable)
                        if (isLocked) {
                            isLocked = false
                            playUnlockAnimation()
                            handler.removeCallbacks(unlockChecker)
                        }
                    }
                    "com.app.nosatmosphereeffect.RELOAD_WALLPAPER" -> {
                        cachedColors = null
                        myRenderer?.reloadTexture()
                        requestRender()
                        notifyColorsChanged()
                    }
                    "com.app.nosatmosphereeffect.UPDATE_CONFIG" -> {
                        updateRendererConfig()
                        requestRender()
                        notifyColorsChanged()
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder) {
            super.onCreate(surfaceHolder)
            val r = getRenderer()
            if (r is HalftoneRenderer) {
                myRenderer = r
                updateRendererConfig()
                setRenderer(myRenderer!!)
            }

            val currentUiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (currentUiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES ||
                currentUiMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
                handleThemeChange(currentUiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            }

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")
                addAction("com.app.nosatmosphereeffect.UPDATE_CONFIG")
            }
            registerReceiver(systemEventReceiver, filter, RECEIVER_EXPORTED)
        }

        override fun onDestroy() {
            super.onDestroy()
            activeEngines.remove(this)
            try { unregisterReceiver(systemEventReceiver) } catch (_: Exception) { }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                if (!km.isKeyguardLocked) isLocked = false

                if (isLocked) {
                    myRenderer?.blurStrength = 0.0f // Locked = Sharp
                    requestRender()
                } else {
                    snapToHomeState()
                }
            }
        }

        private fun playUnlockAnimation() {
            val targetRenderer = myRenderer ?: return
            blurAnimator?.cancel()
            targetRenderer.blurStrength = 0.0f
            requestRender()

            blurAnimator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
                duration = animDuration
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    targetRenderer.blurStrength = value
                    requestRender()
                }
            }
            blurAnimator?.start()
        }

        private fun snapToHomeState() {
            myRenderer?.blurStrength = 1.0f // Unlocked = Halftone
            requestRender()
        }

        private fun prepareForNextUnlock() {
            myRenderer?.blurStrength = 0.0f // Reset to Sharp
            requestRender()
        }

        private fun updateRendererConfig() {
            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
            myRenderer?.dimLevel = prefs.getFloat("dim_level", 0.0f)
            myRenderer?.dotSize = prefs.getFloat("halftone_dot_size", 12.0f)
            myRenderer?.grayscale = prefs.getBoolean("halftone_grayscale", false)
            enableSystemColorUpdate = prefs.getBoolean("notify_system_colors", false)

            val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            pollInterval = prefs.getLong("poll_interval", if (isSamsung) 30000L else 50L)
            lockDelay = prefs.getLong("lock_delay", if (isSamsung) 0L else 800L)
            animDuration = prefs.getLong("anim_duration", 500L)
        }
    }
}