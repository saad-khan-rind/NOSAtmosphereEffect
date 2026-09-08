package com.app.nosatmosphereeffect.helper

import android.content.Context

/**
 * Remembers, across process restarts, the wallpaper surface sizes this device
 * uses. Stored in the display preferences file alongside the fit modes, which
 * survives the wallpaper-apply flows that wipe the other preference files.
 *
 * Persisting matters: the very first fold after a reboot would otherwise have
 * nothing prepared. Once both postures have been seen even once, every
 * subsequent transition — including the first one after a cold start — can be
 * prepared ahead of time.
 */
object FoldPostureSizes {

    private const val KEY_KNOWN_SIZES = "known_surface_sizes"

    private fun prefs(context: Context) =
        context.getSharedPreferences(WallpaperFitHelper.PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Records [current] as the size in use and returns the other known size,
     * or null when this device has only ever reported one.
     */
    fun record(context: Context, current: SurfaceSize): SurfaceSize? {
        if (!current.isUsable()) return null
        val store = prefs(context)
        val known = PostureSizePolicy.decode(store.getString(KEY_KNOWN_SIZES, null))
        val updated = PostureSizePolicy.remember(known, current)
        if (updated != known) {
            store.edit().putString(KEY_KNOWN_SIZES, PostureSizePolicy.encode(updated)).apply()
        }
        return PostureSizePolicy.alternate(updated, current)
    }
}
