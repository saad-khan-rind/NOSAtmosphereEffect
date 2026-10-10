package com.app.nosatmosphereeffect.storage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A folder picked in the system folder picker (its tree URI) whose new images join a playlist. */
internal data class WatchedFolder(
    val id: String,
    val name: String
)

/**
 * The folders a standard playlist follows, plus every folder image id
 * already seen in them. Images whose ids are known are never re-added, so
 * removing a folder image from the playlist sticks.
 */
internal data class FolderWatchState(
    val folders: List<WatchedFolder> = emptyList(),
    val knownMediaIds: Set<Long> = emptySet()
) {
    val isEmpty: Boolean get() = folders.isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_FOLDERS, JSONArray().apply {
            folders.forEach { folder ->
                put(JSONObject().put(KEY_ID, folder.id).put(KEY_NAME, folder.name))
            }
        })
        put(KEY_KNOWN, JSONArray().apply { knownMediaIds.forEach(::put) })
    }

    companion object {
        private const val KEY_FOLDERS = "folders"
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_KNOWN = "known"

        fun fromJson(json: JSONObject?): FolderWatchState {
            if (json == null) return FolderWatchState()
            val folders = json.optJSONArray(KEY_FOLDERS)?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val folder = array.optJSONObject(index) ?: return@mapNotNull null
                    // Folders followed before the system folder picker were photo
                    // library buckets, which can't be read any more: they are dropped
                    // and the playlist keeps its images until a folder is picked again.
                    val id = folder.optString(KEY_ID).takeIf { it.startsWith("content://") }
                        ?: return@mapNotNull null
                    WatchedFolder(id, folder.optString(KEY_NAME, id))
                }
            }.orEmpty()
            val known = json.optJSONArray(KEY_KNOWN)?.let { array ->
                (0 until array.length()).map(array::optLong).toSet()
            }.orEmpty()
            return FolderWatchState(folders.distinctBy(WatchedFolder::id), known)
        }
    }
}

/** Folder watch state of the currently active standard playlist. */
internal object ActiveFolderWatch {
    private const val TAG = "ActiveFolderWatch"
    private const val PREFS_NAME = "folder_playlist"
    private const val KEY_STATE = "active_state"

    fun read(context: Context): FolderWatchState {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null) ?: return FolderWatchState()
        return try {
            FolderWatchState.fromJson(JSONObject(raw))
        } catch (error: JSONException) {
            Log.w(TAG, "Stored folder watch state is invalid", error)
            FolderWatchState()
        }
    }

    fun write(context: Context, state: FolderWatchState) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (state.isEmpty) {
            editor.remove(KEY_STATE)
        } else {
            editor.putString(KEY_STATE, state.toJson().toString())
        }
        editor.commit()
    }

    fun clear(context: Context) = write(context, FolderWatchState())
}
