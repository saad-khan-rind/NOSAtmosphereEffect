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
}
