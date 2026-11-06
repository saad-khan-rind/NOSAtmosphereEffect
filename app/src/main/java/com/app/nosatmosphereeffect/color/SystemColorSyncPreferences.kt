package com.app.nosatmosphereeffect.color

import android.content.Context
import androidx.core.content.edit
import com.app.nosatmosphereeffect.playlist.PlaylistModeManager

/**
 * Stores system color sync separately from effect tuning, which is reset when a
 * new wallpaper configuration is created.
 */
object SystemColorSyncPreferences {
    private const val PREFS_NAME = "system_color_sync_prefs"
    private const val LEGACY_PREFS_NAME = "app_prefs"
    private const val ENABLED_KEY = "notify_system_colors"

    fun isEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(ENABLED_KEY)) {
            return prefs.getBoolean(ENABLED_KEY, true)
        }

        val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = if (legacyPrefs.contains(ENABLED_KEY)) {
            legacyPrefs.getBoolean(ENABLED_KEY, true)
        } else {
            !hasActivePlaylist(appContext)
        }
        prefs.edit { putBoolean(ENABLED_KEY, enabled) }
        return enabled
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(ENABLED_KEY, enabled) }
    }

    private fun hasActivePlaylist(context: Context): Boolean {
        return PlaylistModeManager.isPlaylistMode(context)
    }
}
