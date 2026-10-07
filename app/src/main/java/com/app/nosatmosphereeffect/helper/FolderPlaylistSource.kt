package com.app.nosatmosphereeffect.helper

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.app.nosatmosphereeffect.BuildConfig
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.storage.ActiveFolderWatch
import com.app.nosatmosphereeffect.storage.PlaylistCollectionStore
import com.app.nosatmosphereeffect.storage.PlaylistImageSource
import com.app.nosatmosphereeffect.storage.SavedPlaylistLibrary
import com.app.nosatmosphereeffect.storage.WallpaperStorageCoordinator
import com.app.nosatmosphereeffect.storage.WatchedFolder
import java.io.File

internal data class MediaFolder(
    val id: String,
    val name: String,
    val imageCount: Int,
    val cover: Uri?
)

internal data class MediaImage(
    val id: Long,
    val uri: Uri
)

/**
 * Folder playlists: reads device folders (MediaStore image buckets) and keeps
 * the active playlist in sync with them. Only the `folder` build flavor
 * declares the photo permissions this needs; everywhere else
 * [isAvailable] is false and the UI never offers it.
 */
internal object FolderPlaylistSource {
    private const val TAG = "FolderPlaylistSource"
    private const val WALLPAPER_PREFS = "wallpaper_prefs"
    private const val KEY_FORCE_ROTATION = "folder_force_rotation"

    val isAvailable: Boolean get() = BuildConfig.FOLDER_PLAYLISTS

    /**
     * What to request. Normally both photo permissions, so the system offers
     * its own "Allow all / Select photos" choice. With "Select photos" already
     * granted, asking for READ_MEDIA_VISUAL_USER_SELECTED again only reopens
     * the photo picker, so ask for full access alone to get the permission
     * dialog (with "Allow all") back.
     */
    fun requestedPermissions(context: Context): Array<String> {
        if (hasPartialAccessOnly(context)) {
            return arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }

    /** Full photo access, required to notice images added later. */
    fun hasFullAccess(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Android 14+ "Select photos": only the picked images are visible. */
    fun hasPartialAccessOnly(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return !hasFullAccess(context) &&
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun listFolders(context: Context): List<MediaFolder> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val folders = LinkedHashMap<String, MediaFolder>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val bucketId = cursor.getString(bucketColumn) ?: continue
                    val existing = folders[bucketId]
                    folders[bucketId] = existing?.copy(imageCount = existing.imageCount + 1)
                        ?: MediaFolder(
                            id = bucketId,
                            name = cursor.getString(nameColumn)?.takeIf(String::isNotBlank)
                                ?: context.getString(R.string.folders_unnamed),
                            imageCount = 1,
                            cover = imageUri(cursor.getLong(idColumn))
                        )
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Photo access was denied while listing folders", error)
        }
        return folders.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Images in [folderIds], oldest first so playlists keep a stable order.
     * Returns null when the folders could not be read, which callers must not
     * mistake for "every image was deleted".
     */
    fun imagesIn(context: Context, folderIds: Collection<String>): List<MediaImage>? {
        if (folderIds.isEmpty()) return emptyList()
        val placeholders = folderIds.joinToString(",") { "?" }
        val images = mutableListOf<MediaImage>()
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.Images.Media.BUCKET_ID} IN ($placeholders)",
                folderIds.toTypedArray(),
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            ) ?: return null
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    images += MediaImage(id, imageUri(id))
                }
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "Photo access was denied while reading folders", error)
            return null
        }
        return images
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
        if (!isAvailable || !hasFullAccess(context)) return unchanged
        return WallpaperStorageCoordinator.runExclusive {
            if (PlaylistModeManager.getMode(context) != PlaylistModeManager.MODE_STANDARD) {
                return@runExclusive unchanged
            }
            val watch = ActiveFolderWatch.read(context)
            if (watch.isEmpty) return@runExclusive unchanged
            val playlistDir = PlaylistModeManager.standardPlaylistDir(context)
            // This runs on every screen-off. When nothing in the media library,
            // the followed folders or the playlist has changed since the last
            // sync, there is nothing to find, so the query is skipped.
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
     * What a sync depends on: the media library's version and generation on
     * every external volume (they advance on any change to it), the followed
     * folders and seen images, and the playlist's size. Null when the library
     * will not say, which always syncs.
     */
    private fun syncKey(
        context: Context,
        watch: com.app.nosatmosphereeffect.storage.FolderWatchState,
        playlistDir: File
    ): String? = runCatching {
        val generations = MediaStore.getExternalVolumeNames(context)
            .sorted()
            .joinToString(",") { volume -> "$volume=${MediaStore.getGeneration(context, volume)}" }
        val entries = PlaylistModeManager.imageFiles(playlistDir).size
        "${MediaStore.getVersion(context)}|$generations|${watch.hashCode()}|$entries"
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

    private fun imageUri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}
