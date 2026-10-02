package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.content.SharedPreferences

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
 *
 * ## Several attempts at once are normal
 *
 * Replacing the wallpaper reloads every renderer in the process together —
 * the live wallpaper, the in-app previews, the clock screen — and each one
 * segments the new image. An earlier version kept a single on-disk "in
 * flight" flag and read a set flag as "the last attempt crashed", so every
 * attempt that started while another was still running was refused as a
 * crash and answered "no subject": the Adaptive clock ran its digits over the
 * person and the depth effect vanished, until something recreated the
 * renderers one at a time. Attempts are now counted in memory, and the flag
 * on disk is only read as a crash by the first attempt of a new process —
 * the one moment it can only have been left behind by a process that died.
 */
object SegmentationCrashGuard {
    private const val PREFS_NAME = "segmentation_crash_guard"

    private val cores = HashMap<String, CrashGuardCore>()

    private fun core(context: Context): CrashGuardCore = synchronized(cores) {
        val appContext = context.applicationContext
        cores.getOrPut(appContext.packageName) {
            CrashGuardCore(PreferencesStore(appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)))
        }
    }

    fun isDisabled(context: Context): Boolean = core(context).isDisabled()

    /**
     * Call immediately before invoking the third-party code that might
     * crash the process natively — as close to that call as possible, so
     * the window between this write and the risky call is as small as it
     * can be. Returns false if this attempt should be skipped instead
     * (either permanently disabled after repeated crashes, or backing off
     * from a crash detected just now).
     */
    fun beginAttempt(context: Context): Boolean {
        return when (val outcome = core(context).begin()) {
            CrashGuardCore.Outcome.ALLOWED -> true
            CrashGuardCore.Outcome.DISABLED -> {
                SubjectMaskDiagnostics.recordRejection(
                    "Subject detection kept crashing the app, so it's turned off. " +
                        "You can turn it back on from the clock screen."
                )
                false
            }
            is CrashGuardCore.Outcome.CrashDetected -> {
                SubjectMaskDiagnostics.recordRejection(
                    "Subject detection crashed the app last time, so it's " +
                        "skipping this go" + if (outcome.nowDisabled) {
                            " and turning itself off (it crashed ${outcome.streak} times in a row)."
                        } else {
                            ". It'll try again shortly."
                        }
                )
                false
            }
        }
    }

    /**
     * Call from every path where the risky call genuinely returned control
     * to app code (success or a normally-caught failure) — proof the
     * process survived, so the next attempt isn't wrongly penalized.
     */
    fun endAttempt(context: Context) = core(context).end()

    /**
     * Whether an attempt was running when the last process ended — read only,
     * for other crash recovery to know that segmentation, not it, was in the
     * middle of something. Meaningful before this process starts an attempt.
     */
    fun wasAttemptInFlight(context: Context): Boolean = core(context).wasInFlight()

    /** Exposed for a "try again" action in the UI after a disable. */
    fun reset(context: Context) = core(context).reset()

    private class PreferencesStore(private val prefs: SharedPreferences) : CrashGuardCore.Store {
        override var inFlight: Boolean
            get() = prefs.getBoolean(IN_FLIGHT_KEY, false)
            set(value) {
                // commit(), not apply(): this must be on disk before the
                // risky call, not just queued to be written eventually.
                prefs.edit().putBoolean(IN_FLIGHT_KEY, value).commit()
            }
        override var streak: Int
            get() = prefs.getInt(CRASH_STREAK_KEY, 0)
            set(value) {
                prefs.edit().putInt(CRASH_STREAK_KEY, value).commit()
            }

        override fun clear() {
            prefs.edit().clear().commit()
        }

        private companion object {
            const val IN_FLIGHT_KEY = "in_flight"
            const val CRASH_STREAK_KEY = "crash_streak"
        }
    }
}

/**
 * The guard's rules, apart from Android storage so they can be tested: one
 * instance per process, over storage that outlives it.
 */
internal class CrashGuardCore(private val store: Store) {

    interface Store {
        /** Set while at least one attempt is running; survives a crash. */
        var inFlight: Boolean
        /** Crashes in a row, cleared by any attempt that returns. */
        var streak: Int
        fun clear()
    }

    sealed interface Outcome {
        data object ALLOWED : Outcome
        data object DISABLED : Outcome
        data class CrashDetected(val streak: Int, val nowDisabled: Boolean) : Outcome
    }

    private val lock = Any()
    /** Attempts running in this process right now. */
    private var active = 0
    /** Whether this process has looked for a crash left by a previous one. */
    private var checkedForCrash = false

    fun isDisabled(): Boolean = synchronized(lock) { store.streak >= DISABLE_AFTER_STREAK }

    fun begin(): Outcome = synchronized(lock) {
        if (store.streak >= DISABLE_AFTER_STREAK) return Outcome.DISABLED
        if (!checkedForCrash) {
            checkedForCrash = true
            // Nothing in this process has started an attempt yet, so a set
            // flag can only have been left by a process that died mid-attempt.
            if (store.inFlight) {
                val streak = store.streak + 1
                store.streak = streak
                store.inFlight = false
                return Outcome.CrashDetected(streak, streak >= DISABLE_AFTER_STREAK)
            }
        }
        if (active == 0) store.inFlight = true
        active++
        Outcome.ALLOWED
    }

    fun end() = synchronized(lock) {
        active = (active - 1).coerceAtLeast(0)
        // Any attempt returning proves the process survives this code.
        if (store.streak != 0) store.streak = 0
        // The flag stays up while others are still running: a crash during
        // any of them must still be caught next time.
        if (active == 0) store.inFlight = false
    }

    fun wasInFlight(): Boolean = synchronized(lock) { active == 0 && store.inFlight }

    fun reset() = synchronized(lock) {
        store.clear()
    }

    companion object {
        const val DISABLE_AFTER_STREAK = 2
    }
}
