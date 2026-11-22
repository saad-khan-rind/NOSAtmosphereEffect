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
 *    when [followsSwipes] is on: counted here over [pages] pages, round
 *    again from the last to the first if the launcher's pages [loop], and
 *    back to the first page whenever the screen turns off.
 *
 * The renderers move the clock by [shift] only as far as the screen has
 * unlocked, so on the lock screen it stays where it is.
 */
object ClockPageScroll {
    private const val KEY = "clock_scrolls_with_pages"
    private const val KEY_FOLLOW_SWIPES = "clock_scroll_follows_swipes"
    private const val KEY_PAGES = "clock_scroll_pages"
    private const val KEY_LOOP = "clock_scroll_pages_loop"
    const val DEFAULT_PAGES = 2
    /** One page has nowhere to scroll to. */
    const val MIN_PAGES = 2
    /** Only a guard against a typing slip: any real home screen has fewer. */
    const val MAX_PAGES = 99
    /** How long the clock takes to settle on a page after a swipe, like a launcher's snap. */
    const val SETTLE_MS = 280L

    @Volatile private var enabled: Boolean? = null
    @Volatile private var swipes: Boolean? = null
    @Volatile private var pageCount: Int? = null
    @Volatile private var looping: Boolean? = null

    /** From the launcher's offsets. */
    @Volatile private var offsetShift = 0f

    /**
     * From swipes: the page counted (past the last or before the first while
     * a looping swipe settles), the finger's drag now (screen widths, positive
     * to the right), and the settle after it, from the page position [settleFrom].
     */
    @Volatile private var page = 0
    @Volatile private var drag = 0f
    @Volatile private var settleFrom = 0f
    @Volatile private var settleStart = 0L

    fun isEnabled(context: Context): Boolean =
        enabled ?: prefs(context).getBoolean(KEY, false).also { enabled = it }

    fun followsSwipes(context: Context): Boolean =
        swipes ?: prefs(context).getBoolean(KEY_FOLLOW_SWIPES, false).also { swipes = it }

    fun pages(context: Context): Int =
        pageCount ?: prefs(context).getInt(KEY_PAGES, DEFAULT_PAGES).coerceIn(MIN_PAGES, MAX_PAGES).also { pageCount = it }

    /** Whether the launcher's pages go round: past the last is the first again. */
    fun loops(context: Context): Boolean =
        looping ?: prefs(context).getBoolean(KEY_LOOP, false).also { looping = it }

    fun save(context: Context, on: Boolean, followSwipes: Boolean, pages: Int, loop: Boolean) {
        val safePages = pages.coerceIn(MIN_PAGES, MAX_PAGES)
        prefs(context).edit()
            .putBoolean(KEY, on)
            .putBoolean(KEY_FOLLOW_SWIPES, followSwipes)
            .putInt(KEY_PAGES, safePages)
            .putBoolean(KEY_LOOP, loop)
            .apply()
        enabled = on
        swipes = followSwipes
        pageCount = safePages
        looping = loop
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

    /**
     * The swipe ended, moving [pageDelta] pages (1 to the next, -1 back, 0
     * none) over [pageCount] pages, round again at the ends if they [loop].
     */
    fun onSwipeEnd(pageDelta: Int, pageCount: Int, loop: Boolean) {
        val from = position(pageCount, loop)
        // The page settled on last time, back within the pages before moving on.
        val start = if (loop) Math.floorMod(page, pageCount) else page.coerceIn(0, pageCount - 1)
        val unwrapped = from - page + start
        page = if (loop) start + pageDelta else (start + pageDelta).coerceIn(0, pageCount - 1)
        drag = 0f
        settleFrom = unwrapped
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
        val loop = looping == true
        return shiftAt(position(pages, loop), pages, loop)
    }

    /**
     * Where the screen is among the pages now, in pages: the page with the
     * finger's drag, or part way through the settle after a swipe.
     */
    private fun position(pages: Int, loop: Boolean): Float {
        val start = settleStart
        if (start == 0L) {
            val dragged = page - drag
            return if (loop) dragged else dragged.coerceIn(0f, (pages - 1).toFloat())
        }
        val t = ((SystemClock.uptimeMillis() - start).toFloat() / SETTLE_MS).coerceIn(0f, 1f)
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        return settleFrom + (page - settleFrom) * eased
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
     * The clock's move, in screen widths, with the screen at [position] pages
     * among [pages]: the clock is on the first page, so it is a width to the
     * left per page along. If the pages [loop], the first page is also just
     * past the last, so from there the clock comes back in from the right.
     */
    internal fun shiftAt(position: Float, pages: Int, loop: Boolean): Float {
        if (!position.isFinite() || pages < 1) return 0f
        if (!loop) return -position.coerceIn(0f, (pages - 1).toFloat())
        val along = ((position % pages) + pages) % pages
        return if (along > pages / 2f) pages - along else -along
    }

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
