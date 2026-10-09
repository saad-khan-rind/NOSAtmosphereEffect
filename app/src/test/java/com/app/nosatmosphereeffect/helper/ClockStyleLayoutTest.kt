package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class ClockStyleLayoutTest {
    @Test
    fun `every look has a one-row and a stacked layout`() {
        ClockStyle.entries.forEach { style ->
            val row = style.withLayout(stacked = false)
            val stacked = style.withLayout(stacked = true)

            assertEquals(false, row.stacked)
            assertEquals(true, stacked.stacked)
            assertEquals(style.treatment, row.treatment)
            assertEquals(style.treatment, stacked.treatment)
        }
    }

    @Test
    fun `switching layout and back returns the same style`() {
        ClockStyle.entries.forEach { style ->
            assertEquals(style, style.withLayout(!style.stacked).withLayout(style.stacked))
        }
    }
}
