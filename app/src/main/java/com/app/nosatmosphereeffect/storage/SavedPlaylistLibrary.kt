package com.app.nosatmosphereeffect.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import org.json.JSONException
import org.json.JSONObject

internal data class SavedPlaylistSummary(
    val id: String,
    val name: String,
    val imageCount: Int,
    val updatedAt: Long,
    /** The first image, copied or in its folder. */
    val cover: Uri?,
    val watch: FolderWatchState,
    val isActive: Boolean
)

/**
 * Keeps every applied standard playlist so switching to a single image or to
 * theme playlists no longer throws the collection away.
 *
 * Layout: `saved_playlists/<id>/{images,originals}` mirror the active
 * `playlist` / `playlist_originals` directories, plus `info.json`.
 * Mutations that read or write the active playlist must run inside
 * [WallpaperStorageCoordinator.runExclusive].
 */
internal object SavedPlaylistLibrary {
    private const val TAG = "SavedPlaylistLibrary"
    private const val ROOT_DIR = "saved_playlists"
    private const val IMAGES_DIR = "images"
    private const val ORIGINALS_DIR = "originals"
    private const val INFO_FILE = "info.json"
    private const val PREFS_NAME = "saved_playlists"
    private const val KEY_ACTIVE_ID = "active_id"
    private const val KEY_NAME = "name"
    private const val KEY_CREATED = "created"
    private const val KEY_UPDATED = "updated"
    private const val KEY_WATCH = "watch"
    private val validId = Regex("^[A-Za-z0-9-]{1,64}$")

    fun root(context: Context) = File(context.filesDir, ROOT_DIR)

    fun imagesDir(context: Context, id: String) = File(entryDir(context, id), IMAGES_DIR)

    fun originalsDir(context: Context, id: String) = File(entryDir(context, id), ORIGINALS_DIR)

    fun exists(context: Context, id: String?): Boolean {
        return id != null && validId.matches(id) &&
            PlaylistModeManager.imageFiles(imagesDir(context, id)).isNotEmpty()
    }

    fun list(context: Context): List<SavedPlaylistSummary> {
        val activeId = activeId(context)
        return root(context).listFiles()
            .orEmpty()
            .filter { it.isDirectory && validId.matches(it.name) }
            .mapNotNull { directory ->
                val images = PlaylistModeManager.imageFiles(File(directory, IMAGES_DIR))
                if (images.isEmpty()) return@mapNotNull null
                val info = readInfo(directory)
                SavedPlaylistSummary(
                    id = directory.name,
                    name = info?.optString(KEY_NAME)?.takeIf(String::isNotBlank)
                        ?: context.getString(R.string.saved_default_name),
                    imageCount = images.size,
                    updatedAt = info?.optLong(KEY_UPDATED, directory.lastModified())
                        ?: directory.lastModified(),
                    cover = images.first().let { first ->
                        if (PlaylistImageRef.isRef(first)) {
                            PlaylistImageRef.read(first)?.uri
                        } else {
                            Uri.fromFile(first)
                        }
                    },
                    watch = FolderWatchState.fromJson(info?.optJSONObject(KEY_WATCH)),
                    isActive = directory.name == activeId
                )
            }
            .sortedByDescending(SavedPlaylistSummary::updatedAt)
    }

    fun name(context: Context, id: String): String? {
        return readInfo(entryDir(context, id))?.optString(KEY_NAME)?.takeIf(String::isNotBlank)
    }

    fun watchState(context: Context, id: String): FolderWatchState {
        return FolderWatchState.fromJson(readInfo(entryDir(context, id))?.optJSONObject(KEY_WATCH))
    }

    /** The library entry the active standard playlist was applied from, if any. */
    fun activeId(context: Context): String? {
        val id = prefs(context).getString(KEY_ACTIVE_ID, null)
        return id?.takeIf { exists(context, it) }
    }

    fun setActiveId(context: Context, id: String?) {
        val editor = prefs(context).edit()
        if (id == null) editor.remove(KEY_ACTIVE_ID) else editor.putString(KEY_ACTIVE_ID, id)
        editor.commit()
    }

    /**
     * Copies the active standard playlist into the library, replacing entry
     * [id] or creating a new one, and returns the entry id.
     */
    @Throws(IOException::class)
    fun saveActive(
        context: Context,
        id: String?,
        name: String?,
        watch: FolderWatchState
    ): String {
        val activeImages = PlaylistModeManager.standardPlaylistDir(context)
        val activeOriginals = File(context.filesDir, PlaylistModeManager.STANDARD_ORIGINALS_DIR)
        if (PlaylistModeManager.imageFiles(activeImages).isEmpty()) {
            throw IOException("There is no active playlist to save")
        }

        val entryId = id?.takeIf(validId::matches) ?: UUID.randomUUID().toString()
        val entry = entryDir(context, entryId)
        val previousInfo = readInfo(entry)
        val now = System.currentTimeMillis()
        val staged = File(root(context), ".$entryId-${UUID.randomUUID()}.staged")
        try {
            FileTransactions.prepareEmptyDirectory(staged)
            copyDirectory(activeImages, File(staged, IMAGES_DIR))
            if (activeOriginals.isDirectory) {
                copyDirectory(activeOriginals, File(staged, ORIGINALS_DIR))
            }
            val resolvedName = name?.trim()?.takeIf(String::isNotEmpty)
                ?: previousInfo?.optString(KEY_NAME)?.takeIf(String::isNotBlank)
                ?: defaultName(context, now)
            val info = JSONObject()
                .put(KEY_NAME, resolvedName)
                .put(KEY_CREATED, previousInfo?.optLong(KEY_CREATED, now) ?: now)
                .put(KEY_UPDATED, now)
                .put(KEY_WATCH, watch.toJson())
            FileTransactions.writeTextAtomically(File(staged, INFO_FILE), info.toString())
            FileTransactions.replaceDirectories(listOf(staged to entry))
        } finally {
            try {
                FileTransactions.deleteRecursively(staged)
            } catch (error: IOException) {
                Log.w(TAG, "Could not remove ${staged.absolutePath}", error)
            }
        }
        return entryId
    }

    /**
     * Saves the active standard playlist into its linked entry (or a new one)
     * before something replaces it. Never throws: losing the backup must not
     * block applying a new wallpaper.
     */
    fun preserveActive(context: Context) {
        // Not gated on the playlist mode: callers switch the mode flag before
        // clearing, and the standard directory only ever holds the last
        // standard playlist (single and theme applies both clear it).
        if (PlaylistModeManager.imageFiles(PlaylistModeManager.standardPlaylistDir(context)).isEmpty()) {
            return
        }
        try {
            val id = saveActive(
                context,
                activeId(context),
                name = null,
                watch = ActiveFolderWatch.read(context)
            )
            setActiveId(context, id)
        } catch (error: Exception) {
            Log.w(TAG, "Could not preserve the active playlist", error)
        }
    }

    @Throws(IOException::class)
    fun rename(context: Context, id: String, name: String) {
        val entry = entryDir(context, id)
        val info = readInfo(entry) ?: JSONObject()
        info.put(KEY_NAME, name.trim().ifEmpty { context.getString(R.string.saved_default_name) })
        FileTransactions.writeTextAtomically(File(entry, INFO_FILE), info.toString())
    }

    @Throws(IOException::class)
    fun delete(context: Context, id: String) {
        if (!validId.matches(id)) return
        if (prefs(context).getString(KEY_ACTIVE_ID, null) == id) setActiveId(context, null)
        FileTransactions.deleteRecursively(entryDir(context, id))
    }

    private fun entryDir(context: Context, id: String): File {
        require(validId.matches(id)) { "Invalid saved playlist id" }
        return File(root(context), id)
    }

    private fun readInfo(entry: File): JSONObject? {
        val file = File(entry, INFO_FILE)
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText())
        } catch (error: IOException) {
            Log.w(TAG, "Could not read ${file.absolutePath}", error)
            null
        } catch (error: JSONException) {
            Log.w(TAG, "Saved playlist info is invalid: ${file.absolutePath}", error)
            null
        }
    }

    private fun copyDirectory(source: File, destination: File) {
        FileTransactions.prepareEmptyDirectory(destination)
        source.listFiles().orEmpty().filter(File::isFile).forEach { file ->
            file.copyTo(File(destination, file.name), overwrite = true)
        }
    }

    private fun defaultName(context: Context, timestamp: Long): String {
        val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timestamp))
        return context.getString(R.string.saved_default_name_dated, formatted)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
