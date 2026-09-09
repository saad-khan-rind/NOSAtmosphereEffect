package com.app.nosatmosphereeffect.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.app.nosatmosphereeffect.helper.ImageMemoryBudget
import com.app.nosatmosphereeffect.helper.ImageSampling
import com.app.nosatmosphereeffect.storage.UriFiles
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

internal object BitmapDecoder {
    private const val TAG = "BitmapDecoder"

    /**
     * Decodes at the image's native resolution, sampling only if the decoded
     * bitmap would not fit this device's memory budget.
     *
     * Use this for anything that becomes the wallpaper or its stored
     * original. [decodeUri] with an explicit maxDimension is for thumbnails
     * and list previews, where a bounded size is the actual requirement.
     */
    @Throws(IOException::class, SecurityException::class)
    fun decodeUriFullSize(context: Context, uri: Uri): Bitmap {
        val budget = ImageMemoryBudget.budgetBytes(context)
        return decodeUri(context, uri) { width, height ->
            ImageMemoryBudget.sampleSizeForBudget(width, height, budget)
        }
    }

    @Throws(IOException::class, SecurityException::class)
    fun decodeUri(context: Context, uri: Uri, maxDimension: Int = 4096): Bitmap {
        return decodeUri(context, uri) { width, height ->
            ImageSampling.sampleSize(width, height, maxDimension)
        }
    }

    @Throws(IOException::class, SecurityException::class)
    fun decodeUri(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        return decodeUri(context, uri) { width, height ->
            ImageSampling.sampleSizeForTarget(
                width,
                height,
                targetWidth,
                targetHeight
            )
        }
    }

    private fun decodeUri(
        context: Context,
        uri: Uri,
        sampleSize: (width: Int, height: Int) -> Int
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        UriFiles.open(context, uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The selected file is not a supported image")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = UriFiles.open(context, uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IOException("The selected image could not be decoded")

        return applyExifRotation(context, uri, decoded)
    }

    @Throws(IOException::class)
    fun decodePreview(file: File, maxDimension: Int = 2000): Bitmap {
        if (!file.isFile) throw FileNotFoundException(file.absolutePath)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The wallpaper preview is not a supported image")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.sampleSize(
                bounds.outWidth,
                bounds.outHeight,
                maxDimension
            )
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("The wallpaper preview could not be decoded")
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = try {
            UriFiles.open(context, uri).use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (error: IOException) {
            Log.w(TAG, "Could not read image orientation; using decoded orientation", error)
            return bitmap
        } catch (error: SecurityException) {
            Log.w(TAG, "Image orientation metadata is no longer accessible", error)
            return bitmap
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Image orientation metadata is invalid", error)
            return bitmap
        }

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }

        val rotated = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(degrees) },
            true
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
