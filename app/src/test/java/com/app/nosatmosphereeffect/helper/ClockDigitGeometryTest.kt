package com.app.nosatmosphereeffect.helper

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockDigitGeometryTest {

    @Test
    fun `every drawn design inks every digit inside its own advance`() {
        drawnDesigns().forEach { (name, design) ->
            for (character in "0123456789:") {
                val ink = ClockDigitGeometry.glyph(design, character, showColon = true)
                assertNotNull("$name '$character'", ink)
                assertTrue("$name '$character' has no ink", ink!!.strokes.isNotEmpty() || ink.dots.isNotEmpty())
                val (left, top, right, bottom) = ClockDigitGeometry.bounds(ink).toList()
                val advance = ClockDigitGeometry.advance(design, character, showColon = true)
                assertTrue("$name '$character' spills left: $left", left >= -0.02f)
                assertTrue("$name '$character' spills right: $right > $advance", right <= advance + 0.06f)
                assertTrue("$name '$character' rises past its height", top >= -design.height - 0.02f)
                assertTrue("$name '$character' sinks below the baseline", bottom <= 0.02f)
            }
        }
    }

    @Test
    fun `a hidden colon draws nothing and takes almost no room`() {
        drawnDesigns().forEach { (name, design) ->
            val ink = ClockDigitGeometry.glyph(design, ':', showColon = false)!!
            assertTrue(name, ink.strokes.isEmpty() && ink.dots.isEmpty())
            assertTrue(name, ClockDigitGeometry.advance(design, ':', showColon = false) < 0.1f)
        }
    }

    @Test
    fun `font ids round trip, colon included`() {
        ClockDesign.entries.forEach { design ->
            listOf(true, false).forEach { colon ->
                val font = ClockFont(design, colon)
                assertEquals(font, ClockFont.fromId(font.id))
            }
        }
        assertEquals(ClockFont.DEFAULT, ClockFont.fromId(null))
        assertEquals(ClockFont.DEFAULT, ClockFont.fromId("no_such_face"))
    }

    @Test
    fun `every design belongs to exactly one family`() {
        ClockDesign.entries.forEach { design ->
            assertEquals(design.name, 1, ClockFontFamily.entries.count { design in it.variants })
        }
    }

    /** Not an assertion: draws every design to build/clock-designs.png for a human to look at. */
    @Test
    fun `draw a specimen sheet`() {
        val designs = drawnDesigns()
        val em = 120f
        val rowHeight = 190
        val image = BufferedImage(1500, rowHeight * designs.size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        designs.forEachIndexed { row, (name, design) ->
            g.color = Color.RED
            g.drawString(name, 6, row * rowHeight + 14)
            var x = 10f
            val baseline = row * rowHeight + rowHeight - 24f
            for (character in "0123456789  12:45") {
                if (character == ' ') {
                    x += em * 0.3f
                    continue
                }
                ClockDigitGeometry.glyph(design, character, showColon = true)?.let { ink ->
                    InkTestRenderer.draw(image, ink, x, baseline, em)
                }
                x += ClockDigitGeometry.advance(design, character, showColon = true) * em
            }
        }
        g.dispose()
        val out = File("build/clock-designs.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.exists())
    }

    private fun drawnDesigns(): List<Pair<String, DigitDesign>> =
        ClockDesign.entries.mapNotNull { design -> design.digits?.let { design.name to it } }
}
