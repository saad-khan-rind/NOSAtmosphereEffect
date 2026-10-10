package com.app.nosatmosphereeffect.storage

import android.content.Context
import android.net.Uri
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.image.BitmapDecoder
import com.app.nosatmosphereeffect.image.BitmapStore
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * A playlist entry that points at an image in a followed folder instead of
 * keeping copies of it: `wallpaper_N.ref` in place of `wallpaper_N.jpg` and
 * `original_N.jpg`. The folder picker's grant lasts, so the image is read from
 * the folder whenever it is shown, fitted with the global fit settings of that
 * moment. Only folder images the user hasn't cropped are kept this way.
 */
internal object PlaylistImageRef {
    const val EXTENSION = "ref"
    private const val STAGED_JPEG_QUALITY = 100
    private const val KEY_URI = "uri"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"

    /** [width] x [height] is the screen size the playlist was made for. */
    data class Ref(val uri: Uri, val width: Int, val height: Int)

    fun isRef(file: File): Boolean = file.extension == EXTENSION

    fun canPoint(uri: Uri, mediaId: Long?, isEdited: Boolean): Boolean =
        mediaId != null && !isEdited && uri.scheme == "content"

    @Throws(IOException::class)
    fun write(file: File, ref: Ref) {
        FileTransactions.writeTextAtomically(
            file,
            JSONObject()
                .put(KEY_URI, ref.uri.toString())
                .put(KEY_WIDTH, ref.width)
                .put(KEY_HEIGHT, ref.height)
                .toString()
        )
    }

    /** The pointer in [file], or null when it isn't one or can't be read. */
    fun read(file: File): Ref? = try {
        val json = JSONObject(file.readText())
        Ref(
            Uri.parse(json.getString(KEY_URI)),
            json.getInt(KEY_WIDTH),
            json.getInt(KEY_HEIGHT)
        ).takeIf { it.width > 0 && it.height > 0 }
    } catch (error: IOException) {
        null
    } catch (error: JSONException) {
        null
    }

    /** Thrown when a followed folder's image can't be opened while saving a playlist. */
    class UnavailableException(uri: Uri, cause: Throwable) : IOException("Can't open folder image $uri", cause)

    /** Fails, as copying would have, when [uri] can't be opened as an image. */
    @Throws(UnavailableException::class)
    fun checkReadable(context: Context, uri: Uri) {
        try {
            BitmapDecoder.decodeUri(context, uri, maxDimension = 64).recycle()
        } catch (error: Exception) {
            // The provider reports a missing folder as IllegalArgumentException, not an IOException.
            throw UnavailableException(uri, error)
        }
    }

    /**
     * Writes what a copied entry would hold: [wallpaper] fitted to the
     * playlist's screen with the current global fit settings and, when
     * [source] is given, the un-cropped image for scrolling and the fit modes
     * that show the whole picture.
     */
    @Throws(IOException::class, SecurityException::class)
    fun materialize(context: Context, ref: Ref, wallpaper: File, source: File?) {
        val decoded = BitmapDecoder.decodeUri(context, ref.uri, ref.width, ref.height)
        val fitted = WallpaperFitHelper.fitBitmap(
            decoded,
            ref.width,
            ref.height,
            WallpaperFitHelper.getDefaultFitMode(context),
            WallpaperFitHelper.getDefaultFillMode(context)
        )
        try {
            BitmapStore.writeJpegAtomically(fitted, wallpaper, quality = STAGED_JPEG_QUALITY)
        } finally {
            fitted.recycle()
        }
        if (source != null) UriFiles.copyAtomically(context, ref.uri, source)
    }
}
