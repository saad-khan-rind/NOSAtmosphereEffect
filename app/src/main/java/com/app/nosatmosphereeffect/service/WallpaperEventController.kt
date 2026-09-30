package com.app.nosatmosphereeffect.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

internal data class EffectTiming(
    val pollIntervalMs: Long,
    val lockDelayMs: Long,
    val animationDurationMs: Long
)

internal class WallpaperEventController(
    private val context: Context,
    private val logTag: String,
    private val timing: () -> EffectTiming,
    private val transitionsEnabled: () -> Boolean,
    private val isKeyguardLocked: () -> Boolean,
    /** False while the screen is off or dozing (always-on display). */
    private val isInteractive: () -> Boolean,
    private val onUnlock: () -> Unit,
    private val onPrepareForLock: () -> Unit,
    private val onShowLocked: () -> Unit,
    private val onResumeHome: () -> Unit,
    private val onScreenOff: () -> Unit,
    private val onReload: () -> Unit,
    private val onConfigUpdate: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var locked = true
    private var closed = false
    private var systemReceiverRegistered = false
    private var appReceiverRegistered = false
    private var lastUnlockUptimeMs = NEVER_UNLOCKED
    /** When the current run of unlock polling began; see [UnlockPolling]. */
    private var pollingStartedUptimeMs = 0L
    /** Whether the wallpaper is on screen; polling is pointless while it is not. */
    private var wallpaperVisible = true

    /**
     * True while a locked keyguard reading arrived so soon after an unlock that
     * it is probably the OS settling (screen-off fingerprint unlock on some
     * skins reports the keyguard, visibility and screen events out of order).
     * The locked visuals are withheld until [confirmLocked] re-checks.
     */
    private var lockVisualDeferred = false

    private val prepareForLock = Runnable {
        runCallback("prepare the next unlock", onPrepareForLock)
    }

    private val confirmLocked = Runnable {
        if (closed || !locked) return@Runnable
        if (isKeyguardLocked()) {
            lockVisualDeferred = false
            runCallback("show the lock-screen state", onShowLocked)
        } else {
            completeUnlock()
        }
    }

    // Polls the keyguard while it is up, because USER_PRESENT can arrive late.
    // It must never outlive the lock screen it is watching: on Motorola and
    // Oppo the wallpaper can report itself visible after SCREEN_OFF has run —
    // entering the always-on display, for one — and a poll started then used
    // to keep going every 50 ms all night. The OEM battery managers flag that
    // as abnormal background use and force-stop the app, and Android answers a
    // force-stopped live wallpaper by restoring the default one.
    private val unlockChecker = object : Runnable {
        override fun run() {
            if (closed) return
            if (!isKeyguardLocked()) {
                completeUnlock()
                return
            }
            // Screen off or dozing: SCREEN_ON starts polling again.
            // Wallpaper hidden: becoming visible again reconciles the state.
            if (!wallpaperVisible || !safeIsInteractive()) return
            handler.postDelayed(
                this,
                UnlockPolling.nextDelayMs(
                    configuredMs = timing().pollIntervalMs,
                    elapsedMs = SystemClock.uptimeMillis() - pollingStartedUptimeMs
                )
            )
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_USER_PRESENT -> handleUserPresent()
            }
        }
    }

    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_RELOAD_WALLPAPER -> runCallback("reload the wallpaper", onReload)
                ACTION_UPDATE_CONFIG -> runCallback("update the wallpaper configuration", onConfigUpdate)
            }
        }
    }

    fun start(initiallyLocked: Boolean) {
        if (closed) return
        locked = initiallyLocked
        registerSystemReceiver()
        registerAppReceiver()
    }

    fun setLocked(value: Boolean) {
        locked = value
        if (!value) {
            handler.removeCallbacks(unlockChecker)
            handler.removeCallbacks(confirmLocked)
            lockVisualDeferred = false
        }
    }

    /** True shortly after an unlock, while the OS may still emit stale events. */
    fun isSettlingAfterUnlock(): Boolean {
        return !locked && SystemClock.uptimeMillis() - lastUnlockUptimeMs < UNLOCK_SETTLE_MS
    }

    /**
     * Reconciles the wallpaper becoming visible with the keyguard when
     * transitions are enabled, choosing exactly one of the unlock animation,
     * the locked state or the settled home state.
     */
    fun onVisible(keyguardLocked: Boolean) {
        if (closed) return
        wallpaperVisible = true
        if (!keyguardLocked) {
            if (locked) {
                // Still showing the locked state (e.g. the screen woke already
                // unlocked): animate once instead of snapping.
                completeUnlock()
            } else {
                runCallback("show the home-screen state", onResumeHome)
            }
            return
        }
        if (isSettlingAfterUnlock()) {
            deferLockVisual()
        } else if (!lockVisualDeferred) {
            locked = true
            runCallback("show the lock-screen state", onShowLocked)
        }
        startUnlockPolling()
    }

    /** The wallpaper left the screen: nothing it shows can change, so stop polling. */
    fun onHidden() {
        wallpaperVisible = false
        handler.removeCallbacks(unlockChecker)
    }

    fun onTransitionModeChanged() {
        if (transitionsEnabled()) return
        handler.removeCallbacks(unlockChecker)
        handler.removeCallbacks(prepareForLock)
        handler.removeCallbacks(confirmLocked)
        lockVisualDeferred = false
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(unlockChecker)
        handler.removeCallbacks(prepareForLock)
        handler.removeCallbacks(confirmLocked)
        unregisterSystemReceiver()
        unregisterAppReceiver()
    }

    private fun handleScreenOn() {
        if (closed) return
        handler.removeCallbacks(unlockChecker)
        if (transitionsEnabled()) {
            val keyguardLocked = isKeyguardLocked()
            // USER_PRESENT or a visibility change may already have played the
            // unlock; forcing `locked` here replayed it from the locked state.
            if (!locked && !keyguardLocked) return
            if (!locked && isSettlingAfterUnlock()) {
                deferLockVisual()
            } else {
                locked = true
            }
            startUnlockPolling()
        } else {
            locked = isKeyguardLocked()
            if (locked) {
                runCallback("show the fixed lock-screen state", onPrepareForLock)
            } else {
                runCallback("show the fixed home-screen state", onUnlock)
            }
        }
    }

    private fun handleScreenOff() {
        if (closed) return
        handler.removeCallbacks(unlockChecker)
        handler.removeCallbacks(prepareForLock)
        handler.removeCallbacks(confirmLocked)
        lockVisualDeferred = false
        lastUnlockUptimeMs = NEVER_UNLOCKED
        locked = true
        if (transitionsEnabled()) {
            handler.postDelayed(prepareForLock, timing().lockDelayMs)
        } else {
            runCallback("show the fixed lock-screen state", onPrepareForLock)
        }
        runCallback("rotate the wallpaper playlist", onScreenOff)
    }

    private fun handleUserPresent() {
        if (closed) return
        handler.removeCallbacks(prepareForLock)
        if (!locked) return
        completeUnlock()
    }

    private fun startUnlockPolling() {
        handler.removeCallbacks(unlockChecker)
        if (!safeIsInteractive()) return
        pollingStartedUptimeMs = SystemClock.uptimeMillis()
        handler.post(unlockChecker)
    }

    private fun safeIsInteractive(): Boolean =
        try {
            isInteractive()
        } catch (failure: RuntimeException) {
            Log.w(logTag, "Unable to read the screen state", failure)
            true
        }

    private fun deferLockVisual() {
        locked = true
        lockVisualDeferred = true
        handler.removeCallbacks(confirmLocked)
        handler.postDelayed(confirmLocked, LOCK_CONFIRM_DELAY_MS)
    }

    /**
     * Single exit from the locked state. Cancels every pending lock-side
     * callback (including a lock delay that has not fired yet), then plays the
     * unlock animation only if the locked state was actually shown.
     */
    private fun completeUnlock() {
        val lockVisualWasShown = !lockVisualDeferred
        locked = false
        lockVisualDeferred = false
        lastUnlockUptimeMs = SystemClock.uptimeMillis()
        handler.removeCallbacks(unlockChecker)
        handler.removeCallbacks(confirmLocked)
        handler.removeCallbacks(prepareForLock)
        if (!transitionsEnabled()) {
            runCallback("show the fixed home-screen state", onUnlock)
        } else if (lockVisualWasShown) {
            runCallback("play the unlock animation", onUnlock)
        } else {
            runCallback("show the home-screen state", onResumeHome)
        }
    }

    private fun registerSystemReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            context.registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
            systemReceiverRegistered = true
        } catch (failure: RuntimeException) {
            Log.e(logTag, "Unable to register the screen event receiver", failure)
        }
    }

    private fun registerAppReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_RELOAD_WALLPAPER)
            addAction(ACTION_UPDATE_CONFIG)
        }
        try {
            context.registerReceiver(appReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            appReceiverRegistered = true
        } catch (failure: RuntimeException) {
            Log.e(logTag, "Unable to register the wallpaper command receiver", failure)
        }
    }

    private fun unregisterSystemReceiver() {
        if (!systemReceiverRegistered) return
        systemReceiverRegistered = false
        try {
            context.unregisterReceiver(systemReceiver)
        } catch (failure: RuntimeException) {
            Log.w(logTag, "Unable to unregister the screen event receiver", failure)
        }
    }

    private fun unregisterAppReceiver() {
        if (!appReceiverRegistered) return
        appReceiverRegistered = false
        try {
            context.unregisterReceiver(appReceiver)
        } catch (failure: RuntimeException) {
            Log.w(logTag, "Unable to unregister the wallpaper command receiver", failure)
        }
    }

    private fun runCallback(operation: String, callback: () -> Unit) {
        try {
            callback()
        } catch (failure: RuntimeException) {
            Log.e(logTag, "Unable to $operation", failure)
        }
    }

    private companion object {
        const val NEVER_UNLOCKED = Long.MIN_VALUE / 2
        const val UNLOCK_SETTLE_MS = 1_500L
        const val LOCK_CONFIRM_DELAY_MS = 400L
        const val ACTION_RELOAD_WALLPAPER = "com.app.nosatmosphereeffect.RELOAD_WALLPAPER"
        const val ACTION_UPDATE_CONFIG = "com.app.nosatmosphereeffect.UPDATE_CONFIG"
    }
}

/**
 * How often the keyguard is polled. The configured interval for the first
 * seconds after the lock screen appears, when an unlock is most likely and
 * should animate at once; then no faster than twice a second, for as long as
 * the lock screen simply sits there. USER_PRESENT still ends the wait the
 * moment an unlock happens, so the slower rate only matters if it is late.
 */
internal object UnlockPolling {
    const val FAST_WINDOW_MS = 90_000L
    const val SLOW_INTERVAL_MS = 500L

    fun nextDelayMs(configuredMs: Long, elapsedMs: Long): Long {
        val configured = configuredMs.coerceAtLeast(1L)
        return if (elapsedMs < FAST_WINDOW_MS) configured else maxOf(configured, SLOW_INTERVAL_MS)
    }
}
