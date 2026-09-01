package com.app.nosatmosphereeffect.helper

import android.content.Context

/**
 * Firewalls against native crashes inside third-party segmentation code
 * (Google Play services' bundled ML Kit module, or the bundled TFLite
 * model) that a Kotlin/Java try-catch cannot intercept — a raw SIGBUS or
 * SIGSEGV terminates the process immediately, before any exception handler
 * runs. Seen in production: a SIGBUS entirely inside
 * dl-MlkitSubjectSegmentation's native code, zero app frames in the trace.
 *
 * The only thing app code can do about a crash like that is notice it
 * happened (by writing a flag to disk *before* the risky call, since that
 * write must survive the crash) and back off next time, rather than
 * retrying into the same crash every single attempt.
 */
object SegmentationCrashGuard {
    private const val PREFS_NAME = "segmentation_crash_guard"
    private const val IN_FLIGHT_KEY = "in_flight"
    private const val CRASH_STREAK_KEY = "crash_streak"
    private const val DISABLE_AFTER_STREAK = 2

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDisabled(context: Context): Boolean {
        return prefs(context).getInt(CRASH_STREAK_KEY, 0) >= DISABLE_AFTER_STREAK
    }

    /**
     * Call immediately before invoking the third-party code that might
     * crash the process natively — as close to that call as possible, so
     * the window between this write and the risky call is as small as it
     * can be. Returns false if this attempt should be skipped instead
     * (either permanently disabled after repeated crashes, or backing off
     * from a crash detected just now).
     */
    fun beginAttempt(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.getInt(CRASH_STREAK_KEY, 0) >= DISABLE_AFTER_STREAK) {
            SubjectMaskDiagnostics.recordRejection(
                "Subject detection was disabled after crashing the app " +
                    "repeatedly. Reset it in Advanced Settings to try again."
            )
            return false
        }
        if (prefs.getBoolean(IN_FLIGHT_KEY, false)) {
            // This flag was set before a previous attempt and never
            // cleared — the only way that happens is the process dying
            // before the call returned.
            val streak = prefs.getInt(CRASH_STREAK_KEY, 0) + 1
            prefs.edit()
                .putInt(CRASH_STREAK_KEY, streak)
                .putBoolean(IN_FLIGHT_KEY, false)
                .commit()
            SubjectMaskDiagnostics.recordRejection(
                "Subject detection crashed the app last time — skipping " +
                    "this attempt" + if (streak >= DISABLE_AFTER_STREAK) {
                        " and disabling it (crashed $streak times in a row)."
                    } else {
                        ", will try again next time."
                    }
            )
            return false
        }
        // commit(), not apply(): this must be on disk before the risky
        // call, not just queued to be written eventually.
        prefs.edit().putBoolean(IN_FLIGHT_KEY, true).commit()
        return true
    }

    /**
     * Call from every path where the risky call genuinely returned control
     * to app code (success or a normally-caught failure) — proof the
     * process survived, so the next attempt isn't wrongly penalized.
     */
    fun endAttempt(context: Context) {
        prefs(context).edit()
            .putBoolean(IN_FLIGHT_KEY, false)
            .putInt(CRASH_STREAK_KEY, 0)
            .commit()
    }

    /** Exposed for a "try again" action in the UI after a disable. */
    fun reset(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
