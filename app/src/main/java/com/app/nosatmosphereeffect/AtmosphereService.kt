package com.app.nosatmosphereeffect

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.opengl.GLSurfaceView
import android.view.animation.PathInterpolator

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

        // State Flag: True = Phone is locked/sleeping. False = Unlocked/Using apps.
        private var isLocked = true

        // --- RECEIVER FOR ACCURATE UNLOCK EVENTS ---
        private val systemEventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // 1. Phone went to sleep/AOD
                        isLocked = true
                        prepareForNextUnlock()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        // 2. Phone was JUST unlocked (Fingerprint/Pin/Swipe success)
                        // This fires AFTER the Keyguard is gone. Perfect timing for AOD.
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
                setRenderer(myRenderer!!)
            }

            // Register for Screen Off AND User Present (Unlock)
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            filter.addAction(Intent.ACTION_USER_PRESENT) // <--- The Unlock Signal
            filter.addAction("com.app.nosatmosphereeffect.RELOAD_WALLPAPER")

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(systemEventReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(systemEventReceiver, filter)
            }
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
                // Determine context: Did we just unlock, or are we switching apps?
                if (isLocked) {
                    // We are visible, BUT the phone thinks it's still locked/unlocking.
                    // DO NOT ANIMATE YET. Wait for ACTION_USER_PRESENT to fire.
                    // Just ensure we are in the "Ready" state (0.4).
                    myRenderer?.blurStrength = 0.4f
                    requestRender()
                } else {
                    // Phone is already unlocked. We are just returning from an App.
                    // Snap to full blur instantly. No animation.
                    snapToHomeState()
                }
            }
        }

        // --- ANIMATIONS ---

        private fun playUnlockAnimation() {
            val targetRenderer = myRenderer ?: return

            // New random swirl for this unlock
            targetRenderer.seed = (Math.random() * 500.0).toFloat()

            blurAnimator?.cancel()

            // Force start position (0.4)
            targetRenderer.blurStrength = 0.4f
            requestRender()

            // 2.0 Second Fluid Animation
            blurAnimator = ValueAnimator.ofFloat(0.4f, 1.0f).apply {
                duration = 2000
                interpolator = PathInterpolator(0.1f, 0.0f, 0.2f, 1.0f) // Fast start, slow settle

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
            // Reset to 40% silently while screen is off
            targetRenderer.blurStrength = 0.4f
            requestRender()
        }
    }
}