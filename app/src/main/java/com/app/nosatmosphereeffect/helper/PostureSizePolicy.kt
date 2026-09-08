package com.app.nosatmosphereeffect.helper

/** A wallpaper surface size, in pixels. */
data class SurfaceSize(val width: Int, val height: Int) {
    fun isUsable(): Boolean = width > 0 && height > 0

    override fun toString(): String = "${width}x$height"
}

/**
 * Bookkeeping for the surface sizes a device renders the wallpaper at.
 *
 * A phone only ever reports one. A foldable alternates between two (cover and
 * inner screen), and knowing both is what lets the wallpaper prepare the image
 * for the posture it is about to be switched into. Kept as pure functions so
 * the behaviour is testable without a device.
 */
object PostureSizePolicy {

    /** Only the current posture and the one before it are worth remembering. */
    const val MAX_REMEMBERED = 2

    /** Moves [current] to the front of [known], keeping the list de-duplicated and bounded. */
    fun remember(known: List<SurfaceSize>, current: SurfaceSize): List<SurfaceSize> {
        if (!current.isUsable()) return known
        val ordered = mutableListOf(current)
        for (size in known) {
            if (size == current || !size.isUsable()) continue
            ordered.add(size)
            if (ordered.size == MAX_REMEMBERED) break
        }
        return ordered
    }

    /**
     * The most recently used size that is not [current] — the posture the
     * device is most likely to be folded or unfolded into next, or null when
     * only one size has ever been seen.
     */
    fun alternate(known: List<SurfaceSize>, current: SurfaceSize): SurfaceSize? =
        known.firstOrNull { it != current && it.isUsable() }

    /** Serializes to "1080x2400,1812x2176". */
    fun encode(sizes: List<SurfaceSize>): String =
        sizes.filter { it.isUsable() }.joinToString(",") { it.toString() }

    /** Inverse of [encode]; silently drops anything malformed. */
    fun decode(stored: String?): List<SurfaceSize> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(',').mapNotNull { entry ->
            val parts = entry.trim().split('x')
            if (parts.size != 2) return@mapNotNull null
            val width = parts[0].toIntOrNull() ?: return@mapNotNull null
            val height = parts[1].toIntOrNull() ?: return@mapNotNull null
            SurfaceSize(width, height).takeIf { it.isUsable() }
        }.take(MAX_REMEMBERED)
    }
}
