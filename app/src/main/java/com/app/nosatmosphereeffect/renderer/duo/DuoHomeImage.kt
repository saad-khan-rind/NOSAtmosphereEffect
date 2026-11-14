package com.app.nosatmosphereeffect.renderer.duo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.app.nosatmosphereeffect.image.WallpaperFitHelper
import java.io.File
import java.util.UUID

/**
 * Duo's second photo, the one the home screen shows: its own file beside the
 * wallpaper's (which Duo shows on the lock screen), and its own playlist
 * folder. Until one is picked, Duo shows the wallpaper on both screens.
 */
object DuoHomeImage {
    private const val TAG = "DuoHomeImage"
    private const val FILE = "duo_home.jpg"
    private const val PLAYLIST_DIR = "duo_home_playlist"
    /** The playlist photo on the home screen now, so the next is another one. */
    private const val LAST_KEY = "duo_home_last"
    private const val MAX_SIDE_FACTOR = 1.5f
    private const val JPEG_QUALITY = 92

    fun file(context: Context) = File(context.filesDir, FILE)

    /** The home photos a playlist picks from. */
    fun playlistDir(context: Context) = File(context.filesDir, PLAYLIST_DIR)

    fun exists(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0L }

    /**
     * The home photo fitted to a [surfaceW] by [surfaceH] screen, as the
     * wallpaper is; the wallpaper itself when there is none, or it can't be read.
     */
    fun loadForRender(context: Context, surfaceW: Int, surfaceH: Int): WallpaperFitHelper.RenderImage {
        val source = (if (exists(context)) BitmapFactory.decodeFile(file(context).path) else null)
            ?: return WallpaperFitHelper.loadForRender(context, surfaceW, surfaceH)
        return fit(context, source, surfaceW, surfaceH)
    }

    /** Saves the photo at [uri] as the home photo. False if it can't be read. */
    fun pick(context: Context, uri: Uri): Boolean = import(context, uri, file(context))

    // ---- The home screen's playlist ---------------------------------------------

    fun playlistFiles(context: Context): List<File> =
        playlistDir(context).listFiles { entry -> entry.isFile && entry.name.endsWith(".jpg") }
            ?.sortedBy { it.name }.orEmpty()

    /** Adds the photos at [uris] to the playlist; returns how many were added. */
    fun addToPlaylist(context: Context, uris: List<Uri>): Int {
        val dir = playlistDir(context).apply { mkdirs() }
        return uris.count { uri -> import(context, uri, File(dir, "${UUID.randomUUID()}.jpg")) }
    }

    fun clearPlaylist(context: Context) {
        playlistFiles(context).forEach { it.delete() }
        preferences(context).edit().remove(LAST_KEY).apply()
    }

    /**
     * A playlist's next home photo: another one at random, copied over the
     * home photo. False without a playlist, when nothing changed.
     */
    fun rotate(context: Context): Boolean {
        val files = playlistFiles(context)
        if (files.isEmpty()) return false
        val last = preferences(context).getString(LAST_KEY, null)
        // Never the same one twice running, when there is another to show.
        val next = (files.filter { it.name != last }.ifEmpty { files }).random()
        if (next.name == last && exists(context)) return false
        val target = file(context)
        val staged = File(target.parentFile, "$FILE.tmp")
        next.copyTo(staged, overwrite = true)
        // Moved into place whole, so a frame drawn meanwhile never reads half a file.
        if (!staged.renameTo(target)) {
            staged.delete()
            return false
        }
        preferences(context).edit().putString(LAST_KEY, next.name).apply()
        return true
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences("wallpaper_prefs", Context.MODE_PRIVATE)

    /**
     * Copies the photo at [uri] into [target] as a JPEG no bigger than the
     * screen needs, the right way up. False if it can't be read.
     */
    private fun import(context: Context, uri: Uri, target: File): Boolean = runCatching {
        val metrics = context.resources.displayMetrics
        // Room to scroll sideways, and for the zoom of a wake transition.
        val longest = (maxOf(metrics.widthPixels, metrics.heightPixels) * MAX_SIDE_FACTOR).toInt()
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = maxOf(info.size.width, info.size.height)
            if (side > longest) {
                val scale = longest.toFloat() / side
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
        try {
            target.parentFile?.mkdirs()
            val staged = File(target.parentFile, "${target.name}.tmp")
            staged.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            check(staged.renameTo(target)) { "Unable to move the photo into place" }
        } finally {
            bitmap.recycle()
        }
        true
    }.onFailure { Log.w(TAG, "Unable to import a photo for Duo", it) }.getOrDefault(false)

    /** [source] fitted for the home screen; [source] is recycled unless it is what comes back. */
    fun fit(context: Context, source: Bitmap, surfaceW: Int, surfaceH: Int): WallpaperFitHelper.RenderImage {
        val fitted = WallpaperFitHelper.fitForRender(context, source, surfaceW, surfaceH)
        if (fitted.bitmap !== source && !source.isRecycled) source.recycle()
        return fitted
    }
}
