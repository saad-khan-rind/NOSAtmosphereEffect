package com.app.nosatmosphereeffect.activity

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.app.nosatmosphereeffect.helper.MatrixStatePolicy
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.storage.FileTransactions
import com.app.nosatmosphereeffect.storage.WatchedFolder
import java.io.File

internal data class PlaylistDraftItem(
    val originalUri: Uri,
    val isEdited: Boolean = false,
    val editedFilePath: String? = null,
    val matrixState: FloatArray? = null,
    val fitMode: String = WallpaperFitHelper.MODE_FILL,
    val fillMode: String = WallpaperFitHelper.FILL_BLACK,
    /** Id of the folder image it came from (FolderPlaylistSource), when it did. */
    val mediaId: Long? = null
)

internal class StandardPlaylistDraftState : ViewModel() {
    val items = mutableStateListOf<PlaylistDraftItem>()
    var initialized = false
    /** Library entry this draft updates when applied; null creates a new one. */
    var savedPlaylistId by mutableStateOf<String?>(null)
    var playlistName by mutableStateOf<String?>(null)
    val watchedFolders = mutableStateListOf<WatchedFolder>()
    /** Folder image ids already offered from [watchedFolders]; never re-added. */
    var knownMediaIds: Set<Long> = emptySet()
    var atmosphereGlassEnabled by mutableStateOf(false)
    var isProcessing by mutableStateOf(false)
    var applyCompleted by mutableStateOf(false)
    var applyError by mutableStateOf<String?>(null)
    var processedCount by mutableStateOf(0)
    var totalCount by mutableStateOf(0)
}

internal class ThemePlaylistDraftState : ViewModel() {
    val lightItems = mutableStateListOf<PlaylistDraftItem>()
    val darkItems = mutableStateListOf<PlaylistDraftItem>()
    var initialized = false
    var atmosphereGlassEnabled by mutableStateOf(false)
    var isProcessing by mutableStateOf(false)
    var applyCompleted by mutableStateOf(false)
    var applyError by mutableStateOf<String?>(null)
    var processedCount by mutableStateOf(0)
    var totalCount by mutableStateOf(0)
}

internal object PlaylistDraftStateCodec {
    fun encode(items: List<PlaylistDraftItem>): ArrayList<Bundle> {
        return ArrayList(
            items.map { item ->
                Bundle().apply {
                    putParcelable(KEY_URI, item.originalUri)
                    putBoolean(KEY_EDITED, item.isEdited)
                    putString(KEY_EDITED_PATH, item.editedFilePath)
                    putFloatArray(KEY_MATRIX, MatrixStatePolicy.copyIfValid(item.matrixState))
                    putString(KEY_FIT_MODE, item.fitMode)
                    putString(KEY_FILL_MODE, item.fillMode)
                    item.mediaId?.let { putLong(KEY_MEDIA_ID, it) }
                }
            }
        )
    }

    fun decode(items: ArrayList<Bundle>?): List<PlaylistDraftItem>? {
        if (items == null) return null
        return items.mapNotNull { state ->
            val uri = state.getParcelable(KEY_URI, Uri::class.java) ?: return@mapNotNull null
            PlaylistDraftItem(
                originalUri = uri,
                isEdited = state.getBoolean(KEY_EDITED),
                editedFilePath = state.getString(KEY_EDITED_PATH),
                matrixState = MatrixStatePolicy.copyIfValid(
                    state.getFloatArray(KEY_MATRIX)
                ),
                fitMode = state.getString(KEY_FIT_MODE)
                    ?: WallpaperFitHelper.MODE_FILL,
                fillMode = state.getString(KEY_FILL_MODE)
                    ?: WallpaperFitHelper.FILL_BLACK,
                mediaId = if (state.containsKey(KEY_MEDIA_ID)) state.getLong(KEY_MEDIA_ID) else null
            )
        }
    }

    private const val KEY_URI = "uri"
    private const val KEY_EDITED = "edited"
    private const val KEY_EDITED_PATH = "edited_path"
    private const val KEY_MATRIX = "matrix"
    private const val KEY_FIT_MODE = "fit_mode"
    private const val KEY_FILL_MODE = "fill_mode"
    private const val KEY_MEDIA_ID = "media_id"
}

internal object PlaylistDraftCache {
    fun delete(context: Context, path: String?) {
        val candidate = path?.let(::File) ?: return
        val cacheDirectory = context.cacheDir.canonicalFile
        val file = candidate.canonicalFile
        if (file.parentFile != cacheDirectory || !file.name.startsWith(FILE_PREFIX)) return
        FileTransactions.deleteRecursively(file)
    }

    private const val FILE_PREFIX = "cropped_playlist_"
}
