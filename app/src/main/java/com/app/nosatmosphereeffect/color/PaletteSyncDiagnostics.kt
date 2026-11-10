package com.app.nosatmosphereeffect.color

import android.content.Context

internal data class PaletteSyncTrace(
    val stage: String,
    val detail: String?,
    val error: String?,
    val updatedAtMillis: Long
)

internal object PaletteSyncDiagnostics {
    const val STAGE_FORCE_REQUESTED = "force_requested"
    const val STAGE_REFRESH_QUEUED = "refresh_queued"
    const val STAGE_EXTRACTING = "extracting"
    const val STAGE_PUBLISHED = "published"
    const val STAGE_DISABLED = "disabled"
    const val STAGE_MISSING_WALLPAPER = "missing_wallpaper"
    const val STAGE_EXTRACTION_FAILED = "extraction_failed"
    const val STAGE_PUBLISH_FAILED = "publish_failed"
    const val STAGE_FORCE_FAILED = "force_failed"

    private const val PREFS_NAME = "palette_sync_diagnostics"
    private const val KEY_STAGE = "stage"
    private const val KEY_DETAIL = "detail"
    private const val KEY_ERROR = "error"
    private const val KEY_UPDATED_AT = "updated_at"

    fun record(
        context: Context,
        stage: String,
        detail: String? = null,
        error: String? = null,
        clearError: Boolean = false
    ) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STAGE, stage)
            .putNullableString(KEY_DETAIL, detail)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
        when {
            error != null -> editor.putString(KEY_ERROR, error)
            clearError -> editor.remove(KEY_ERROR)
        }
        editor.apply()
    }

    fun read(context: Context): PaletteSyncTrace? {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stage = preferences.getString(KEY_STAGE, null) ?: return null
        return PaletteSyncTrace(
            stage = stage,
            detail = preferences.getString(KEY_DETAIL, null),
            error = preferences.getString(KEY_ERROR, null),
            updatedAtMillis = preferences.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    private fun android.content.SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putString(key, value)
    }
}
