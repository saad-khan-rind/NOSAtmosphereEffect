package com.app.nosatmosphereeffect.helper

/**
 * Records the last subject-mask failure/success in memory so it can be
 * shown directly in the UI (ClockAdjustActivity) without needing adb or
 * logcat access. Best-effort diagnostics only — not persisted, resets on
 * process death.
 */
object SubjectMaskDiagnostics {
    @Volatile var lastFailure: String? = null
        private set
    @Volatile var lastSuccessAtMillis: Long = 0L
        private set

    fun recordFailure(context: String, error: Throwable) {
        val detail = error.message?.take(120) ?: "no message"
        lastFailure = "$context — ${error::class.simpleName}: $detail"
    }

    /**
     * For the deliberate "no usable subject" outcomes (mask rejected by a
     * confidence/bounds heuristic, not an error) — these previously failed
     * completely silently, which looks identical to a real bug from the
     * outside.
     */
    fun recordRejection(reason: String) {
        lastFailure = reason
    }

    fun recordSuccess() {
        lastFailure = null
        lastSuccessAtMillis = System.currentTimeMillis()
    }
}
