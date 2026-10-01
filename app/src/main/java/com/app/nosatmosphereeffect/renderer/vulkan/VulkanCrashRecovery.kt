package com.app.nosatmosphereeffect.renderer.vulkan

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.app.nosatmosphereeffect.helper.SegmentationCrashGuard

/**
 * Moves an effect off Vulkan after the GPU driver crashes the process.
 *
 * ## Why this has to exist
 *
 * A fault inside a Vulkan driver is a native crash: it kills the process
 * outright, before any Kotlin can notice, so the ordinary fallback — a failure
 * the host sees and records — never runs. Android restarts the live wallpaper,
 * the restart picks Vulkan again, and the same fault kills it again. Two deaths
 * within ten seconds and the system gives up on the wallpaper and puts the
 * default one back, which is what users see: the wallpaper "stops working
 * after some hours" and has to be applied again.
 *
 * ## How
 *
 * The time and effect of every Vulkan start are written down. On the next
 * start, Android's own record of why the process last ended is read
 * ([ActivityManager.getHistoricalProcessExitReasons]); a native crash after a
 * Vulkan start blocks Vulkan for that effect, exactly like a caught failure
 * does, so the restarted wallpaper comes back on OpenGL ES and the loop never
 * gets its second death.
 */
internal object VulkanCrashRecovery {
    private const val TAG = "VulkanCrashRecovery"
    private const val PREFS_NAME = "graphics_backend_prefs"
    private const val KEY_SESSION_EFFECT = "vulkan_session_effect"
    private const val KEY_SESSION_STARTED = "vulkan_session_started"
    private const val KEY_EXITS_CHECKED = "vulkan_exits_checked_until"

    @Volatile private var checkedThisProcess = false

    /** Called whenever Vulkan is chosen for [effectId]. */
    fun noteVulkanStarted(context: Context, effectId: String) {
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_SESSION_EFFECT, effectId)
                putLong(KEY_SESSION_STARTED, System.currentTimeMillis())
            }
        }.onFailure { Log.w(TAG, "Unable to note the Vulkan session", it) }
    }

    /**
     * Once per process, before the first backend is chosen: if the process
     * that came before died of a native crash while Vulkan was running,
     * blocks Vulkan for the effect it was running.
     */
    fun checkPreviousExit(context: Context) {
        if (checkedThisProcess) return
        synchronized(this) {
            if (checkedThisProcess) return
            checkedThisProcess = true
            runCatching { check(context) }
                .onFailure { Log.w(TAG, "Unable to read the previous exit", it) }
        }
    }

    private fun check(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manager = appContext.getSystemService(ActivityManager::class.java) ?: return
        val processName = appContext.applicationInfo.processName ?: appContext.packageName
        val exits = manager.getHistoricalProcessExitReasons(appContext.packageName, 0, MAX_EXITS)
            .filter { it.processName == processName }
            .map { VulkanCrashPolicy.Exit(nativeCrash = it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = it.timestamp) }
        val decision = VulkanCrashPolicy.decide(
            exits = exits,
            checkedUntil = prefs.getLong(KEY_EXITS_CHECKED, 0L),
            sessionStarted = prefs.getLong(KEY_SESSION_STARTED, 0L),
            sessionEffect = prefs.getString(KEY_SESSION_EFFECT, null),
            segmentationWasRunning = SegmentationCrashGuard.wasAttemptInFlight(appContext)
        )
        prefs.edit { putLong(KEY_EXITS_CHECKED, decision.checkedUntil) }
        val effect = decision.blockEffect ?: return
        Log.w(TAG, "The process crashed natively while Vulkan ran $effect; using OpenGL ES")
        VulkanSupport.recordFailure(
            appContext,
            effect,
            "Your phone's graphics driver crashed the wallpaper on Vulkan"
        )
    }

    private const val MAX_EXITS = 5
}

/** [VulkanCrashRecovery]'s decision, apart from Android so it can be tested. */
internal object VulkanCrashPolicy {

    data class Exit(val nativeCrash: Boolean, val timestamp: Long)

    data class Decision(val blockEffect: String?, val checkedUntil: Long)

    /**
     * Only the most recent exit not seen before counts: an older crash was
     * already dealt with when it was the most recent. It is blamed on Vulkan
     * only when it was a native crash, came after Vulkan started, and
     * segmentation — the other native code in the process, with its own
     * guard — was not running at the time.
     */
    fun decide(
        exits: List<Exit>,
        checkedUntil: Long,
        sessionStarted: Long,
        sessionEffect: String?,
        segmentationWasRunning: Boolean
    ): Decision {
        val latest = exits.filter { it.timestamp > checkedUntil }.maxByOrNull { it.timestamp }
            ?: return Decision(null, checkedUntil)
        val blame = latest.nativeCrash &&
            sessionEffect != null &&
            sessionStarted in 1..latest.timestamp &&
            !segmentationWasRunning
        return Decision(if (blame) sessionEffect else null, latest.timestamp)
    }
}
