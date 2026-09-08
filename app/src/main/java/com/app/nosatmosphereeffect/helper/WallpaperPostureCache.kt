package com.app.nosatmosphereeffect.helper

import android.graphics.Bitmap

/**
 * Holds wallpaper images prepared ahead of time for the surface sizes this
 * device renders at.
 *
 * Foldables resize the wallpaper surface when the posture changes. Until the
 * renderer submits a frame at the new size, SurfaceFlinger keeps scaling the
 * last buffer, which is what users see as the image being squeezed and then
 * snapping back. That squeeze lasts exactly as long as it takes the renderer to
 * produce the first correct frame, and the expensive part of it is re-fitting
 * the image: a JPEG decode of the stored source plus a full-surface canvas
 * draw, several hundred milliseconds on a large photo, all on the render thread
 * while the wallpaper engine thread blocks behind it.
 *
 * Preparing the other posture's image in advance removes that work from the
 * resize path: the renderer only has to upload a texture, so the correct frame
 * lands within a frame or two of the surface change.
 *
 * At most [MAX_ENTRIES] images are held, one per surface size. In the steady
 * state only one is: the posture the device is *not* currently in. The second
 * slot exists so that preparing the previous posture can never evict an image
 * a render thread is still on its way to claim.
 *
 * Ownership transfers on [take]: the caller recycles the bitmap exactly as it
 * would one it had just built itself.
 */
internal object WallpaperPostureCache {

    /**
     * Everything that decides what [WallpaperFitHelper.loadForRender] would
     * produce. [stamp] covers the wallpaper files themselves, so a new
     * wallpaper (or a playlist rotation) stops matching instead of being served
     * a stale image.
     */
    data class Key(
        val width: Int,
        val height: Int,
        val mode: String,
        val fill: String,
        val scroll: Boolean,
        val stamp: String
    )

    /** One per posture. */
    private const val MAX_ENTRIES = 2

    /** Refuse to hold anything absurd; a prepared image is never worth an OOM. */
    private const val MAX_BYTES = 96 * 1024 * 1024

    private class Entry(val key: Key, val bitmap: Bitmap, val windowX: Float)

    /** Keyed by surface size, so the two postures never displace each other. */
    private val entries = LinkedHashMap<Pair<Int, Int>, Entry>()

    /** True when [key] is already prepared, so preparing it again is wasted work. */
    @Synchronized
    fun holds(key: Key): Boolean {
        val entry = entries[key.slot()] ?: return false
        return entry.key == key && !entry.bitmap.isRecycled
    }

    /**
     * Removes and returns the prepared image for [key], transferring ownership
     * of the bitmap to the caller, or null when nothing matching is held.
     *
     * A same-size entry that no longer matches (the wallpaper or the fit
     * settings changed underneath it) is dropped here rather than left to
     * occupy memory until the next posture change.
     */
    @Synchronized
    fun take(key: Key): WallpaperFitHelper.RenderImage? {
        val entry = entries.remove(key.slot()) ?: return null
        if (entry.key != key || entry.bitmap.isRecycled) {
            entry.bitmap.recycleSafely()
            return null
        }
        return WallpaperFitHelper.RenderImage(entry.bitmap, entry.windowX)
    }

    /** Takes ownership of [image] and parks it under [key]. */
    @Synchronized
    fun store(key: Key, image: WallpaperFitHelper.RenderImage) {
        if (image.bitmap.isRecycled) return
        if (image.bitmap.byteCount > MAX_BYTES) {
            image.bitmap.recycleSafely()
            return
        }
        val slot = key.slot()
        entries.remove(slot)?.bitmap?.recycleSafely()
        while (entries.size >= MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)?.bitmap?.recycleSafely()
        }
        entries[slot] = Entry(key, image.bitmap, image.windowX)
    }

    @Synchronized
    fun clear() {
        for (entry in entries.values) entry.bitmap.recycleSafely()
        entries.clear()
    }

    private fun Key.slot(): Pair<Int, Int> = width to height

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }
}
