package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockBoxPlacementTest {

    /** Digits inset inside the texture, as the real faces are. */
    private val content = ClockFaceBox(
        aspect = 1.4f,
        left = 0.08f,
        top = 0.14f,
        right = 0.92f,
        bottom = 0.86f
    )
    private val contentAspect = content.contentAspect
    private val screenAspect = 0.46f
    private val start = ClockPlacement(
        centerX = 0.5f,
        top = 0.13f,
        height = 0.24f,
        widthScale = 1f
    )

    private fun contentOf(placement: ClockPlacement) =
        ClockBoxPlacement.contentBox(placement, contentAspect, screenAspect)

    private fun drag(
        handle: ClockBoxHandle,
        dx: Float = 0f,
        dy: Float = 0f,
        from: ClockPlacement = start
    ): ClockPlacement {
        val box = contentOf(from)
        val proposed = when (handle) {
            ClockBoxHandle.MOVE ->
                ClockBoxRect(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
            ClockBoxHandle.TOP_LEFT -> ClockBoxRect(box.left + dx, box.top + dy, box.right, box.bottom)
            ClockBoxHandle.TOP_RIGHT -> ClockBoxRect(box.left, box.top + dy, box.right + dx, box.bottom)
            ClockBoxHandle.BOTTOM_LEFT -> ClockBoxRect(box.left + dx, box.top, box.right, box.bottom + dy)
            ClockBoxHandle.BOTTOM_RIGHT -> ClockBoxRect(box.left, box.top, box.right + dx, box.bottom + dy)
            ClockBoxHandle.LEFT -> ClockBoxRect(box.left + dx, box.top, box.right, box.bottom)
            ClockBoxHandle.RIGHT -> ClockBoxRect(box.left, box.top, box.right + dx, box.bottom)
            ClockBoxHandle.TOP -> ClockBoxRect(box.left, box.top + dy, box.right, box.bottom)
            ClockBoxHandle.BOTTOM -> ClockBoxRect(box.left, box.top, box.right, box.bottom + dy)
        }
        return ClockBoxPlacement.apply(from, proposed, handle, contentAspect, screenAspect)
    }

    @Test
    fun `moving the clock does not change its size`() {
        val moved = drag(ClockBoxHandle.MOVE, dx = 0.07f, dy = 0.09f)

        assertEquals(start.height, moved.height, 0f)
        assertEquals(start.widthScale, moved.widthScale, 0f)
        val before = contentOf(start)
        val after = contentOf(moved)
        assertEquals(before.width, after.width, 1e-5f)
        assertEquals(before.height, after.height, 1e-5f)
        assertEquals(before.top + 0.09f, after.top, 1e-5f)
    }

    @Test
    fun `a move that lands near the centre snaps to it`() {
        val offCentre = start.copy(centerX = 0.40f)
        val moved = drag(ClockBoxHandle.MOVE, dx = 0.101f, from = offCentre)

        assertTrue(ClockBoxPlacement.isCentred(moved))
        assertEquals(offCentre.height, moved.height, 0f)
    }

    @Test
    fun `resizing from the bottom right leaves the top left corner alone`() {
        val resized = drag(ClockBoxHandle.BOTTOM_RIGHT, dx = 0.06f, dy = 0.05f)

        val before = contentOf(start)
        val after = contentOf(resized)
        assertEquals("left edge moved", before.left, after.left, 1e-4f)
        assertEquals("top edge moved", before.top, after.top, 1e-4f)
        assertTrue("should have grown", after.width > before.width && after.height > before.height)
    }

    @Test
    fun `resizing from the top left leaves the bottom right corner alone`() {
        val resized = drag(ClockBoxHandle.TOP_LEFT, dx = -0.05f, dy = -0.04f)

        val before = contentOf(start)
        val after = contentOf(resized)
        assertEquals("right edge moved", before.right, after.right, 1e-4f)
        assertEquals("bottom edge moved", before.bottom, after.bottom, 1e-4f)
        assertTrue("should have grown", after.width > before.width)
    }

    @Test
    fun `a horizontal resize keeps the height and the anchored edge`() {
        val resized = drag(ClockBoxHandle.RIGHT, dx = 0.05f)

        val before = contentOf(start)
        val after = contentOf(resized)
        assertEquals(start.height, resized.height, 0f)
        assertEquals(before.left, after.left, 1e-4f)
        assertEquals(before.top, after.top, 1e-4f)
        assertTrue(after.width > before.width)
    }

    @Test
    fun `a vertical resize keeps the width and the horizontal centre`() {
        val resized = drag(ClockBoxHandle.BOTTOM, dy = 0.05f)

        val before = contentOf(start)
        val after = contentOf(resized)
        assertEquals("the clock moved sideways", before.centerX, after.centerX, 1e-4f)
        assertEquals("the width changed", before.width, after.width, 1e-3f)
        assertEquals(before.top, after.top, 1e-4f)
        assertTrue(after.height > before.height)
    }

    @Test
    fun `a resize never snaps the centre sideways`() {
        // Starting just outside the snap zone: growing must not pull it in.
        val offCentre = start.copy(centerX = 0.5f + ClockBoxPlacement.CENTRE_SNAP * 1.2f)
        val resized = drag(ClockBoxHandle.BOTTOM, dy = 0.04f, from = offCentre)

        assertEquals(offCentre.centerX, resized.centerX, 1e-4f)
    }

    @Test
    fun `the same drag applied twice gives the same result`() {
        // Every frame resolves against the gesture's start, so a drag cannot
        // drift as it is re-applied.
        val once = drag(ClockBoxHandle.BOTTOM_RIGHT, dx = 0.03f, dy = 0.02f)
        val twice = drag(ClockBoxHandle.BOTTOM_RIGHT, dx = 0.03f, dy = 0.02f)

        assertEquals(once.centerX, twice.centerX, 0f)
        assertEquals(once.top, twice.top, 0f)
        assertEquals(once.height, twice.height, 0f)
        assertEquals(once.widthScale, twice.widthScale, 0f)
    }

    @Test
    fun `a zero drag changes nothing`() {
        ClockBoxHandle.entries.forEach { handle ->
            val settled = drag(handle)
            assertEquals("$handle moved the clock", start.centerX, settled.centerX, 1e-4f)
            assertEquals("$handle moved the clock", start.top, settled.top, 1e-4f)
            assertEquals("$handle resized the clock", start.height, settled.height, 1e-4f)
            assertEquals("$handle reshaped the clock", start.widthScale, settled.widthScale, 1e-3f)
        }
    }

    @Test
    fun `a tall clock still reaches the top of the screen`() {
        // The regression: the stored top used to be the face TEXTURE's top,
        // and the digits started a fraction of its height below that. Clamping
        // the texture meant the taller the clock, the further down it stopped.
        val tall = start.copy(height = 0.5f)
        val short = start.copy(height = 0.1f)

        val tallTop = contentOf(drag(ClockBoxHandle.MOVE, dy = -1f, from = tall)).top
        val shortTop = contentOf(drag(ClockBoxHandle.MOVE, dy = -1f, from = short)).top

        assertEquals("a tall clock must reach the top edge", 0f, tallTop, 1e-4f)
        assertEquals("a short clock must reach the top edge", 0f, shortTop, 1e-4f)
    }

    @Test
    fun `the clock reaches every edge at any size`() {
        listOf(0.08f, 0.24f, 0.5f, 0.9f).forEach { height ->
            val sized = start.copy(height = height)
            val up = contentOf(drag(ClockBoxHandle.MOVE, dy = -2f, from = sized))
            val down = contentOf(drag(ClockBoxHandle.MOVE, dy = 2f, from = sized))
            val left = contentOf(drag(ClockBoxHandle.MOVE, dx = -2f, from = sized))
            val right = contentOf(drag(ClockBoxHandle.MOVE, dx = 2f, from = sized))

            assertEquals("height $height cannot reach the top", 0f, up.top, 1e-4f)
            assertEquals("height $height cannot reach the bottom", 1f, down.bottom, 1e-4f)
            assertEquals("height $height cannot reach the left", 0f, left.left, 1e-4f)
            assertEquals("height $height cannot reach the right", 1f, right.right, 1e-4f)
        }
    }

    @Test
    fun `the clock never leaves the screen`() {
        ClockBoxHandle.entries.forEach { handle ->
            listOf(-2f, -0.3f, 0.3f, 2f).forEach { delta ->
                val settled = drag(handle, dx = delta, dy = delta, from = start.copy(height = 0.4f))
                val box = contentOf(settled)
                assertTrue("$handle by $delta left the screen: $box", box.left >= -1e-4f)
                assertTrue("$handle by $delta left the screen: $box", box.top >= -1e-4f)
                assertTrue("$handle by $delta left the screen: $box", box.right <= 1f + 1e-4f)
                assertTrue("$handle by $delta left the screen: $box", box.bottom <= 1f + 1e-4f)
            }
        }
    }

    @Test
    fun `growing past an edge stops at the edge rather than overflowing`() {
        val grown = drag(ClockBoxHandle.BOTTOM, dy = 3f)
        val box = contentOf(grown)

        assertTrue("should have grown", box.height > contentOf(start).height)
        assertTrue("bottom edge overflowed: $box", box.bottom <= 1f + 1e-4f)
    }

    @Test
    fun `the texture surrounds the digits exactly where the face says it does`() {
        // What the shaders are given. The digits must land on their own box
        // whatever margin the bitmap carries around them, because that margin
        // changes with the style and with where the date was dragged to.
        val texture = ClockBoxPlacement.textureBox(start, content, screenAspect)
        val digits = contentOf(start)

        assertEquals(
            digits.left,
            texture.left + content.left * texture.width,
            1e-5f
        )
        assertEquals(
            digits.top,
            texture.top + content.top * texture.height,
            1e-5f
        )
        assertEquals(
            digits.right,
            texture.left + content.right * texture.width,
            1e-5f
        )
        assertEquals(
            digits.bottom,
            texture.top + content.bottom * texture.height,
            1e-5f
        )
    }

    @Test
    fun `a wider margin does not move the digits`() {
        // The whole point of storing the digits' box rather than the texture's:
        // the same stored numbers put the clock in the same place on a face
        // whose bitmap is half as wide again.
        val loose = ClockFaceBox(
            aspect = 1.4f * 1.5f,
            left = 0.28f,
            top = 0.34f,
            right = 0.72f,
            bottom = 0.66f
        )
        val tight = ClockBoxPlacement.textureBox(start, content, screenAspect)
        val wide = ClockBoxPlacement.textureBox(start, loose, screenAspect)

        assertTrue("the looser face should need a bigger texture", wide.width > tight.width)
        assertEquals(
            start.top,
            wide.top + loose.top * wide.height,
            1e-5f
        )
        assertEquals(
            start.centerX,
            wide.left + (loose.left + loose.right) / 2f * wide.width,
            1e-5f
        )
    }

    @Test
    fun `the date's relative box is what the two placements describe`() {
        val date = ClockPlacement(centerX = 0.5f, top = 0.07f, height = 0.05f, widthScale = 1f)
        val dateAspect = 5.5f
        val relative = ClockBoxPlacement.relativeDateBox(
            clock = start,
            date = date,
            contentAspect = contentAspect,
            dateAspect = dateAspect,
            screenAspect = screenAspect
        )

        val clockBox = contentOf(start)
        val dateBox = ClockBoxPlacement.contentBox(date, dateAspect, screenAspect)
        assertEquals(
            (dateBox.left - clockBox.left) / clockBox.width,
            relative.offsetX,
            1e-5f
        )
        assertEquals(
            (dateBox.top - clockBox.top) / clockBox.height,
            relative.offsetY,
            1e-5f
        )
        assertEquals(dateBox.width / clockBox.width, relative.width, 1e-5f)
        assertEquals(dateBox.height / clockBox.height, relative.height, 1e-5f)
        assertTrue("the date starts above the clock", relative.offsetY < 0f)
    }

    @Test
    fun `quantizing the date's box lands on a grid and stays put`() {
        val box = ClockDateLayout(
            offsetX = 0.123456f,
            offsetY = -0.501f,
            width = 0.7771f,
            height = 0.2002f
        )
        val once = box.quantized()

        assertEquals(once, once.quantized())
        assertEquals(box.offsetX, once.offsetX, 0.003f)
        assertEquals(box.offsetY, once.offsetY, 0.003f)
        assertEquals(box.width, once.width, 0.003f)
        assertEquals(box.height, once.height, 0.003f)
    }

    @Test
    fun `the date is placed by its own box, not the clock's`() {
        // Dragging the date must not move or resize the clock.
        val date = ClockPlacement(centerX = 0.3f, top = 0.8f, height = 0.05f, widthScale = 1f)
        val moved = ClockBoxPlacement.apply(
            start = date,
            proposed = ClockBoxRect(0.1f, 0.6f, 0.6f, 0.65f),
            handle = ClockBoxHandle.MOVE,
            contentAspect = 5.5f,
            screenAspect = screenAspect
        )

        assertEquals(0.6f, moved.top, 1e-4f)
        assertEquals(date.height, moved.height, 0f)
        assertEquals(date.widthScale, moved.widthScale, 0f)
    }

    @Test
    fun `shrinking past the limit stops at the limit instead of inverting`() {
        val tiny = drag(ClockBoxHandle.BOTTOM_RIGHT, dx = -1f, dy = -1f)

        assertEquals(AtmosphereClockPolicy.MIN_HEIGHT, tiny.height, 1e-5f)
        assertTrue(tiny.widthScale >= AtmosphereClockPolicy.MIN_AXIS_SCALE)
        val after = contentOf(tiny)
        assertTrue("box inverted", after.width > 0f && after.height > 0f)
    }

    @Test
    fun `a box that overflows after its shape changes is fitted back on screen`() {
        // Sized on a narrow stacked face, then shown as a wide row.
        val tall = start.copy(height = 0.5f)
        val wideAspect = 2.6f
        val fitted = ClockBoxPlacement.fitOnScreen(tall, wideAspect, screenAspect)
        val box = ClockBoxPlacement.contentBox(fitted, wideAspect, screenAspect)
        assertTrue(box.left >= ClockBoxPlacement.SIDE_MARGIN - 1e-4f)
        assertTrue(box.right <= 1f - ClockBoxPlacement.SIDE_MARGIN + 1e-4f)
        // The shape the user chose is kept; only the size gave way.
        assertEquals(tall.widthScale, fitted.widthScale, 1e-5f)
    }

    @Test
    fun `a box that already fits is left alone`() {
        assertTrue(ClockBoxPlacement.fitOnScreen(start, contentAspect, screenAspect) === start)
    }

    @Test
    fun `a pinch scales about the centre and keeps the shape`() {
        val pinched = ClockBoxPlacement.transform(start, 0.5f, 0f, 0f, contentAspect, screenAspect)
        assertEquals(start.height * 0.5f, pinched.height, 1e-5f)
        assertEquals(start.widthScale, pinched.widthScale, 1e-5f)
        assertEquals(start.top + start.height / 2f, pinched.top + pinched.height / 2f, 1e-5f)
        assertEquals(0.5f, pinched.centerX, 1e-5f)
    }

    @Test
    fun `a pinch cannot push the box off screen`() {
        val grown = ClockBoxPlacement.transform(start, 6f, 0.4f, 0f, contentAspect, screenAspect)
        val box = contentOf(grown)
        assertTrue(box.left >= -1e-4f && box.right <= 1f + 1e-4f)
    }

    @Test
    fun `a new date is sized from the clock and sits above it`() {
        val dateAspect = 6f
        val date = ClockBoxPlacement.dateBesideClock(start, contentAspect, dateAspect, screenAspect)
        val clockBox = contentOf(start)
        val dateBox = ClockBoxPlacement.contentBox(date, dateAspect, screenAspect)
        assertEquals(clockBox.width * ClockBoxPlacement.DATE_WIDTH_SHARE, dateBox.width, 1e-3f)
        assertEquals(clockBox.centerX, dateBox.centerX, 1e-4f)
        assertTrue(dateBox.bottom <= clockBox.top)
    }

    @Test
    fun `a new date goes below a clock with no room above it`() {
        val high = start.copy(top = 0.02f)
        val dateAspect = 6f
        val date = ClockBoxPlacement.dateBesideClock(high, contentAspect, dateAspect, screenAspect)
        val clockBox = contentOf(high)
        val dateBox = ClockBoxPlacement.contentBox(date, dateAspect, screenAspect)
        assertTrue(dateBox.top >= clockBox.bottom)
    }
}
