package com.app.nosatmosphereeffect.clock

import android.content.Context
import com.app.nosatmosphereeffect.image.WallpaperFitHelper

/**
 * The clock scrolling away with the home screen's pages (#210): on the first
 * page it is where the user put it, one page over it is a screen width to the
 * side, out of sight. Turned on in Fine tuning, where it turns the
 * wallpaper's own scrolling off: the photo holds still and the clock moves.
 *
 * A live wallpaper engine reports the launcher's page offsets ([onOffsets]);
 * the renderers move the clock by [shift] only as far as the screen has
 * unlocked, so on the lock screen it stays where it is and slides to the
 * page's place as the phone unlocks.
 */
object ClockPageScroll {
    private const val KEY = "clock_scrolls_with_pages"

    @Volatile private var enabled: Boolean? = null
    @Volatile private var pageShift = 0f

    fun isEnabled(context: Context): Boolean =
        enabled ?: prefs(context).getBoolean(KEY, false).also { enabled = it }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).apply()
        enabled = on
        if (!on) pageShift = 0f
    }

    /**
     * The launcher's page offset [xOffset] and its step between pages
     * [xOffsetStep], from a live (not preview) engine that is on screen
     * ([visible]) or not; hidden, the clock is moved nowhere, so nothing
     * drawn elsewhere (an in-app preview) inherits the page.
     */
    fun onOffsets(context: Context, xOffset: Float, xOffsetStep: Float, visible: Boolean) {
        pageShift = if (visible && isEnabled(context)) shiftFor(xOffset, xOffsetStep) else 0f
    }

    /**
     * How far to move the clock, in screen widths, with the screen
     * [unlockFraction] of the way from the lock screen (0) to the home screen (1).
     */
    fun shift(unlockFraction: Float): Float =
        if (unlockFraction.isFinite()) pageShift * unlockFraction.coerceIn(0f, 1f) else 0f

    /**
     * The clock's move at a page offset, in screen widths: none on the first
     * page, one width to the left on the second, and so on; none for a
     * launcher with a single page, or one that reports no steps.
     */
    internal fun shiftFor(xOffset: Float, xOffsetStep: Float): Float {
        if (!xOffset.isFinite() || !xOffsetStep.isFinite()) return 0f
        if (xOffsetStep <= 0f || xOffsetStep >= 1f) return 0f
        return -(xOffset.coerceIn(0f, 1f) / xOffsetStep)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(WallpaperFitHelper.PREFS_NAME, Context.MODE_PRIVATE)
}
