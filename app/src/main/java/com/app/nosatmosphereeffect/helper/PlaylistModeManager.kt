package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.content.res.Configuration
import com.app.nosatmosphereeffect.storage.ActiveFolderWatch
import com.app.nosatmosphereeffect.storage.FileTransactions
import com.app.nosatmosphereeffect.storage.SavedPlaylistLibrary
import java.io.File

/** Owns the on-disk collections and mode flag shared by every wallpaper effect. */
object PlaylistModeManager {
    const val MODE_SINGLE = "single"
    const val MODE_STANDARD = "standard"
    const val MODE_THEME = "theme"

    const val STANDARD_PLAYLIST_DIR = "playlist"
    const val STANDARD_ORIGINALS_DIR = "playlist_originals"
    const val LIGHT_PLAYLIST_DIR = "playlist_light"
    const val LIGHT_ORIGINALS_DIR = "playlist_light_originals"
    const val DARK_PLAYLIST_DIR = "playlist_dark"
    const val DARK_ORIGINALS_DIR = "playlist_dark_originals"

    const val KEY_MODE = "playlist_mode"
    const val KEY_ACTIVE_THEME = "active_theme_state"

    private const val PREFS_NAME = "wallpaper_prefs"

    fun getMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MODE, null)
        if (stored in setOf(MODE_SINGLE, MODE_STANDARD, MODE_THEME)) return stored!!

        return when {
            hasImages(File(context.filesDir, LIGHT_PLAYLIST_DIR)) &&
                hasImages(File(context.filesDir, DARK_PLAYLIST_DIR)) -> MODE_THEME
            hasImages(File(context.filesDir, STANDARD_PLAYLIST_DIR)) -> MODE_STANDARD
            else -> MODE_SINGLE
        }
    }

    fun isThemeMode(context: Context): Boolean = getMode(context) == MODE_THEME

    fun isPlaylistMode(context: Context): Boolean {
        return when (getMode(context)) {
            MODE_THEME -> hasImages(lightPlaylistDir(context)) && hasImages(darkPlaylistDir(context))
            MODE_STANDARD -> hasImages(standardPlaylistDir(context))
            else -> false
        }
    }

    fun currentNightMode(context: Context): Boolean {
        val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    fun activePlaylistDir(context: Context, isNightMode: Boolean = currentNightMode(context)): File {
        return when (getMode(context)) {
            MODE_THEME -> if (isNightMode) darkPlaylistDir(context) else lightPlaylistDir(context)
            else -> standardPlaylistDir(context)
        }
    }

    fun activeOriginalsDirName(
        context: Context,
        isNightMode: Boolean = currentNightMode(context)
    ): String {
        return when (getMode(context)) {
            MODE_THEME -> if (isNightMode) DARK_ORIGINALS_DIR else LIGHT_ORIGINALS_DIR
            else -> STANDARD_ORIGINALS_DIR
        }
    }

    fun lastImagePreferenceKey(mode: String, isNightMode: Boolean): String {
        return when (mode) {
            MODE_THEME -> if (isNightMode) "last_playlist_image_dark" else "last_playlist_image_light"
            else -> "last_playlist_image"
        }
    }

    fun standardPlaylistDir(context: Context) = File(context.filesDir, STANDARD_PLAYLIST_DIR)
    fun lightPlaylistDir(context: Context) = File(context.filesDir, LIGHT_PLAYLIST_DIR)
    fun darkPlaylistDir(context: Context) = File(context.filesDir, DARK_PLAYLIST_DIR)

    /**
     * Removes the active standard playlist. It is first copied into the saved
     * playlist library, so switching to a single image or theme playlists can
     * always be undone from "Saved playlists".
     */
    fun clearStandardCollections(context: Context) {
        SavedPlaylistLibrary.preserveActive(context)
        FileTransactions.deleteRecursively(standardPlaylistDir(context))
        FileTransactions.deleteRecursively(File(context.filesDir, STANDARD_ORIGINALS_DIR))
        SavedPlaylistLibrary.setActiveId(context, null)
        ActiveFolderWatch.clear(context)
    }

    fun clearThemeCollections(context: Context) {
        FileTransactions.deleteRecursively(lightPlaylistDir(context))
        FileTransactions.deleteRecursively(darkPlaylistDir(context))
        FileTransactions.deleteRecursively(File(context.filesDir, LIGHT_ORIGINALS_DIR))
        FileTransactions.deleteRecursively(File(context.filesDir, DARK_ORIGINALS_DIR))
    }

    /**
     * The playlist's entries in order, one file per index: `wallpaper_N.jpg`
     * or a `wallpaper_N.ref` pointer. If both exist (a conversion to a
     * pointer was interrupted) the complete .jpg wins.
     */
    fun imageFiles(directory: File): List<File> {
        return directory.listFiles()
            ?.mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                val index = PlaylistFilePolicy.index(file.name) ?: return@mapNotNull null
                index to file
            }
            ?.sortedWith(compareBy({ (index, _) -> index }, { (_, file) -> file.extension != "jpg" }))
            ?.distinctBy { (index, _) -> index }
            ?.map { (_, file) -> file }
            .orEmpty()
    }

    /** The entry at [index] in [directory], copied or pointer, or null. */
    fun entryFile(directory: File, index: Int): File? =
        imageFiles(directory).firstOrNull { PlaylistFilePolicy.index(it.name) == index }

    private fun hasImages(directory: File): Boolean = imageFiles(directory).isNotEmpty()
}
