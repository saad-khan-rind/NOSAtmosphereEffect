package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.app.nosatmosphereeffect.storage.FileTransactions
import java.io.File
import java.io.IOException

/**
 * Central helper for fitting wallpaper images to the *actual* surface they are
 * rendered on.
 *
 * Solves two problems:
 *  1. User-selectable image fit ("Screen Fill", "Fit Image", "Stretch",
 *     "Rotate to Fit") with configurable empty-space fill (black bars,
 *     repeating pattern, mirrored pattern).
 *  2. Foldables / multi-display devices: the surface size changes between the
 *     cover and inner screens. Renderers re-fit the image for the current
 *     surface instead of stretching a bitmap that was prepared for a
 *     different screen.
 *
 * File layout in [Context.getFilesDir]:
 *  - wallpaper.jpg          the active, user-cropped image (legacy, unchanged)
 *  - wallpaper_src.jpg      the un-cropped source of the active image
 *  - next_wallpaper.jpg     cropped image queued for playlist rotation (legacy)
 *  - next_wallpaper_src.jpg un-cropped source queued for playlist rotation
 *
 * Display settings live in their own SharedPreferences file ("display_prefs")
 * on purpose: the apply flows wipe "app_prefs" and "wallpaper_prefs" on every
 * new wallpaper, but the user's fit preference should survive that.
 */
object WallpaperFitHelper {
    private const val TAG = "WallpaperFitHelper"

    const val PREFS_NAME = "display_prefs"

    // Per-slot display modes. A "slot" is one of the two materialized wallpaper
    // image sets on disk: the ACTIVE one (wallpaper.jpg / wallpaper_src.jpg) and
    // the NEXT one queued for playlist rotation (next_wallpaper.jpg /
    // next_wallpaper_src.jpg). Storing the fit per slot lets every playlist image
    // keep its own fit mode (e.g. image 1 = Screen Fill, image 2 = Stretch).
    const val KEY_ACTIVE_FIT = "active_fit_mode"
    const val KEY_ACTIVE_FILL = "active_fill_mode"
    const val KEY_NEXT_FIT = "next_fit_mode"
    const val KEY_NEXT_FILL = "next_fill_mode"

    // Default modes for new crops.
    const val KEY_DEFAULT_FIT = "default_fit_mode"
    const val KEY_DEFAULT_FILL = "default_fill_mode"

    // Horizontal wallpaper scrolling (home-screen page parallax). Lives in
    // display_prefs alongside the fit modes so it survives the app_prefs /
    // wallpaper_prefs wipe that happens on every new wallpaper. Global (not
    // per-slot): it is a device/launcher behaviour, not an image property.
    const val KEY_SCROLL_ENABLED = "wallpaper_scroll_enabled"

    // Cap the scrollable wallpaper width at this multiple of the screen width.
    // Keeps texture memory bounded (a 4:3 photo on a tall phone would otherwise
    // be ~3x screen width) while still giving a generous, stock-like pan range.
    private const val MAX_SCROLL_WIDTH_FACTOR = 2.0f

    const val MODE_FILL = "FILL"
    const val MODE_FIT = "FIT"
    const val MODE_STRETCH = "STRETCH"
    const val MODE_ROTATE_FIT = "ROTATE_FIT"

    const val FILL_BLACK = "BLACK"
    const val FILL_REPEAT = "REPEAT"
    const val FILL_MIRROR = "MIRROR"

    const val ACTIVE_WALLPAPER_FILE = "wallpaper.jpg"
    const val NEXT_WALLPAPER_FILE = "next_wallpaper.jpg"
    const val ACTIVE_SOURCE_FILE = "wallpaper_src.jpg"
    const val NEXT_SOURCE_FILE = "next_wallpaper_src.jpg"


    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getActiveFitMode(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_FIT, MODE_FILL) ?: MODE_FILL

    fun getActiveFillMode(context: Context): String =
        prefs(context).getString(KEY_ACTIVE_FILL, FILL_BLACK) ?: FILL_BLACK

    fun getNextFitMode(context: Context): String =
        prefs(context).getString(KEY_NEXT_FIT, MODE_FILL) ?: MODE_FILL

    fun getNextFillMode(context: Context): String =
        prefs(context).getString(KEY_NEXT_FILL, FILL_BLACK) ?: FILL_BLACK

    fun getDefaultFitMode(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_FIT, MODE_FILL) ?: MODE_FILL

    fun getDefaultFillMode(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_FILL, FILL_BLACK) ?: FILL_BLACK

    /** Sets the default display mode for new crops. */
    fun setDefaultModes(context: Context, fitMode: String, fillMode: String) {
        if (
            !prefs(context).edit()
                .putString(KEY_DEFAULT_FIT, fitMode)
                .putString(KEY_DEFAULT_FILL, fillMode)
                .commit()
        ) {
            throw IOException("Could not persist default wallpaper display modes")
        }
    }

    /** Sets the display mode for the active wallpaper (single-image crop, and the first playlist image). */
    fun setActiveModes(context: Context, fitMode: String, fillMode: String) {
        if (
            !prefs(context).edit()
                .putString(KEY_ACTIVE_FIT, fitMode)
                .putString(KEY_ACTIVE_FILL, fillMode)
                .commit()
        ) {
            throw IOException("Could not persist active wallpaper display modes")
        }
    }

    /** Sets the display mode for the queued next wallpaper (the next playlist image to rotate in). */
    fun setNextModes(context: Context, fitMode: String, fillMode: String) {
        if (
            !prefs(context).edit()
                .putString(KEY_NEXT_FIT, fitMode)
                .putString(KEY_NEXT_FILL, fillMode)
                .commit()
        ) {
            throw IOException("Could not persist queued wallpaper display modes")
        }
    }

    /**
     * Promotes the queued next mode before the renderer receives the new bitmap.
     */
    fun promoteNextMode(context: Context) {
        val p = prefs(context)
        val fit = p.getString(KEY_NEXT_FIT, MODE_FILL) ?: MODE_FILL
        val fill = p.getString(KEY_NEXT_FILL, FILL_BLACK) ?: FILL_BLACK
        if (
            !p.edit()
                .putString(KEY_ACTIVE_FIT, fit)
                .putString(KEY_ACTIVE_FILL, fill)
                .commit()
        ) {
            throw IOException("Could not promote queued wallpaper display modes")
        }
    }

    /** Modes other than plain screen-fill want the un-cropped source image. */
    fun needsSourceImage(mode: String): Boolean = mode != MODE_FILL

    fun isScrollEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCROLL_ENABLED, false)

    fun setScrollEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCROLL_ENABLED, enabled).apply()
    }

    /**
     * A bitmap prepared for the GL renderers together with the fraction of its
     * width that is visible on screen at any one time ([windowX], in (0, 1]).
     *
     * When scrolling is off, or the image is not wider than the screen, the
     * whole width is visible and [windowX] is 1.0 (the renderer then draws
     * exactly as it always did). When scrolling is on and the image overflows
     * horizontally, [windowX] < 1.0 and the renderer pans a screen-width window
     * across the texture in response to launcher offsets.
     */
    class RenderImage(val bitmap: Bitmap, val windowX: Float)

    /**
     * Loads the active wallpaper for rendering. Identical to [loadDisplayBitmap]
     * when scrolling is disabled. When enabled, returns a "fill-height, keep
     * width" bitmap (capped to [MAX_SCROLL_WIDTH_FACTOR]x the screen width) so
     * there is horizontal content to pan across.
     */
    fun loadForRender(
        context: Context,
        surfaceW: Int,
        surfaceH: Int,
        previewSource: (() -> Bitmap?)? = null
    ): RenderImage {
        if (previewSource != null) {
            val source = previewSource()
            if (source != null) {
                return RenderImage(
                    fitBitmap(source, surfaceW, surfaceH, MODE_FILL, FILL_BLACK),
                    1.0f
                )
            }
        }
        if (surfaceW > 0 && surfaceH > 0) {
            // A posture change (fold / unfold) usually finds the image for the
            // new surface size already prepared, which turns the re-fit into a
            // plain texture upload instead of a decode the compositor has to
            // paper over by stretching the previous frame.
            val prepared = WallpaperPostureCache.take(renderKey(context, surfaceW, surfaceH))
            if (prepared != null) return prepared
        }
        return buildRenderImage(context, surfaceW, surfaceH)
    }

    /**
     * Prepares — off the render thread — the image [loadForRender] would build
     * for [surfaceW] x [surfaceH], and parks it for the next call that asks for
     * exactly that. Used to get the other fold posture ready before the device
     * is actually folded into it. A no-op when it is already prepared.
     *
     * Runs on a background thread by contract: it decodes and rasterizes.
     */
    fun prewarm(context: Context, surfaceW: Int, surfaceH: Int) {
        if (surfaceW <= 0 || surfaceH <= 0) return
        val key = renderKey(context, surfaceW, surfaceH)
        if (WallpaperPostureCache.holds(key)) return
        WallpaperPostureCache.store(key, buildRenderImage(context, surfaceW, surfaceH))
    }

    /**
     * Identifies the image [loadForRender] would produce: the surface size, the
     * display settings that shape the fit, and a stamp of the wallpaper files
     * themselves so a changed wallpaper never matches a prepared one.
     */
    internal fun renderKey(context: Context, surfaceW: Int, surfaceH: Int): WallpaperPostureCache.Key =
        WallpaperPostureCache.Key(
            width = surfaceW,
            height = surfaceH,
            mode = getActiveFitMode(context),
            fill = getActiveFillMode(context),
            scroll = isScrollEnabled(context),
            stamp = wallpaperStamp(context)
        )

    private fun wallpaperStamp(context: Context): String {
        val filesDir = context.filesDir
        return fileStamp(File(filesDir, ACTIVE_WALLPAPER_FILE)) +
            "|" + fileStamp(File(filesDir, ACTIVE_SOURCE_FILE))
    }

    private fun fileStamp(file: File): String =
        if (file.isFile) "${file.lastModified()}:${file.length()}" else "-"

    private fun buildRenderImage(
        context: Context,
        surfaceW: Int,
        surfaceH: Int
    ): RenderImage {
        if (!isScrollEnabled(context) || surfaceW <= 0 || surfaceH <= 0) {
            return RenderImage(loadDisplayBitmap(context, surfaceW, surfaceH), 1.0f)
        }

        // Scrolling wants the full, un-cropped image so the parts the crop would
        // have trimmed are available to pan to. Fall back to the cropped image
        // (old installs / no source) — it simply will not have any pan slack.
        val filesDir = context.filesDir
        var source: Bitmap? = null
        val srcFile = File(filesDir, ACTIVE_SOURCE_FILE)
        if (srcFile.exists()) {
            source = decodeFileSampled(srcFile, ImageMemoryBudget.budgetBytes(context))
        }
        if (source == null) {
            val file = File(filesDir, ACTIVE_WALLPAPER_FILE)
            if (file.exists()) source = BitmapFactory.decodeFile(file.absolutePath)
        }
        if (source == null) {
            val placeholder = Bitmap.createBitmap(surfaceW, surfaceH, Bitmap.Config.ARGB_8888)
            placeholder.eraseColor(Color.BLUE)
            return RenderImage(placeholder, 1.0f)
        }
        return makeScrollBitmap(source, surfaceW, surfaceH)
    }

    /**
     * Scroll-aware variant of [fitToSurface] for queued playlist transitions.
     * Mirrors [loadForRender]: wide image kept wide when scrolling is on,
     * otherwise the legacy screen-fit.
     */
    fun fitForRender(context: Context, source: Bitmap, surfaceW: Int, surfaceH: Int): RenderImage {
        if (!isScrollEnabled(context) || surfaceW <= 0 || surfaceH <= 0) {
            return RenderImage(fitToSurface(context, source, surfaceW, surfaceH), 1.0f)
        }
        return makeScrollBitmap(source, surfaceW, surfaceH)
    }

    /**
     * Produces a bitmap scaled to exactly fill the surface HEIGHT, preserving
     * the source aspect ratio, then horizontally centre-cropped to at most
     * [MAX_SCROLL_WIDTH_FACTOR]x the surface width. Returns it with the visible
     * width fraction. Consumes [source] (recycled if a new bitmap is made).
     *
     * If the height-filled image is not actually wider than the screen (a tall
     * / narrow image), there is nothing to scroll, so it falls back to a normal
     * screen-fill and reports windowX = 1.0.
     */
    private fun makeScrollBitmap(source: Bitmap, surfaceW: Int, surfaceH: Int): RenderImage {
        if (source.width <= 0 || source.height <= 0) {
            return RenderImage(fitBitmap(source, surfaceW, surfaceH, MODE_FILL, FILL_BLACK), 1.0f)
        }

        val layout = ImageFitPolicy.scrollLayout(
            sourceWidth = source.width,
            sourceHeight = source.height,
            surfaceWidth = surfaceW,
            surfaceHeight = surfaceH,
            maxWidthFactor = MAX_SCROLL_WIDTH_FACTOR
        )
        if (layout == null) {
            return RenderImage(fitBitmap(source, surfaceW, surfaceH, MODE_FILL, FILL_BLACK), 1.0f)
        }

        val outW = layout.canvasWidth
        val outH = surfaceH

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val matrix = Matrix()
        matrix.setScale(layout.scale, layout.scale)
        matrix.postTranslate(layout.translateX, 0f)
        canvas.drawBitmap(source, matrix, paint)
        source.recycle()

        return RenderImage(output, layout.visibleWidthFraction)
    }

    /**
     * Loads the active wallpaper and fits it to the given surface size using
     * the user's display settings. Always returns a non-null bitmap (falls
     * back to a solid color if no wallpaper exists yet).
     *
     * If the surface size is not known yet (0 x 0) the image is returned
     * unfitted, exactly as the legacy code did.
     */
    fun loadDisplayBitmap(context: Context, surfaceW: Int, surfaceH: Int): Bitmap {
        val mode = getActiveFitMode(context)
        val fill = getActiveFillMode(context)
        val filesDir = context.filesDir

        var source: Bitmap? = null

        // Modes that show the whole image want the un-cropped source. Fall
        // back to the cropped wallpaper if no source exists (old installs).
        if (needsSourceImage(mode)) {
            val srcFile = File(filesDir, ACTIVE_SOURCE_FILE)
            if (srcFile.exists()) {
                source = decodeFileSampled(srcFile, ImageMemoryBudget.budgetBytes(context))
            }
        }

        if (source == null) {
            val file = File(filesDir, ACTIVE_WALLPAPER_FILE)
            if (file.exists()) {
                source = BitmapFactory.decodeFile(file.absolutePath)
            }
        }

        if (source == null) {
            source = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.BLUE)
        }

        return fitBitmap(source, surfaceW, surfaceH, mode, fill)
    }

    /**
     * Fits an already decoded bitmap (e.g. a queued playlist transition) to
     * the surface using the user's current display settings.
     */
    fun fitToSurface(context: Context, source: Bitmap, surfaceW: Int, surfaceH: Int): Bitmap {
        return fitBitmap(source, surfaceW, surfaceH, getActiveFitMode(context), getActiveFillMode(context))
    }

    /**
     * Pure geometry: produces a bitmap of exactly [targetW] x [targetH] from
     * [source] according to [mode] and [fillMode].
     *
     * NOTE: consumes [source] — if a new bitmap is created, the source is
     * recycled. Callers must only use (and recycle) the returned bitmap.
     */
    fun fitBitmap(source: Bitmap, targetW: Int, targetH: Int, mode: String, fillMode: String): Bitmap {
        // Surface size unknown: nothing sensible to do, keep legacy behavior.
        if (targetW <= 0 || targetH <= 0) return source

        // Fast path: the common case on regular phones. The crop already
        // matches the screen exactly, so avoid any re-encode quality loss.
        if (mode == MODE_FILL && source.width == targetW && source.height == targetH) {
            return source
        }

        // ROTATE_FIT: rotate the image 90° if its orientation does not match
        // the screen (e.g. a landscape photo on a portrait screen), then fit.
        var working = source
        var rotatedCopy = false
        if (mode == MODE_ROTATE_FIT) {
            if (ImageFitPolicy.shouldRotate(source.width, source.height, targetW, targetH)) {
                val rotate = Matrix().apply { postRotate(90f) }
                val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, rotate, true)
                if (rotated != source) {
                    working = rotated
                    rotatedCopy = true
                }
            }
        }

        val output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        val tw = targetW.toFloat()
        val th = targetH.toFloat()
        val fitMode = when (mode) {
            MODE_STRETCH -> ImageFitMode.STRETCH
            MODE_FILL -> ImageFitMode.FILL
            else -> ImageFitMode.FIT
        }
        val transform = ImageFitPolicy.transform(
            sourceWidth = working.width,
            sourceHeight = working.height,
            targetWidth = targetW,
            targetHeight = targetH,
            mode = fitMode
        )
        val matrix = Matrix()
        matrix.setScale(transform.scaleX, transform.scaleY)
        matrix.postTranslate(transform.translateX, transform.translateY)

        val letterboxed = (mode == MODE_FIT || mode == MODE_ROTATE_FIT)
        if (letterboxed && fillMode != FILL_BLACK) {
            // Fill the bars by tiling the image outward from its fitted
            // position. MIRROR gives the "reverse-repeat" pattern.
            val tile = if (fillMode == FILL_MIRROR) Shader.TileMode.MIRROR else Shader.TileMode.REPEAT
            val shader = BitmapShader(working, tile, tile)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            canvas.drawRect(0f, 0f, tw, th, paint)
        } else {
            canvas.drawBitmap(working, matrix, paint)
        }

        if (rotatedCopy) working.recycle()
        if (output != source) source.recycle()
        return output
    }

    fun deleteNextSource(filesDir: File) {
        FileTransactions.deleteRecursively(File(filesDir, NEXT_SOURCE_FILE))
    }

    /**
     * Stages the un-cropped original belonging to a playlist entry
     * (e.g. "wallpaper_3.jpg" -> playlist_originals/original_3.jpg) as the
     * next-wallpaper source. If no original exists, any stale staged source
     * is removed so the rotation falls back to the cropped image.
     */
    fun stageNextSource(
        filesDir: File,
        playlistFileName: String,
        originalsDirectoryName: String
    ): Boolean {
        return copyPlaylistOriginalTo(
            filesDir,
            playlistFileName,
            NEXT_SOURCE_FILE,
            originalsDirectoryName
        )
    }

    private fun copyPlaylistOriginalTo(
        filesDir: File,
        playlistFileName: String,
        destName: String,
        originalsDirectoryName: String
    ): Boolean {
        val destination = File(filesDir, destName)
        val original = findPlaylistOriginal(
            filesDir,
            playlistFileName,
            originalsDirectoryName
        )
        if (original == null) {
            FileTransactions.deleteRecursively(destination)
            return false
        }
        copyFileAtomically(original, destination)
        return true
    }

    @Throws(IOException::class)
    private fun copyFileAtomically(source: File, destination: File) {
        val directory = destination.parentFile
            ?: throw IOException("Destination has no parent directory")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Could not create ${directory.absolutePath}")
        }

        val temporary = File.createTempFile("${destination.name}.", ".tmp", directory)
        var moved = false
        try {
            source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            FileTransactions.moveReplacing(temporary, destination)
            moved = true
        } finally {
            if (!moved && temporary.exists()) {
                try {
                    FileTransactions.deleteRecursively(temporary)
                } catch (cleanupError: IOException) {
                    Log.w(TAG, "Could not remove temporary source ${temporary.absolutePath}", cleanupError)
                } catch (cleanupError: SecurityException) {
                    Log.w(TAG, "Could not access temporary source ${temporary.absolutePath}", cleanupError)
                }
            }
        }
    }

    private fun findPlaylistOriginal(
        filesDir: File,
        playlistFileName: String,
        originalsDirectoryName: String
    ): File? {
        // Playlist crops are named "wallpaper_<n>.jpg", originals "original_<n>.jpg"
        val index = playlistFileName
            .removePrefix("wallpaper_")
            .removeSuffix(".jpg")
            .toIntOrNull() ?: return null
        val file = File(File(filesDir, originalsDirectoryName), "original_$index.jpg")
        return if (file.exists()) file else null
    }

    /**
     * Decodes the queued next wallpaper for a playlist transition, choosing
     * the un-cropped source when the current fit mode wants it. Used by the
     * wallpaper services in place of a plain decode of next_wallpaper.jpg.
     */
    fun decodeNextForDisplay(context: Context): Bitmap? {
        val filesDir = context.filesDir
        // Scrolling needs the full-width source so the queued image has pan slack.
        if (isScrollEnabled(context) || needsSourceImage(getNextFitMode(context))) {
            val srcFile = File(filesDir, NEXT_SOURCE_FILE)
            if (srcFile.exists()) {
                val bitmap = decodeFileSampled(srcFile, ImageMemoryBudget.budgetBytes(context))
                if (bitmap != null) return bitmap
            }
        }
        val nextFile = File(filesDir, NEXT_WALLPAPER_FILE)
        if (!nextFile.exists()) return null
        return BitmapFactory.decodeFile(nextFile.absolutePath)
    }

    /**
     * Decodes a wallpaper source file at its native resolution, sampling only
     * when the decoded bitmap would exceed [budgetBytes], and applies EXIF
     * rotation. Playlist originals are raw copies of the picked images, so
     * they can be huge and carry EXIF orientation.
     *
     * This used to downsample every source to a 4096px longest side, which
     * threw away most of the pixels of an ordinary phone photo before the fit
     * pass had a chance to resample it to the surface. The fit pass is a
     * single high-quality reduction with FILTER_BITMAP; feeding it the full
     * image is what makes that reduction worth having, and feeding it a
     * pre-halved one is a reduction applied twice.
     */
    fun decodeFileSampled(
        file: File,
        budgetBytes: Long = ImageMemoryBudget.DEFAULT_BUDGET_BYTES
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageMemoryBudget.sampleSizeForBudget(
                    bounds.outWidth,
                    bounds.outHeight,
                    budgetBytes
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            applyExifRotation(file, raw)
        } catch (error: IOException) {
            Log.w(TAG, "Could not decode ${file.absolutePath}", error)
            null
        } catch (error: SecurityException) {
            Log.w(TAG, "Storage access was denied while decoding ${file.absolutePath}", error)
            null
        } catch (error: RuntimeException) {
            Log.w(TAG, "Invalid wallpaper image at ${file.absolutePath}", error)
            null
        }
    }

    private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotation == 0f) return bitmap

            val matrix = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (error: IOException) {
            Log.w(TAG, "Could not read orientation metadata from ${file.absolutePath}", error)
            bitmap
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not rotate ${file.absolutePath}", error)
            bitmap
        }
    }
}
