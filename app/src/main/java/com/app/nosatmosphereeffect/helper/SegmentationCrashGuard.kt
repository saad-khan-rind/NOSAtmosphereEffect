package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.app.nosatmosphereeffect.R

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
 *
 * ## One strike per model version
 *
 * When the model's version is known (Google Play services downloads the
 * subject model and updates it on its own schedule, see
 * [SubjectMaskExtractor.modelVersion]), a single crash pauses detection for
 * that version. Version 263635 of Google's subject module crashed with SIGBUS
 * in its native code in production. Trying it again automatically only
 * crashes again, and a live wallpaper whose process dies twice within ten
 * seconds is replaced by Android's default wallpaper. Once Play services
 * moves to another version of the model, detection tries again by itself, and
 * Try again ([reset]) lets the user try it sooner. Without a known version
 * (the bundled model), the older rule stays: skip one attempt after a crash,
 * switch off after two in a row.
 */
object SegmentationCrashGuard {
    private const val PREFS_NAME = "segmentation_crash_guard"

    /** Shown wherever detection is paused for the model version on this phone. */
    @StringRes
    val MODEL_PAUSED: Int = R.string.subject_model_paused

    private val cores = HashMap<String, CrashGuardCore>()

    private fun core(context: Context): CrashGuardCore = synchronized(cores) {
        val appContext = context.applicationContext
        cores.getOrPut(appContext.packageName) {
            CrashGuardCore(PreferencesStore(appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)))
        }
    }

    /**
     * True when detection is off: after repeated crashes, or paused for the
     * model version this phone has now.
     */
    fun isDisabled(context: Context): Boolean =
        core(context).isDisabled(SubjectMaskExtractor.modelVersion(context))

    /** True when detection is paused for the model version this phone has now. */
    fun isPausedForModel(context: Context): Boolean =
        core(context).isPaused(SubjectMaskExtractor.modelVersion(context))

    /**
     * Call immediately before invoking the third-party code that might
     * crash the process natively — as close to that call as possible, so
     * the window between this write and the risky call is as small as it
     * can be. [model] is the model's version when known. Returns false if
     * this attempt should be skipped instead: paused for this model version,
     * switched off after repeated crashes, or backing off from a crash
     * detected just now.
     */
    fun beginAttempt(context: Context, model: String? = null): Boolean {
        return when (val outcome = core(context).begin(model)) {
            CrashGuardCore.Outcome.ALLOWED -> true
            CrashGuardCore.Outcome.PAUSED -> {
                SubjectMaskDiagnostics.recordRejection(MODEL_PAUSED)
                false
            }
            CrashGuardCore.Outcome.DISABLED -> {
                SubjectMaskDiagnostics.recordRejection(R.string.subject_turned_off)
                false
            }
            is CrashGuardCore.Outcome.CrashDetected -> {
                when {
                    outcome.pausedModel -> SubjectMaskDiagnostics.recordRejection(MODEL_PAUSED)
                    outcome.nowDisabled -> SubjectMaskDiagnostics.recordCountedRejection(
                        R.plurals.subject_crashed_streak,
                        outcome.streak
                    )
                    else -> SubjectMaskDiagnostics.recordRejection(R.string.subject_skipping)
                }
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

    /** Exposed for a "try again" action in the UI after a disable or a pause. */
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
        override var pausedModel: String?
            get() = prefs.getString(PAUSED_MODEL_KEY, null)
            set(value) {
                prefs.edit().putString(PAUSED_MODEL_KEY, value).commit()
            }

        override fun clear() {
            prefs.edit().clear().commit()
        }

        private companion object {
            const val IN_FLIGHT_KEY = "in_flight"
            const val CRASH_STREAK_KEY = "crash_streak"
            const val PAUSED_MODEL_KEY = "paused_model"
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
        /** The model version detection is paused for, if any. */
        var pausedModel: String?
        fun clear()
    }

    sealed interface Outcome {
        data object ALLOWED : Outcome
        data object DISABLED : Outcome
        /** Paused for this model version, after a crash or a broken answer. */
        data object PAUSED : Outcome
        data class CrashDetected(
            val streak: Int,
            val nowDisabled: Boolean,
            /** True when the crash paused this model version. */
            val pausedModel: Boolean = false
        ) : Outcome
    }

    private val lock = Any()
    /** Attempts running in this process right now. */
    private var active = 0
    /** Whether this process has looked for a crash left by a previous one. */
    private var checkedForCrash = false

    fun isDisabled(model: String? = null): Boolean = synchronized(lock) {
        store.streak >= DISABLE_AFTER_STREAK || isPausedLocked(model)
    }

    fun isPaused(model: String?): Boolean = synchronized(lock) { isPausedLocked(model) }

    private fun isPausedLocked(model: String?): Boolean =
        model != null && store.pausedModel == model

    fun pause(model: String) = synchronized(lock) {
        store.pausedModel = model
    }

    fun begin(model: String? = null): Outcome = synchronized(lock) {
        // A different model version than the one paused for: try again.
        val paused = store.pausedModel
        if (model != null && paused != null && paused != model) store.pausedModel = null
        if (isPausedLocked(model)) return Outcome.PAUSED
        if (store.streak >= DISABLE_AFTER_STREAK) return Outcome.DISABLED
        if (!checkedForCrash) {
            checkedForCrash = true
            // Nothing in this process has started an attempt yet, so a set
            // flag can only have been left by a process that died mid-attempt.
            if (store.inFlight) {
                store.inFlight = false
                if (model != null) {
                    // One strike for a known model version.
                    store.pausedModel = model
                    return Outcome.CrashDetected(store.streak, nowDisabled = false, pausedModel = true)
                }
                val streak = store.streak + 1
                store.streak = streak
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
