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
    val fillMode: String
)

internal object PlaylistCollectionStore {
    private const val TAG = "PlaylistCollectionStore"
    // Quality 100 forces libjpeg into a much slower near-lossless encode
    // path for barely-perceptible gain on a downscaled wallpaper image; 92
    // is visually indistinguishable at wallpaper viewing distance/zoom and
    // encodes noticeably faster (and produces a smaller file).
    private const val STAGED_JPEG_QUALITY = 92

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

    private fun metadataFor(index: Int, item: PlaylistImageSource): JSONObject {
        return JSONObject().apply {
            put("original", "original_$index.jpg")
            put("isEdited", item.isEdited)
            put("fitMode", item.fitMode)
            put("fillMode", item.fillMode)
            MatrixStatePolicy.copyIfValid(item.matrixState)?.let { values ->
                put("matrix", JSONArray().apply {
                    values.forEach { value -> put(value.toDouble()) }
                })
            }
        }
    }

}
