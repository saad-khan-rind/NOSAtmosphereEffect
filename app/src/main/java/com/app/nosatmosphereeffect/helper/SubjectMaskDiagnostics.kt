package com.app.nosatmosphereeffect.helper

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.app.nosatmosphereeffect.R

/**
 * Records the last subject-mask failure/success in memory so it can be
 * shown directly in the UI (ClockAdjustActivity) without needing adb or
 * logcat access. Best-effort diagnostics only — not persisted, resets on
 * process death.
 */
object SubjectMaskDiagnostics {
    /** A reason as a string resource, so it shows in the user's language. */
    data class Failure(
        /** A string resource, or a plurals resource when [quantity] is set. */
        val text: Int,
        val args: List<Any> = emptyList(),
        val quantity: Int? = null
    ) {
        fun describe(resources: Resources): String = if (quantity != null) {
            resources.getQuantityString(text, quantity, *args.toTypedArray())
        } else {
            resources.getString(text, *args.toTypedArray())
        }
    }

    @Volatile var lastFailure: Failure? = null
        private set
    @Volatile var lastSuccessAtMillis: Long = 0L
        private set

    fun recordFailure(context: String, error: Throwable) {
        val type = error::class.simpleName.orEmpty()
        val detail = error.message?.take(120)
        lastFailure = if (detail == null) {
            Failure(R.string.subject_failure, listOf(context, type))
        } else {
            Failure(R.string.subject_failure_detail, listOf(context, type, detail))
        }
    }

    /**
     * For the deliberate "no usable subject" outcomes (mask rejected by a
     * confidence/bounds heuristic, not an error) — these previously failed
     * completely silently, which looks identical to a real bug from the
     * outside.
     */
    fun recordRejection(@StringRes reason: Int, vararg args: Any) {
        lastFailure = Failure(reason, args.toList())
    }

    /** A rejection whose wording depends on [quantity], also its only argument. */
    fun recordCountedRejection(@PluralsRes reason: Int, quantity: Int) {
        lastFailure = Failure(reason, listOf(quantity), quantity)
    }

    /** Forgets the last failure: detection is being tried again. */
    fun clear() {
        lastFailure = null
    }

    fun recordSuccess() {
        lastFailure = null
        lastSuccessAtMillis = System.currentTimeMillis()
    }
}
