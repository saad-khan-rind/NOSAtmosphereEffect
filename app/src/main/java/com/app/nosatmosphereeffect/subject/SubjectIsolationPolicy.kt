package com.app.nosatmosphereeffect.subject

object SubjectIsolationPolicy {
    const val HALFTONE_BACKGROUND_ONLY_KEY = "halftone_background_only"

    fun effectCoverage(
        backgroundOnly: Boolean,
        hasSubjectMask: Boolean,
        foregroundConfidence: Float
    ): Float {
        if (!backgroundOnly) return 1f
        if (!hasSubjectMask) return 0f

        val confidence = if (foregroundConfidence.isFinite()) {
            foregroundConfidence.coerceIn(0f, 1f)
        } else {
            1f
        }
        val t = ((confidence - 0.3f) / 0.42f).coerceIn(0f, 1f)
        val foreground = t * t * (3f - 2f * t)
        return 1f - foreground
    }
}
