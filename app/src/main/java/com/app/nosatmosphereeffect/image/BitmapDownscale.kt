package com.app.nosatmosphereeffect.image

import android.graphics.Bitmap
import com.app.nosatmosphereeffect.subject.SubjectMaskExtractor

/**
 * Shared downscale helper for the subject-segmentation model inputs (both
 * the Play/ML Kit and the bundled-TFLite extractors).
 *
 * The bitmap handed to a [SubjectMaskExtractor] can arrive at very different
 * resolutions depending on the caller: the in-app effect preview already
 * decodes the wallpaper file through a box-filtered `inSampleSize` (see
 * [ImageSampling]) before it gets here, while the live wallpaper renderer
 * hands over the bitmap at full screen resolution. Reducing both down to the
 * model's input size with a single `Bitmap.createScaledBitmap` bilinear pass
 * means the full-resolution path goes through a much steeper single
 * reduction than the pre-shrunk preview path, which aliases and starves the
 * segmentation model of clean edges — the same photo can then segment
 * cleanly in the preview but come back as a washed-out/over-broad mask for
 * the actual wallpaper.
 *
 * Downscaling in successive halving steps keeps every individual step's
 * reduction factor small (mipmap-style), so segmentation quality no longer
 * depends on how much the caller had already shrunk the source image.
 */
internal object BitmapDownscale {
    fun toStagedSize(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        var current = source
        var owns = false
        while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
            val halved = Bitmap.createScaledBitmap(
                current,
                current.width / 2,
                current.height / 2,
                true
            )
            if (owns) current.recycle()
            current = halved
            owns = true
        }
        val result = Bitmap.createScaledBitmap(current, targetWidth, targetHeight, true)
        if (owns) current.recycle()
        return result
    }
}
