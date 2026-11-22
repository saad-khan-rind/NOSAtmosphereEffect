package com.app.nosatmosphereeffect.clock

import android.content.Context
import android.os.SystemClock
import com.app.nosatmosphereeffect.image.WallpaperFitHelper
import kotlin.math.abs

/**
 * The clock scrolling away with the home screen's pages (#210): on the first
 * page it is where the user put it, one page over it is a screen width to the
 * side, out of sight. Turned on in Fine tuning, where it turns the
 * wallpaper's own scrolling off: the photo holds still and the clock moves.
 *
 * The page comes from one of two places:
 *  - the launcher's page offsets ([onOffsets]), exact, but only from
 *    launchers that report them (Samsung's doesn't);
 *  - the user's own swipes on the home screen ([onSwipeDrag], [onSwipeEnd]),
 *    when [followsSwipes] is on: counted here, from the first page up to
 *    [pages], and back to the first page whenever the screen turns off.
 *
 * The renderers move the clock by [shift] only as far as the screen has
 * unlocked, so on the lock screen it stays where it is.
 */
object ClockPageScroll {
    private const val KEY = "clock_scrolls_with_pages"
    private const val KEY_FOLLOW_SWIPES = "clock_scroll_follows_swipes"
    private const val KEY_PAGES = "clock_scroll_pages"
    const val DEFAULT_PAGES = 2
    const val MAX_PAGES = 8
    /** How long the clock takes to settle on a page after a swipe, like a launcher's snap. */
    const val SETTLE_MS = 280L

    @Volatile private var enabled: Boolean? = null
    @Volatile private var swipes: Boolean? = null
    @Volatile private var pageCount: Int? = null

    /** From the launcher's offsets. */
    @Volatile private var offsetShift = 0f

    /** From swipes: the page counted, the finger's drag now (screen widths), and the settle after it. */
    @Volatile private var page = 0
    @Volatile private var drag = 0f
    @Volatile private var settleFrom = 0f
    @Volatile private var settleStart = 0L

    fun isEnabled(context: Context): Boolean =
        enabled ?: prefs(context).getBoolean(KEY, false).also { enabled = it }

    fun followsSwipes(context: Context): Boolean =
        swipes ?: prefs(context).getBoolean(KEY_FOLLOW_SWIPES, false).also { swipes = it }

    fun pages(context: Context): Int =
        pageCount ?: prefs(context).getInt(KEY_PAGES, DEFAULT_PAGES).coerceIn(2, MAX_PAGES).also { pageCount = it }

    fun save(context: Context, on: Boolean, followSwipes: Boolean, pages: Int) {
        val safePages = pages.coerceIn(2, MAX_PAGES)
        prefs(context).edit()
            .putBoolean(KEY, on)
            .putBoolean(KEY_FOLLOW_SWIPES, followSwipes)
            .putInt(KEY_PAGES, safePages)
            .apply()
        enabled = on
        swipes = followSwipes
        pageCount = safePages
        offsetShift = 0f
        resetToFirstPage()
    }

    /**
     * The launcher's page offset [xOffset] and its step between pages
     * [xOffsetStep], from a live (not preview) engine that is on screen
     * ([visible]) or not; hidden, the clock is moved nowhere, so nothing
     * drawn elsewhere (an in-app preview) inherits the page.
     */
    fun onOffsets(context: Context, xOffset: Float, xOffsetStep: Float, visible: Boolean) {
        offsetShift = if (visible && isEnabled(context) && !followsSwipes(context)) {
            shiftFor(xOffset, xOffsetStep)
        } else {
            0f
        }
    }

    /** The finger has dragged [screenWidths] sideways on the home screen, positive to the right. */
    fun onSwipeDrag(screenWidths: Float) {
        settleStart = 0L
        drag = screenWidths
    }

    /** The swipe ended, moving [pageDelta] pages (1 to the next, -1 back, 0 none), within [pageCount]. */
    fun onSwipeEnd(pageDelta: Int, pageCount: Int) {
        val from = swipeShift(page, drag, pageCount)
        page = (page + pageDelta).coerceIn(0, pageCount - 1)
        drag = 0f
        settleFrom = from
        settleStart = SystemClock.uptimeMillis()
    }

    /** The screen turned off: the next home screen is counted from the first page. */
    fun resetToFirstPage() {
        page = 0
        drag = 0f
        settleStart = 0L
    }

    /** Whether the clock is still settling onto a page, so the wallpaper keeps drawing frames. */
    fun settling(): Boolean = settleStart != 0L && SystemClock.uptimeMillis() - settleStart < SETTLE_MS

    /**
     * How far to move the clock, in screen widths, with the screen
     * [unlockFraction] of the way from the lock screen (0) to the home screen (1).
     */
    fun shift(unlockFraction: Float): Float {
        if (!unlockFraction.isFinite()) return 0f
        val now = when {
            enabled != true -> 0f
            swipes == true -> settledSwipeShift()
            else -> offsetShift
        }
        return now * unlockFraction.coerceIn(0f, 1f)
    }

    private fun settledSwipeShift(): Float {
        val pages = pageCount ?: DEFAULT_PAGES
        val target = swipeShift(page, drag, pages)
        val start = settleStart
        if (start == 0L) return target
        val t = ((SystemClock.uptimeMillis() - start).toFloat() / SETTLE_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return settleFrom + (target - settleFrom) * eased
    }

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

    /**
     * The clock's move on [page] with the finger dragged [drag] screen widths
     * (positive to the right): it follows the finger, but never past the
     * first page or the last of [pages], where the launcher doesn't move either.
     */
    internal fun swipeShift(page: Int, drag: Float, pages: Int): Float =
        (-page + drag).coerceIn(-(pages - 1).toFloat(), 0f)

    /**
     * What a swipe that moved [dx] by [dy] pixels in [durationMs] on a screen
     * [width] pixels wide does to the page: 1 to the next (a swipe to the
     * left), -1 back, 0 none. A page turns when the swipe went a quarter of the
     * screen, or was a quick flick; mostly vertical ones (the app drawer, the
     * notification shade) never do.
     */
    internal fun pageDelta(dx: Float, dy: Float, durationMs: Long, width: Int): Int {
        if (width <= 0 || !dx.isFinite() || !dy.isFinite()) return 0
        if (abs(dx) < abs(dy) * 1.5f) return 0
        val far = abs(dx) > width * 0.25f
        val quick = durationMs in 1..250 && abs(dx) > width * 0.08f
        if (!far && !quick) return 0
        return if (dx < 0f) 1 else -1
    }

    /** Whether a drag of [dx] by [dy] pixels is a sideways swipe yet, past [slop] pixels. */
    internal fun isSideways(dx: Float, dy: Float, slop: Float): Boolean =
        abs(dx) > slop && abs(dx) > abs(dy) * 1.5f

    private fun prefs(context: Context) =
        context.getSharedPreferences(WallpaperFitHelper.PREFS_NAME, Context.MODE_PRIVATE)
}
