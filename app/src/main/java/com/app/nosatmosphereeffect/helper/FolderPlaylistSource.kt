package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.storage.ActiveFolderWatch
import com.app.nosatmosphereeffect.storage.PlaylistCollectionStore
import com.app.nosatmosphereeffect.storage.PlaylistImageSource
import com.app.nosatmosphereeffect.storage.SavedPlaylistLibrary
import com.app.nosatmosphereeffect.storage.WallpaperStorageCoordinator
import com.app.nosatmosphereeffect.storage.WatchedFolder
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class MediaImage(
    val id: Long,
    val uri: Uri
)

/**
 * Folder playlists: reads folders the user picked in the system folder picker
 * (Storage Access Framework) and keeps the active playlist in sync with them.
 * No permission is involved: picking a folder grants read access to it alone,
 * kept across restarts. A folder's id is its tree URI.
 */
internal object FolderPlaylistSource {
    private const val TAG = "FolderPlaylistSource"
    private const val WALLPAPER_PREFS = "wallpaper_prefs"
    private const val KEY_FORCE_ROTATION = "folder_force_rotation"

    /** Where the system folder picker opens: Pictures on the phone's own storage. */
    fun initialFolder(): Uri =
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Pictures")

    /**
     * Keeps read access to the picked [treeUri] and names it. Null when the
     * folder can't be followed (the provider gives no lasting access).
     */
    fun follow(context: Context, treeUri: Uri): WatchedFolder? = try {
        // shortcut: grants are never released (Android keeps up to 512); release unused ones if that's ever reached.
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = context.contentResolver.query(
            rootDocument(treeUri),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        WatchedFolder(
            treeUri.toString(),
            name?.takeIf(String::isNotBlank) ?: context.getString(R.string.folders_unnamed)
        )
    } catch (error: Exception) {
        Log.w(TAG, "Could not follow the picked folder", error)
        null
    }

    /**
     * Images directly inside [folderIds], oldest first so playlists keep a
     * stable order. Returns null when any folder could not be read (access
     * lost, folder moved or renamed, storage unmounted), which callers must
     * not mistake for "every image was deleted".
     */
    fun imagesIn(context: Context, folderIds: Collection<String>): List<MediaImage>? {
        val images = mutableListOf<Pair<Long, MediaImage>>()
        for (folderId in folderIds) {
            try {
                val tree = Uri.parse(folderId)
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree)
                )
                val cursor = context.contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                    ),
                    null,
                    null,
                    null
                ) ?: return null
                cursor.use {
                    while (it.moveToNext()) {
                        if (it.getString(1)?.startsWith("image/") != true) continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                        images += it.getLong(2) to MediaImage(idOf(uri), uri)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not read a followed folder", error)
                return null
            }
        }
        return images
            .sortedWith(compareBy({ it.first }, { it.second.uri.toString() }))
            .map { it.second }
    }

    internal data class SyncResult(val added: Int, val removed: Int) {
        val changed: Boolean get() = added > 0 || removed > 0
    }

    /**
     * Mirrors the active playlist's watched folders: removes images whose
     * source was deleted and appends images added since the last check.
     * Runs when the app opens and on every screen-off rotation. Call off the
     * main thread.
     */
    fun syncActivePlaylist(context: Context): SyncResult {
        val unchanged = SyncResult(0, 0)
        return WallpaperStorageCoordinator.runExclusive {
            if (PlaylistModeManager.getMode(context) != PlaylistModeManager.MODE_STANDARD) {
                return@runExclusive unchanged
            }
            val watch = ActiveFolderWatch.read(context)
            if (watch.isEmpty) return@runExclusive unchanged
            val playlistDir = PlaylistModeManager.standardPlaylistDir(context)
            // This runs on every screen-off. When none of the followed folders,
            // the watch or the playlist has changed since the last sync, there
            // is nothing to find, so the listing is skipped.
            val before = syncKey(context, watch, playlistDir)
            if (before != null && before == lastSyncKey) return@runExclusive unchanged
            val current = imagesIn(context, watch.folders.map(WatchedFolder::id))
                ?: return@runExclusive unchanged
            val present = current.mapTo(HashSet(), MediaImage::id)

            val originalsDir = File(context.filesDir, PlaylistModeManager.STANDARD_ORIGINALS_DIR)

            // Removals first so appended images number after the compacted set.
            val entryIds = PlaylistCollectionStore.mediaIds(playlistDir)
            val entryCount = PlaylistModeManager.imageFiles(playlistDir).size
            val removable = FolderSyncPolicy.removableIndices(
                List(entryCount) { index -> entryIds[index] },
                present
            )
            val removed = PlaylistCollectionStore.removeEntries(playlistDir, originalsDir, removable)
            if (removed > 0) followRenumbering(context, removable)

            val fresh = current.filter { it.id !in watch.knownMediaIds }
            val added = if (fresh.isEmpty()) {
                0
            } else {
                val (width, height) = playlistImageSize(playlistDir) ?: return@runExclusive SyncResult(0, removed)
                val fitMode = WallpaperFitHelper.getDefaultFitMode(context)
                val fillMode = WallpaperFitHelper.getDefaultFillMode(context)
                PlaylistCollectionStore.append(
                    context = context,
                    items = fresh.map { image ->
                        PlaylistImageSource(
                            originalUri = image.uri,
                            isEdited = false,
                            editedFilePath = null,
                            matrixState = null,
                            fitMode = fitMode,
                            fillMode = fillMode,
                            mediaId = image.id
                        )
                    },
                    playlistDirectory = playlistDir,
                    originalsDirectory = originalsDir,
                    targetWidth = width,
                    targetHeight = height
                )
            }

            // Every offered id counts as seen, including ones that failed to
            // decode, so a broken file is not retried on every check.
            val updated = watch.copy(
                knownMediaIds = FolderSyncPolicy.knownAfterSync(
                    watch.knownMediaIds,
                    present,
                    fresh.map(MediaImage::id)
                )
            )
            if (updated != watch) ActiveFolderWatch.write(context, updated)
            lastSyncKey = syncKey(context, updated, playlistDir)
            val result = SyncResult(added, removed)
            if (result.changed) {
                SavedPlaylistLibrary.activeId(context)?.let { id ->
                    runCatching {
                        SavedPlaylistLibrary.saveActive(context, id, name = null, watch = updated)
                    }.onFailure { error ->
                        Log.w(TAG, "Could not refresh the saved copy of the playlist", error)
                    }
                }
            }
            result
        }
    }

    /** The state last synced, in this process; see [syncKey]. */
    @Volatile private var lastSyncKey: String? = null

    /**
     * What a sync depends on: each followed folder's last-modified time (on
     * the phone's storage it changes whenever a file is added or deleted in
     * it), the followed folders and seen images, and the playlist's size.
     * Null, which always syncs, when a folder doesn't report the time, as
     * some cloud providers don't.
     */
    private fun syncKey(
        context: Context,
        watch: com.app.nosatmosphereeffect.storage.FolderWatchState,
        playlistDir: File
    ): String? = runCatching {
        val modified = watch.folders.map { folder ->
            val tree = Uri.parse(folder.id)
            context.contentResolver.query(
                rootDocument(tree),
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                cursor.takeIf { it.moveToFirst() && !it.isNull(0) }?.getLong(0)?.takeIf { it > 0 }
            } ?: return@runCatching null
        }
        val entries = PlaylistModeManager.imageFiles(playlistDir).size
        "$modified|${watch.hashCode()}|$entries"
    }.getOrNull()

    /**
     * Compaction renumbers `wallpaper_N.jpg`, so the remembered "last shown"
     * name must follow its image — or, if that image was the one deleted,
     * the next rotation is forced so a deleted photo does not stay on screen.
     */
    private fun followRenumbering(context: Context, removedIndices: Set<Int>) {
        val prefs = context.getSharedPreferences(WALLPAPER_PREFS, Context.MODE_PRIVATE)
        val key = PlaylistModeManager.lastImagePreferenceKey(PlaylistModeManager.MODE_STANDARD, false)
        val lastIndex = prefs.getString(key, null)?.let(PlaylistFilePolicy::index)
        val editor = prefs.edit()
        if (lastIndex == null || lastIndex in removedIndices) {
            editor.remove(key).putBoolean(KEY_FORCE_ROTATION, true)
        } else {
            val shifted = lastIndex - removedIndices.count { it < lastIndex }
            editor.putString(key, "wallpaper_$shifted.jpg")
        }
        editor.commit()
    }

    /** True when the displayed image was deleted and must be replaced. */
    fun isRotationForced(context: Context): Boolean {
        return context.getSharedPreferences(WALLPAPER_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_ROTATION, false)
    }

    fun clearForcedRotation(context: Context) {
        context.getSharedPreferences(WALLPAPER_PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_FORCE_ROTATION).commit()
    }

    fun knownIdsFor(context: Context, folders: List<WatchedFolder>): Set<Long> {
        return imagesIn(context, folders.map(WatchedFolder::id))
            ?.mapTo(HashSet(), MediaImage::id)
            .orEmpty()
    }

    /**
     * New images are fitted to the size the playlist was staged at, which
     * works from the wallpaper service too (it has no window to measure).
     */
    private fun playlistImageSize(playlistDir: File): Pair<Int, Int>? {
        val sample = PlaylistModeManager.imageFiles(playlistDir).firstOrNull() ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sample.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    private fun rootDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /**
     * Playlists keep a Long id per image (MediaStore's, before folders were
     * picked through the system picker); a document's is taken from a hash
     * of its URI, so the same file always gets the same id.
     */
    private fun idOf(document: Uri): Long =
        ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(document.toString().toByteArray())).long
}
