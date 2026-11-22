package com.app.nosatmosphereeffect.clock

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockPageScrollTest {

    @Test
    fun `the clock moves a screen width per page, to the left`() {
        // Four pages: steps of a third.
        val step = 1f / 3f
        assertEquals(0f, ClockPageScroll.shiftFor(0f, step), 1e-6f)
        assertEquals(-1f, ClockPageScroll.shiftFor(step, step), 1e-6f)
        assertEquals(-3f, ClockPageScroll.shiftFor(1f, step), 1e-6f)
        // Half way between the first two pages, half way out.
        assertEquals(-0.5f, ClockPageScroll.shiftFor(step / 2f, step), 1e-6f)
    }

    @Test
    fun `a launcher with one page or no steps moves nothing`() {
        assertEquals(0f, ClockPageScroll.shiftFor(0.5f, 0f), 0f)
        assertEquals(0f, ClockPageScroll.shiftFor(0.5f, 1f), 0f)
        assertEquals(0f, ClockPageScroll.shiftFor(Float.NaN, 0.5f), 0f)
    }

    @Test
    fun `on the lock screen the clock stays where it is`() {
        assertEquals(0f, ClockPageScroll.shift(0f), 0f)
        assertEquals(0f, ClockPageScroll.shift(Float.NaN), 0f)
    }

    @Test
    fun `a swipe to the left turns to the next page, to the right back`() {
        assertEquals(1, ClockPageScroll.pageDelta(dx = -400f, dy = 20f, durationMs = 400, width = 1080))
        assertEquals(-1, ClockPageScroll.pageDelta(dx = 400f, dy = 20f, durationMs = 400, width = 1080))
    }

    @Test
    fun `a short slow drag or a mostly vertical swipe turns no page`() {
        assertEquals(0, ClockPageScroll.pageDelta(dx = -150f, dy = 10f, durationMs = 600, width = 1080))
        assertEquals(0, ClockPageScroll.pageDelta(dx = -400f, dy = 700f, durationMs = 300, width = 1080))
    }

    @Test
    fun `a quick flick turns a page even when short`() {
        assertEquals(1, ClockPageScroll.pageDelta(dx = -120f, dy = 5f, durationMs = 120, width = 1080))
    }

    @Test
    fun `the clock follows the finger but not past the first or last page`() {
        // Dragging right on the first page (position below 0) moves nothing: the launcher doesn't either.
        assertEquals(0f, ClockPageScroll.shiftAt(-0.3f, pages = 3, loop = false), 1e-6f)
        assertEquals(-0.3f, ClockPageScroll.shiftAt(0.3f, pages = 3, loop = false), 1e-6f)
        assertEquals(-2f, ClockPageScroll.shiftAt(2.5f, pages = 3, loop = false), 1e-6f)
    }

    @Test
    fun `when the pages loop, the clock comes back in from the right past the last page`() {
        // Three pages: half way from the last page round to the first.
        assertEquals(0.5f, ClockPageScroll.shiftAt(2.5f, pages = 3, loop = true), 1e-6f)
        // Arrived back on the first page, a full turn round.
        assertEquals(0f, ClockPageScroll.shiftAt(3f, pages = 3, loop = true), 1e-6f)
        // Dragging right from the first page shows the last coming in: the clock leaves to the right.
        assertEquals(0.4f, ClockPageScroll.shiftAt(-0.4f, pages = 3, loop = true), 1e-6f)
        // On the second page the clock is a width to the left, as without looping.
        assertEquals(-1f, ClockPageScroll.shiftAt(1f, pages = 3, loop = true), 1e-6f)
    }

    @Test
    fun `a sideways drag is told from a vertical one`() {
        assertEquals(true, ClockPageScroll.isSideways(dx = 40f, dy = 10f, slop = 20f))
        assertEquals(false, ClockPageScroll.isSideways(dx = 40f, dy = 40f, slop = 20f))
        assertEquals(false, ClockPageScroll.isSideways(dx = 10f, dy = 0f, slop = 20f))
    }

    @Test
    fun `one page never moves the clock, looping or not`() {
        assertEquals(0f, ClockPageScroll.shiftAt(0f, pages = 1, loop = false), 1e-6f)
        assertEquals(0f, ClockPageScroll.shiftAt(1f, pages = 1, loop = true), 1e-6f)
    }
}
