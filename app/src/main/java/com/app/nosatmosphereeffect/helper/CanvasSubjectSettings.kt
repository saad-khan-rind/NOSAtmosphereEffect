package com.app.nosatmosphereeffect.helper

object CanvasSubjectSettings {
    const val ENABLED_KEY = "canvas_subject_segmentation"
    const val MODEL_READY_KEY = "canvas_subject_model_downloaded"
}

enum class SubjectModelDelivery {
    GOOGLE_PLAY_SERVICES,
    BUNDLED_FOSS
}

enum class SubjectModelPhase {
    CHECKING,
    NOT_DOWNLOADED,
    DOWNLOADING,
    INSTALLING,
    PAUSED,
    READY,
    FAILED,
    /** Installed, but the version on this phone is broken; see SegmentationCrashGuard. */
    BROKEN
}

data class SubjectModelState(
    val phase: SubjectModelPhase,
    val progressPercent: Int? = null
)
