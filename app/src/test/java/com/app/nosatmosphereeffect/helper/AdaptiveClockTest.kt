package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveClockTest {

    private val characters = "0123456789"

    @Test
    fun `every digit stays inside its cell at any weight and height`() {
        for (weight in listOf(0f, 0.5f, 1f)) {
            val stroke = AdaptiveClockGlyphs.stroke(weight)
            for (height in listOf(AdaptiveClockGlyphs.MIN_HEIGHT, 3.2f, AdaptiveClockGlyphs.MAX_HEIGHT)) {
                for (character in characters) {
                    val skeleton = AdaptiveClockGlyphs.skeleton(character, height, stroke)
                    assertNotNull("$character", skeleton)
                    skeleton!!
                    val slack = 1e-3f
                    assertTrue("$character left", skeleton.minX - stroke / 2f >= -slack)
                    assertTrue("$character right", skeleton.maxX + stroke / 2f <= 1f + slack)
                    // Every digit hangs from the same line…
                    assertEquals("$character top", 0f, skeleton.minY - stroke / 2f, 0.01f)
                    // …and reaches exactly as far down as it was asked to.
                    assertEquals("$character bottom", height, skeleton.maxY + stroke / 2f, 0.01f)
                }
            }
        }
    }

    @Test
    fun `a stretched zero keeps its stroke along the whole side`() {
        val stroke = AdaptiveClockGlyphs.stroke(0.5f)
        val zero = AdaptiveClockGlyphs.skeleton('0', AdaptiveClockGlyphs.MAX_HEIGHT, stroke)!!
        val left = stroke / 2f
        for (y in listOf(1f, 2f, 3f)) {
            assertEquals(0f, zero.distance(left, y), 1e-4f)
        }
        // Hollow in the middle.
        assertTrue(zero.distance(0.5f, 2f) > stroke)
    }

    @Test
    fun `the colon's pills stretch with its height`() {
        val stroke = 0.25f
        for (height in listOf(AdaptiveClockGlyphs.MIN_HEIGHT, AdaptiveClockGlyphs.MAX_HEIGHT)) {
            val colon = AdaptiveClockGlyphs.skeleton(':', height, stroke)!!
            assertEquals(height * 0.2f, colon.minY - stroke / 2f, 0.01f)
            assertEquals(height * 0.8f, colon.maxY + stroke / 2f, 0.01f)
            // Two separate pills: nothing at the middle.
            assertTrue(colon.distance(AdaptiveClockGlyphs.COLON_WIDTH / 2f, height * 0.5f) > stroke / 2f)
        }
        assertNull(AdaptiveClockGlyphs.skeleton('a', 3f, 0.25f))
    }

    @Test
    fun `the motion stretches a third past, then settles in one continuous movement`() {
        val values = (0L..AdaptiveClockFace.MOTION_MS).map { AdaptiveClockFace.motionCurve(it) }
        val peak = values.maxOrNull()!!
        assertTrue("overshoot $peak", peak in 1.30f..1.35f)
        val peakAt = values.indexOf(peak).toLong()
        assertEquals(AdaptiveClockFace.RISE_MS.toFloat(), peakAt.toFloat(), 2f)
        // Up all the way to the peak, down all the way after it, and never
        // below the target: one stretch and one return, no wobble.
        for (ms in 1 until values.size) {
            if (ms <= peakAt) {
                assertTrue("rising at $ms", values[ms] >= values[ms - 1])
            } else {
                assertTrue("settling at $ms", values[ms] <= values[ms - 1] + 1e-6f)
                assertTrue("below target at $ms", values[ms] >= 1f - 1e-6f)
            }
            assertTrue("jump at $ms", kotlin.math.abs(values[ms] - values[ms - 1]) < 0.01f)
        }
        // No stall at the top: 60ms either side of the turn it has already
        // moved visibly.
        assertTrue(peak - AdaptiveClockFace.motionCurve(peakAt + 60) > 0.01f)
        assertTrue(peak - AdaptiveClockFace.motionCurve(peakAt - 60) > 0.01f)
        assertEquals(1f, AdaptiveClockFace.motionCurve(AdaptiveClockFace.MOTION_MS), 0f)
        assertEquals(0f, AdaptiveClockFace.motionCurve(0L), 0f)
    }

    @Test
    fun `slots are laid out left to right without overlapping`() {
        var previousRight = -1f
        for (slot in 0 until AdaptiveClockGlyphs.SLOT_COUNT) {
            val left = AdaptiveClockGlyphs.slotLeft(slot)
            assertTrue(left > previousRight)
            previousRight = left + AdaptiveClockGlyphs.slotWidth(slot)
        }
        assertEquals(AdaptiveClockGlyphs.ROW_WIDTH, previousRight, 1e-4f)
    }

    private val box = ClockBoxRect(left = 0.3f, top = 0.2f, right = 0.7f, bottom = 0.5f)

    /** Screen y of a height in digit widths, inside [box]. */
    private fun screenY(units: Float) =
        box.top + units / AdaptiveClockGlyphs.MAX_HEIGHT * box.height

    @Test
    fun `a digit with nothing under it runs full length`() {
        val height = AdaptiveClockFit.digitHeight(0f, box) { _, _ -> 0f }
        assertEquals(AdaptiveClockGlyphs.MAX_HEIGHT, height, 1e-4f)
    }

    @Test
    fun `a digit stops a clearance above the subject`() {
        val subjectTop = screenY(3.2f)
        val height = AdaptiveClockFit.digitHeight(0f, box) { _, y -> if (y >= subjectTop) 1f else 0f }
        assertEquals(3.2f - AdaptiveClockFit.CLEARANCE, height, 0.06f)
    }

    @Test
    fun `a subject reaching above the natural height leaves the digit compact`() {
        val height = AdaptiveClockFit.digitHeight(0f, box) { _, _ -> 1f }
        assertEquals(AdaptiveClockGlyphs.MIN_HEIGHT, height, 1e-4f)
    }

    @Test
    fun `only the digits over the subject are shortened`() {
        // A subject under the right half of the row only.
        val middle = box.left + box.width / 2f
        val subjectTop = screenY(2.6f)
        val subject = { x: Float, y: Float -> if (x > middle && y >= subjectTop) 1f else 0f }
        val first = AdaptiveClockFit.digitHeight(AdaptiveClockGlyphs.slotLeft(0), box, subjectAt = subject)
        val last = AdaptiveClockFit.digitHeight(AdaptiveClockGlyphs.slotLeft(4), box, subjectAt = subject)
        assertEquals(AdaptiveClockGlyphs.MAX_HEIGHT, first, 1e-4f)
        assertTrue(last < 2.6f)
    }

    @Test
    fun `a stacked column splits evenly and never below a natural digit`() {
        val full = AdaptiveClockGlyphs.STACK_HEIGHT
        val (upper, lower) = AdaptiveClockGlyphs.stackSplit(full)
        assertEquals(upper, lower, 1e-4f)
        assertEquals(full, upper + AdaptiveClockGlyphs.STACK_GAP + lower, 1e-4f)
        val (shortUpper, shortLower) = AdaptiveClockGlyphs.stackSplit(0f)
        assertEquals(AdaptiveClockGlyphs.MIN_HEIGHT, shortUpper, 0f)
        assertEquals(AdaptiveClockGlyphs.MIN_HEIGHT, shortLower, 0f)
    }

    @Test
    fun `a stacked column is fitted as one length`() {
        val stackBox = ClockBoxRect(left = 0.6f, top = 0.1f, right = 0.9f, bottom = 0.7f)
        val subjectTop = stackBox.top + 5f / AdaptiveClockGlyphs.STACK_HEIGHT * stackBox.height
        val length = AdaptiveClockFit.digitHeight(
            slotLeft = AdaptiveClockGlyphs.laneLeft(true, 0),
            box = stackBox,
            boxWidthUnits = AdaptiveClockGlyphs.boxWidth(true),
            boxHeightUnits = AdaptiveClockGlyphs.boxHeight(true),
            minLength = AdaptiveClockGlyphs.laneMin(true),
            subjectAt = { _, y -> if (y >= subjectTop) 1f else 0f }
        )
        assertEquals(5f - AdaptiveClockFit.CLEARANCE, length, 0.06f)
        // Never shorter than two natural digits and the gap between them.
        val blocked = AdaptiveClockFit.digitHeight(
            slotLeft = 0f,
            box = stackBox,
            boxWidthUnits = AdaptiveClockGlyphs.boxWidth(true),
            boxHeightUnits = AdaptiveClockGlyphs.boxHeight(true),
            minLength = AdaptiveClockGlyphs.laneMin(true),
            subjectAt = { _, _ -> 1f }
        )
        assertEquals(AdaptiveClockGlyphs.laneMin(true), blocked, 0f)
    }

    @Test
    fun `the scroll viewport pans a screen-wide window across a wide image`() {
        // An image twice as wide, relative to its height, as the screen.
        val view = ClockSceneViewport.forScroll(0f, screenAspect = 0.5f, imageAspect = 1f)
        assertEquals(0f, view.u(0f), 1e-5f)
        assertEquals(0.5f, view.u(1f), 1e-5f)
        val right = ClockSceneViewport.forScroll(1f, screenAspect = 0.5f, imageAspect = 1f)
        assertEquals(0.5f, right.u(0f), 1e-5f)
        assertEquals(0.4f, right.v(0.4f), 1e-5f)
    }

    @Test
    fun `the centre crop viewport trims the longer side evenly`() {
        val tall = ClockSceneViewport.forCenterCrop(viewAspect = 1f, imageAspect = 0.5f)
        assertEquals(0.25f, tall.v(0f), 1e-5f)
        assertEquals(0.75f, tall.v(1f), 1e-5f)
        assertEquals(0.3f, tall.u(0.3f), 1e-5f)
    }

    @Test
    fun `adaptive colour resolves like auto wherever one colour is needed`() {
        assertEquals(0xFF123456.toInt(), ClockPalette.resolve(ClockPalette.ADAPTIVE, 0xFF123456.toInt()))
        assertTrue(ClockPalette.followsWallpaper(ClockPalette.ADAPTIVE))
        assertFalse(ClockPalette.isAuto(ClockPalette.ADAPTIVE))
        assertEquals(ClockPalette.ADAPTIVE, AtmosphereClockPolicy.sanitizeColor(ClockPalette.ADAPTIVE))
    }

    @Test
    fun `the adaptive face is solid and always wants the subject`() {
        val state = ClockOverlayState(
            enabled = true,
            depthEnabled = false,
            styleId = ClockStyle.ADAPTIVE.id,
            requestedColor = ClockPalette.ADAPTIVE
        ).sanitized()
        // Solid, and its colour follows the effect because it came from the
        // wallpaper; a picked colour stays as picked.
        assertEquals(0f, state.glassMode, 0f)
        assertEquals(ClockOverlayState.FOLLOWS_WALLPAPER, state.glassMeta, 0f)
        assertEquals(0f, state.copy(requestedColor = 0xFFFFFFFF.toInt()).glassMeta, 0f)
        // The same flag rides on the glass faces' own numbers, which the
        // shaders take it back off.
        val glassAuto = state.copy(styleId = ClockStyle.GLASS.id, requestedColor = ClockPalette.AUTO)
        assertEquals(3f + ClockOverlayState.FOLLOWS_WALLPAPER, glassAuto.glassMeta, 0f)
        assertTrue(state.needsSubjectMask())
        // Always in front of the subject, whatever the depth switch says.
        assertFalse(state.copy(depthEnabled = true).sanitized().depthEnabled)
        assertTrue(
            state.copy(depthEnabled = true, styleId = ClockStyle.GLASS.id).sanitized().depthEnabled
        )
        assertTrue(state.adaptiveColors)
        // A glass face with depth off still needs no mask.
        assertFalse(state.copy(styleId = ClockStyle.GLASS.id).needsSubjectMask())
    }

    @Test
    fun `weight is kept in range`() {
        assertEquals(1f, AtmosphereClockPolicy.sanitizeWeight(3f), 0f)
        assertEquals(AtmosphereClockPolicy.DEFAULT_WEIGHT, AtmosphereClockPolicy.sanitizeWeight(Float.NaN), 0f)
    }
}
