package com.app.nosatmosphereeffect.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.app.nosatmosphereeffect.helper.ImageFitMode
import com.app.nosatmosphereeffect.helper.ImageFitPolicy
import com.app.nosatmosphereeffect.helper.MatrixStatePolicy
import com.app.nosatmosphereeffect.helper.PlaylistFilePolicy
import com.app.nosatmosphereeffect.helper.PlaylistModeManager
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.image.BitmapStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class PlaylistImageSource(
    val originalUri: Uri,
    val isEdited: Boolean,
    val editedFilePath: String?,
    val matrixState: FloatArray?,
    val fitMode: String,
    val fillMode: String,
    /** Id of the folder image it came from (FolderPlaylistSource), when it did. */
    val mediaId: Long? = null
)

internal object PlaylistCollectionStore {
    private const val TAG = "PlaylistCollectionStore"
    private const val STAGED_JPEG_QUALITY = 100
    const val KEY_MEDIA_ID = "mediaId"

    @Throws(IOException::class, SecurityException::class)
    fun stage(
        context: Context,
        items: List<PlaylistImageSource>,
        stagedImages: File,
        stagedOriginals: File,
        targetWidth: Int,
        targetHeight: Int,
        onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        if (items.isEmpty()) throw IOException("Playlist is empty")
        if (targetWidth <= 0 || targetHeight <= 0) {
            throw IOException("Display dimensions are unavailable")
        }

        FileTransactions.prepareEmptyDirectory(stagedImages)
        FileTransactions.prepareEmptyDirectory(stagedOriginals)
        val metadata = JSONArray()

        items.forEachIndexed { index, item ->
            val wallpaper = File(stagedImages, "wallpaper_$index.jpg")
            val original = File(stagedOriginals, "original_$index.jpg")
            UriFiles.copyAtomically(context, item.originalUri, original)

            if (item.isEdited) {
                val edited = item.editedFilePath
                    ?.let(::File)
                    ?.takeIf(File::isFile)
                    ?: throw FileNotFoundException("Edited image $index is missing")
                UriFiles.copyAtomically(context, Uri.fromFile(edited), wallpaper)
            } else {
                // Decode close to the wallpaper's actual target size instead
                // of the general-purpose 4096px cap: this bitmap is fit and
                // discarded immediately below, so for a high-megapixel photo
                // decoding far more pixels than the screen will ever show
                // just burns extra decode time and peak memory for nothing.
                val source = BitmapDecoder.decodeUri(
                    context,
                    item.originalUri,
                    targetWidth,
                    targetHeight
                )
                val bitmap = WallpaperFitHelper.fitBitmap(
                    source,
                    targetWidth,
                    targetHeight,
                    item.fitMode,
                    item.fillMode
                )
                try {
                    BitmapStore.writeJpegAtomically(bitmap, wallpaper, quality = STAGED_JPEG_QUALITY)
                } finally {
                    bitmap.recycle()
                }
            }
            metadata.put(metadataFor(index, item))
            onProgress(index + 1, items.size)
        }

        FileTransactions.writeTextAtomically(
            File(stagedImages, "metadata.json"),
            metadata.toString()
        )
    }

    fun activateFirst(
        context: Context,
        playlistDirectory: File,
        originalsDirectory: File,
        activeWallpaper: File,
        activeSource: File
    ): File {
        val transaction = beginActivatingFirst(
            context,
            playlistDirectory,
            originalsDirectory,
            activeWallpaper,
            activeSource
        )
        transaction.commit()
        return File(playlistDirectory, "wallpaper_0.jpg")
    }

    fun beginActivatingFirst(
        context: Context,
        playlistDirectory: File,
        originalsDirectory: File,
        activeWallpaper: File,
        activeSource: File
    ): FileTransactions.ReplacementTransaction {
        val firstWallpaper = File(playlistDirectory, "wallpaper_0.jpg")
        val firstOriginal = File(originalsDirectory, "original_0.jpg")
        if (!firstWallpaper.isFile || !firstOriginal.isFile) {
            throw FileNotFoundException("The playlist has no complete first image")
        }

        val token = UUID.randomUUID().toString()
        val stagedWallpaper = File(activeWallpaper.parentFile, ".active-wallpaper-$token.staged")
        val stagedSource = File(activeSource.parentFile, ".active-source-$token.staged")
        var failure: Exception? = null
        try {
            UriFiles.copyAtomically(context, Uri.fromFile(firstWallpaper), stagedWallpaper)
            UriFiles.copyAtomically(context, Uri.fromFile(firstOriginal), stagedSource)
            return FileTransactions.beginReplacingFiles(
                listOf(
                    stagedWallpaper to activeWallpaper,
                    stagedSource to activeSource
                )
            )
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            listOf(stagedWallpaper, stagedSource).forEach { staged ->
                try {
                    FileTransactions.deleteRecursively(staged)
                } catch (cleanupError: Exception) {
                    if (failure == null) {
                        Log.w(TAG, "Could not remove ${staged.absolutePath}", cleanupError)
                    } else {
                        failure.addSuppressed(cleanupError)
                    }
                }
            }
        }
    }

    /**
     * Appends [items] to an existing playlist in place, continuing its
     * `wallpaper_N.jpg` numbering and `metadata.json`. Returns how many images
     * were added; an item that cannot be read is skipped, not fatal.
     * Run inside [WallpaperStorageCoordinator.runExclusive].
     */
    @Throws(IOException::class)
    fun append(
        context: Context,
        items: List<PlaylistImageSource>,
        playlistDirectory: File,
        originalsDirectory: File,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        if (items.isEmpty()) return 0
        if (targetWidth <= 0 || targetHeight <= 0) {
            throw IOException("Display dimensions are unavailable")
        }
        val existing = PlaylistModeManager.imageFiles(playlistDirectory)
        if (existing.isEmpty()) throw IOException("There is no playlist to extend")

        val metadataFile = File(playlistDirectory, "metadata.json")
        val metadata = if (metadataFile.isFile) JSONArray(metadataFile.readText()) else null
        // metadata[i] describes wallpaper_i, so only append when they line up.
        val nextIndex = (existing.mapNotNull { PlaylistFilePolicy.index(it.name) }.max() + 1)
            .let { if (metadata != null && metadata.length() != it) null else it }
            ?: throw IOException("Playlist metadata does not match its images")

        var index = nextIndex
        items.forEach { item ->
            val wallpaper = File(playlistDirectory, "wallpaper_$index.jpg")
            val original = File(originalsDirectory, "original_$index.jpg")
            try {
                UriFiles.copyAtomically(context, item.originalUri, original)
                val source = BitmapDecoder.decodeUri(
                    context,
                    item.originalUri,
                    targetWidth,
                    targetHeight
                )
                val bitmap = WallpaperFitHelper.fitBitmap(
                    source,
                    targetWidth,
                    targetHeight,
                    item.fitMode,
                    item.fillMode
                )
                try {
                    BitmapStore.writeJpegAtomically(bitmap, wallpaper, quality = STAGED_JPEG_QUALITY)
                } finally {
                    bitmap.recycle()
                }
            } catch (error: Exception) {
                // One unreadable or deleted image must not block the others.
                Log.w(TAG, "Skipping ${item.originalUri} while extending the playlist", error)
                FileTransactions.deleteRecursively(original)
                FileTransactions.deleteRecursively(wallpaper)
                return@forEach
            }
            metadata?.put(metadataFor(index, item))
            index++
        }
        if (metadata != null && index != nextIndex) {
            FileTransactions.writeTextAtomically(metadataFile, metadata.toString())
        }
        return index - nextIndex
    }

    /** Folder image ids recorded for each entry of a playlist, by file index. */
    fun mediaIds(playlistDirectory: File): Map<Int, Long> {
        val metadataFile = File(playlistDirectory, "metadata.json")
        if (!metadataFile.isFile) return emptyMap()
        val metadata = JSONArray(metadataFile.readText())
        return (0 until metadata.length()).mapNotNull { index ->
            val entry = metadata.optJSONObject(index) ?: return@mapNotNull null
            if (!entry.has(KEY_MEDIA_ID)) return@mapNotNull null
            index to entry.getLong(KEY_MEDIA_ID)
        }.toMap()
    }

    /**
     * Removes the entries at [removeIndices] and renumbers the rest so
     * `wallpaper_i` / `original_i` / `metadata[i]` stay contiguous. The new
     * collection is staged beside the live one and swapped in atomically.
     * Returns how many entries were removed. Never empties a playlist.
     * Run inside [WallpaperStorageCoordinator.runExclusive].
     */
    @Throws(IOException::class)
    fun removeEntries(
        playlistDirectory: File,
        originalsDirectory: File,
        removeIndices: Set<Int>
    ): Int {
        if (removeIndices.isEmpty()) return 0
        val metadataFile = File(playlistDirectory, "metadata.json")
        if (!metadataFile.isFile) return 0
        val metadata = JSONArray(metadataFile.readText())
        val kept = (0 until metadata.length()).filter { index ->
            index !in removeIndices && File(playlistDirectory, "wallpaper_$index.jpg").isFile
        }
        if (kept.isEmpty() || kept.size == metadata.length()) return 0

        val token = UUID.randomUUID().toString()
        val stagedImages = File(playlistDirectory.parentFile, ".playlist-compact-$token.staged")
        val stagedOriginals = File(originalsDirectory.parentFile, ".originals-compact-$token.staged")
        try {
            FileTransactions.prepareEmptyDirectory(stagedImages)
            FileTransactions.prepareEmptyDirectory(stagedOriginals)
            val compacted = JSONArray()
            kept.forEachIndexed { newIndex, oldIndex ->
                File(playlistDirectory, "wallpaper_$oldIndex.jpg")
                    .copyTo(File(stagedImages, "wallpaper_$newIndex.jpg"))
                val original = File(originalsDirectory, "original_$oldIndex.jpg")
                if (original.isFile) {
                    original.copyTo(File(stagedOriginals, "original_$newIndex.jpg"))
                }
                compacted.put(
                    JSONObject(metadata.getJSONObject(oldIndex).toString())
                        .put("original", "original_$newIndex.jpg")
                )
            }
            FileTransactions.writeTextAtomically(
                File(stagedImages, "metadata.json"),
                compacted.toString()
            )
            FileTransactions.replaceDirectories(
                listOf(stagedImages to playlistDirectory, stagedOriginals to originalsDirectory)
            )
        } finally {
            listOf(stagedImages, stagedOriginals).forEach { staged ->
                try {
                    FileTransactions.deleteRecursively(staged)
                } catch (error: IOException) {
                    Log.w(TAG, "Could not remove ${staged.absolutePath}", error)
                }
            }
        }
        return metadata.length() - kept.size
    }

    private fun metadataFor(index: Int, item: PlaylistImageSource): JSONObject {
        return JSONObject().apply {
            put("original", "original_$index.jpg")
            put("isEdited", item.isEdited)
            put("fitMode", item.fitMode)
            put("fillMode", item.fillMode)
            item.mediaId?.let { put(KEY_MEDIA_ID, it) }
            MatrixStatePolicy.copyIfValid(item.matrixState)?.let { values ->
                put("matrix", JSONArray().apply {
                    values.forEach { value -> put(value.toDouble()) }
                })
            }
        }
    }

}
