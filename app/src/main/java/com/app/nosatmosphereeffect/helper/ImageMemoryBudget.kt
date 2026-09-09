package com.app.nosatmosphereeffect.helper

import android.app.ActivityManager
import android.content.Context

/**
 * Decides how much of an image this device can hold decoded, so the original
 * wallpaper is kept at its native resolution instead of being downsampled to
 * a fixed dimension.
 *
 * ## Why this replaced a flat 4096 cap
 *
 * Every decode path used `ImageSampling.sampleSize(w, h, 4096)`, which
 * halves an image until its longest side fits 4096px. That is a lossy,
 * irreversible reduction applied to the *original* — the file the FIT,
 * ROTATE and scroll modes render from — and it fired on ordinary phone
 * photos: a 50MP 8160x6120 shot came back at 4080x3060, a quarter of the
 * pixels, before the user had even cropped it.
 *
 * The cap was there for memory safety, which is a real concern, but a
 * dimension is the wrong unit for it. A 6000x1000 panorama has fewer pixels
 * than a 4000x3000 photo yet trips a longest-side cap first, and the same
 * cap is either wasteful or dangerous depending on how much RAM the device
 * actually has. Budgeting in bytes and only sampling when the budget is
 * genuinely exceeded means most images are never touched at all.
 *
 * ## Why largeMemoryClass rather than the Java heap
 *
 * Bitmap pixel storage moved to the native heap in Android 8, so
 * `Runtime.maxMemory()` no longer describes what a bitmap costs.
 * `ActivityManager.largeMemoryClass` remains the best available proxy for
 * "how much this device is comfortable with" — it scales with total RAM and
 * is what the platform itself uses to size its own caches.
 */
object ImageMemoryBudget {

    /** ARGB_8888. */
    private const val BYTES_PER_PIXEL = 4L

    /**
     * Share of the device's large-heap allowance a single decoded original
     * may occupy. A third leaves room for the crop output, the blurred copy
     * and the segmentation input to coexist, which is the worst case during
     * an apply.
     */
    private const val BUDGET_FRACTION = 3L

    /**
     * Floor for devices that report an implausibly small allowance. 96MB is
     * 24 megapixels — comfortably above any image the old 4096 cap would
     * have let through, so this change can never decode *less* than before.
     */
    private const val MIN_BUDGET_BYTES = 96L * 1024L * 1024L

    /** Used when no Context is available (background file paths). */
    const val DEFAULT_BUDGET_BYTES = 160L * 1024L * 1024L

    fun budgetBytes(context: Context): Long {
        val manager = try {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        } catch (_: RuntimeException) {
            null
        }
        val megabytes = manager?.largeMemoryClass?.takeIf { it > 0 } ?: return DEFAULT_BUDGET_BYTES
        val budget = megabytes.toLong() * 1024L * 1024L / BUDGET_FRACTION
        return budget.coerceAtLeast(MIN_BUDGET_BYTES)
    }

    /**
     * The `inSampleSize` needed to bring a [sourceWidth] x [sourceHeight]
     * image inside [budgetBytes] — **1 whenever it already fits**, which is
     * the point: no downscale unless the device genuinely cannot hold the
     * image.
     *
     * BitmapFactory only honours powers of two, so the result is always one.
     */
    fun sampleSizeForBudget(
        sourceWidth: Int,
        sourceHeight: Int,
        budgetBytes: Long
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "Source dimensions must be positive"
        }
        if (budgetBytes <= 0L) return 1
        var sampleSize = 1
        while (sampleSize <= Int.MAX_VALUE / 2) {
            val width = sourceWidth / sampleSize
            val height = sourceHeight / sampleSize
            val bytes = width.toLong() * height.toLong() * BYTES_PER_PIXEL
            if (bytes <= budgetBytes) return sampleSize
            sampleSize *= 2
        }
        return sampleSize
    }

    /** Convenience for callers that already hold a Context. */
    fun sampleSizeFor(context: Context, sourceWidth: Int, sourceHeight: Int): Int =
        sampleSizeForBudget(sourceWidth, sourceHeight, budgetBytes(context))
}
