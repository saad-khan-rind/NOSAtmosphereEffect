package com.app.nosatmosphereeffect

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.GLSurfaceView
import android.view.animation.LinearInterpolator
import android.os.Build
import java.util.Locale

class AtmosphereService : GLWallpaperService() {

    override fun onCreateEngine(): Engine {
        return AtmosphereEngine()
    }

    override fun getRenderer(): GLSurfaceView.Renderer {
        return AtmosphereRenderer(applicationContext)
    }

    inner class AtmosphereEngine : GLEngine() {

        private var myRenderer: AtmosphereRenderer? = null
        private var blurAnimator: ValueAnimator? = null
        private var isLocked = true
        private val isSamsungDevice: Boolean
            get() {
                val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
                val brand = Build.BRAND.lowercase(Locale.ROOT)
                return manufacturer.contains("samsung") || brand.contains("samsung")
            }

        private val systemEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isLocked = true
                        prepareForNextUnlock()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        isLocked = false
                        playUnlockAnimation()
                    }
                    "com.app.nosatmosphereeffect.RELOAD_WALLPAPER" -> {
                        myRenderer?.reloadTexture()
                        requestRender()
                    }
                }
            }
        }

        override fun onCreate(surfaceHolder: android.view.SurfaceHolder) {
            super.onCreate(surfaceHolder)

            val r = getRenderer()
            if (r is AtmosphereRenderer) {
                myRenderer = r
                myRenderer?.isSamsung = isSamsungDevice
                setRenderer(myRenderer!!)
            }

            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_USER_PRESENT)
            filter.addAction("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")

            registerReceiver(systemEventReceiver, filter, Context.RECEIVER_EXPORTED)
        }

        override fun onDestroy() {
            super.onDestroy()
            try {
                unregisterReceiver(systemEventReceiver)
            } catch (e: Exception) { }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                if (isLocked) {
                    myRenderer?.blurStrength = if (isSamsungDevice) 0.4f else 0.0f
                    requestRender()
                } else {
                    snapToHomeState()
                }
            }
        }

        private fun playUnlockAnimation() {
            val targetRenderer = myRenderer ?: return

            targetRenderer.seed = (Math.random() * 1000.0).toFloat()

            blurAnimator?.cancel()
            targetRenderer.blurStrength = 0.0f
            requestRender()

            blurAnimator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
                duration = 2500 // 2.5 Seconds total

                // LINEAR: We let the Shader handle the "Wait -> Blur -> Move" timing logic
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
            val targetRenderer = myRenderer ?: return
            blurAnimator?.cancel()
            targetRenderer.blurStrength = 1.0f
            requestRender()
        }

        private fun prepareForNextUnlock() {
            val targetRenderer = myRenderer ?: return
            blurAnimator?.cancel()
            targetRenderer.blurStrength = if (isSamsungDevice) 0.4f else 0.0f
            requestRender()
        }
    }
}