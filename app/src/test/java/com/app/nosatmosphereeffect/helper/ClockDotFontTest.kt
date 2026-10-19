package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockDotFontTest {

    @Test
    fun `every glyph is a clean grid`() {
        val all = ('0'..'9') + ('A'..'Z') + " ,.:·-/'".toList()
        all.forEach { character ->
            val glyph = ClockDotFont.glyphs(character.toString())?.single()
            assertNotNull("No dots for '$character'", glyph)
            glyph!!
            assertTrue("'$character' rows", glyph.size == ClockDotFont.ROWS || glyph.size == ClockDotFont.ROWS + 1)
            assertTrue("'$character' widths", glyph.all { it.length == glyph[0].length })
            assertTrue("'$character' cells", glyph.all { row -> row.all { it == '#' || it == '.' } })
        }
    }

    @Test
    fun `dates are covered in capitals and other scripts fall back`() {
        assertEquals(11, ClockDotFont.glyphs("MON, 19 OCT")?.size)
        assertNull(ClockDotFont.glyphs("Mon"))
        assertNull(ClockDotFont.glyphs("ПН, 19 ОКТ"))
    }
}
