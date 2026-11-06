package com.app.nosatmosphereeffect.subject

import android.graphics.Bitmap

/**
 * Remembers recent segmentation results so the same image is not segmented
 * twice.
 *
 * Every render host re-uploads its wallpaper — and asks for a fresh mask — on
 * each surface (re)creation, each GL context loss, and in every engine that
 * shows it (the live wallpaper and the picker preview are separate engines).
 * The pixels are identical each time, so without this the device ran a full
 * inference pass, hundreds of milliseconds of NPU/CPU, on every screen wake on
 * skins that recreate the wallpaper surface, and again for every preview.
 *
 * Keyed on a fingerprint of the input bitmap rather than on a file or a
 * generation: hosts fit the image to their own surface size, so the same photo
 * reaches segmentation at different sizes, and only the pixels say whether two
 * requests are really the same. Process-wide and small — a handful of masks,
 * each at most the extractor's working size.
 */
internal object SubjectMaskCache {
    private const val CAPACITY = 3
    private const val SAMPLE_GRID = 48

    private val lock = Any()
    private val entries = LinkedHashMap<Long, Bitmap>(CAPACITY, 0.75f, true)

    /**
     * A cheap fingerprint: dimensions plus a sparse grid of pixels. Stable for
     * a deterministic decode of the same file at the same size, and ~2k pixel
     * reads regardless of image size.
     */
    fun fingerprint(bitmap: Bitmap): Long? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        return try {
            var hash = 1469598103934665603L
            fun mix(value: Long) {
                hash = (hash xor value) * 1099511628211L
            }
            mix(bitmap.width.toLong())
            mix(bitmap.height.toLong())
            mix(bitmap.config?.ordinal?.toLong() ?: -1L)
            for (row in 0 until SAMPLE_GRID) {
                val y = ((row + 0.5f) * bitmap.height / SAMPLE_GRID).toInt()
                    .coerceIn(0, bitmap.height - 1)
                for (column in 0 until SAMPLE_GRID) {
                    val x = ((column + 0.5f) * bitmap.width / SAMPLE_GRID).toInt()
                        .coerceIn(0, bitmap.width - 1)
                    mix(bitmap.getPixel(x, y).toLong())
                }
            }
            hash
        } catch (_: RuntimeException) {
            null
        }
    }

    /** A private copy of the cached mask, or null. The caller owns the copy. */
    fun get(key: Long): Bitmap? = synchronized(lock) {
        val cached = entries[key] ?: return null
        if (cached.isRecycled) {
            entries.remove(key)
            return null
        }
        try {
            cached.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /** Stores a private copy of [mask]; the caller keeps ownership of [mask]. */
    fun put(key: Long, mask: Bitmap) {
        if (mask.isRecycled) return
        val copy = try {
            mask.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: OutOfMemoryError) {
            return
        } ?: return
        val evicted = synchronized(lock) {
            entries.put(key, copy)?.let { listOf(it) }.orEmpty() + trimLocked()
        }
        evicted.forEach { if (!it.isRecycled) it.recycle() }
    }

    fun clear() {
        val evicted = synchronized(lock) {
            entries.values.toList().also { entries.clear() }
        }
        evicted.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun trimLocked(): List<Bitmap> {
        val evicted = mutableListOf<Bitmap>()
        val iterator = entries.entries.iterator()
        while (entries.size > CAPACITY && iterator.hasNext()) {
            evicted += iterator.next().value
            iterator.remove()
        }
        return evicted
    }
}
