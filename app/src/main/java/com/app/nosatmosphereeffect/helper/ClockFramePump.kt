package com.app.nosatmosphereeffect.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Asks the render surface for a frame at the cadence the clock needs.
 *
 * The renderers are RENDERMODE_WHEN_DIRTY: they draw when something asks and
 * are otherwise idle. That is right for an effect driven by lock/unlock
 * transitions, and wrong for a clock, which has to advance on its own. With
 * transitions off and the device sitting on the lock screen, nothing ever
 * asked, so the displayed time simply stopped.
 *
 * ## Why this is per-engine and not per-service
 *
 * A live wallpaper service hosts several engines over its lifetime — the
 * settings preview and the real wallpaper can both be alive at once, and the
 * preview is destroyed when the picker closes. A single service-wide receiver
 * gets torn down by whichever engine dies first, silently leaving the
 * survivor without one. One pump per engine, owned by that engine's
 * controller, has no such coupling.
 *
 * ## Why a scheduled post rather than ACTION_TIME_TICK alone
 *
 * TIME_TICK only fires once a minute, so it cannot drive a seconds display,
 * and it can be delayed. Posting to the next real boundary gives an exact
 * cadence at either granularity. The receiver is still registered, but only
 * for the two events a timer cannot infer: a manual time change and a
 * timezone change, both of which also change the 12/24-hour reading.
 */
class ClockFramePump(
    context: Context,
    private val onTick: () -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var enabled = false
    private var showSeconds = false
    private var visible = false
    private var closed = false
    private var scheduled = false
    private var receiver: BroadcastReceiver? = null

    private val tickRunnable = Runnable {
        scheduled = false
        if (!closed && enabled && visible) {
            onTick()
            schedule()
        }
    }

    /** [enabled] should be false whenever the clock is off, to idle entirely. */
    fun configure(enabled: Boolean, showSeconds: Boolean) {
        if (closed) return
        val changed = this.enabled != enabled || this.showSeconds != showSeconds
        this.enabled = enabled
        this.showSeconds = showSeconds
        if (changed) restart()
    }

    fun setVisible(visible: Boolean) {
        if (closed || this.visible == visible) return
        this.visible = visible
        restart()
    }

    fun close() {
        if (closed) return
        closed = true
        cancel()
        unregisterReceiver()
    }

    private fun restart() {
        cancel()
        if (!enabled || !visible) {
            unregisterReceiver()
            return
        }
        registerReceiver()
        // Fire once immediately: whatever changed (becoming visible, seconds
        // being switched on) means the current frame is already stale.
        onTick()
        schedule()
    }

    private fun schedule() {
        if (closed || scheduled || !enabled || !visible) return
        scheduled = true
        handler.postDelayed(tickRunnable, delayToNextBoundaryMs())
    }

    private fun cancel() {
        handler.removeCallbacks(tickRunnable)
        scheduled = false
    }

    /**
     * Milliseconds until the next second or minute boundary in wall time,
     * measured against uptime so the post is not itself affected by a clock
     * adjustment. Clamped to a small floor so a boundary landing on the
     * current instant cannot spin.
     */
    private fun delayToNextBoundaryMs(): Long {
        val period = if (showSeconds) 1_000L else 60_000L
        val now = System.currentTimeMillis()
        val remainder = now % period
        val delay = period - remainder
        return delay.coerceIn(MIN_DELAY_MS, period)
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!closed && enabled && visible) {
                    onTick()
                    // The boundary moved; re-align rather than waiting out
                    // the post that was scheduled against the old time.
                    cancel()
                    schedule()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(
                appContext,
                created,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiver = created
        } catch (failure: RuntimeException) {
            // Costs the clock its response to a manual time change, not the
            // wallpaper. The scheduled cadence keeps working regardless.
            Log.w(TAG, "Could not register the clock time receiver", failure)
            receiver = null
        }
    }

    private fun unregisterReceiver() {
        val current = receiver ?: return
        receiver = null
        try {
            appContext.unregisterReceiver(current)
        } catch (_: IllegalArgumentException) {
            // Already gone.
        }
    }

    private companion object {
        const val TAG = "ClockFramePump"
        const val MIN_DELAY_MS = 16L
    }
}
