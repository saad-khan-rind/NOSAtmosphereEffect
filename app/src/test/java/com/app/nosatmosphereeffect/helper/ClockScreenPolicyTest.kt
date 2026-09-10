package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in which effects show the clock, where, and who gets to choose.
 *
 * The rules here are easy to break by accident from a long way away — adding an
 * effect id, changing a renderer's transition endpoints, or "tidying" a policy
 * table all do it silently, and the symptom is a clock that is invisible or
 * illegible rather than a crash.
 */
class ClockScreenPolicyTest {

    @Test
    fun `every catalogued effect composites the clock on both backends`() {
        val expected = listOf(
            "ORIGINAL", "REVERSE",
            "GLASS", "GLASS_REVERSE",
            "COLORFILL", "COLORFILL_REVERSE",
            "NEON", "NEON_REVERSE",
            "FROSTED", "FROSTED_REVERSE",
            "HALFTONE", "HALFTONE_REVERSE"
        )
        expected.forEach { effectId ->
            assertTrue(
                "$effectId should support the clock",
                AtmosphereClockPolicy.supportsEffect(effectId)
            )
        }
    }

    @Test
    fun `unknown effects never claim clock support`() {
        assertFalse(AtmosphereClockPolicy.supportsEffect(null))
        assertFalse(AtmosphereClockPolicy.supportsEffect(""))
        assertFalse(AtmosphereClockPolicy.supportsEffect("SOMETHING_NEW"))
    }

    @Test
    fun `only the effects that keep the photo intact offer a screen choice`() {
        // Colour Fill recolours, Sketch draws lines, Halftone screens into
        // dots — none of them displaces or softens the image, so a clock reads
        // at both ends and the user picks.
        listOf(
            "COLORFILL", "COLORFILL_REVERSE",
            "NEON", "NEON_REVERSE",
            "HALFTONE", "HALFTONE_REVERSE"
        ).forEach { effectId ->
            assertTrue(
                "$effectId should offer the lock/home/both choice",
                ClockScreenPolicy.offersChoice(effectId)
            )
        }

        // Atmosphere, Glass and Frosted all blur or refract on one side.
        listOf(
            "ORIGINAL", "REVERSE",
            "GLASS", "GLASS_REVERSE",
            "FROSTED", "FROSTED_REVERSE"
        ).forEach { effectId ->
            assertFalse(
                "$effectId must not offer a screen choice",
                ClockScreenPolicy.offersChoice(effectId)
            )
        }
    }

    @Test
    fun `a forced effect collapses any stored choice onto its sharp side`() {
        // The choice is stored globally, so switching from Colour Fill (where
        // "both" is legal) to Frosted must not leave Frosted claiming a
        // home-screen clock it would never draw.
        ClockScreen.entries.forEach { requested ->
            assertEquals(
                "FROSTED should always resolve to its sharp side",
                ClockScreenPolicy.defaultScreen("FROSTED"),
                ClockScreenPolicy.resolveScreen("FROSTED", requested)
            )
        }
    }

    @Test
    fun `a choosing effect keeps what the user picked`() {
        ClockScreen.entries.forEach { requested ->
            assertEquals(
                requested,
                ClockScreenPolicy.resolveScreen("COLORFILL", requested)
            )
        }
    }

    @Test
    fun `forward and reverse variants take opposite sides`() {
        assertNotEquals(
            ClockScreenPolicy.defaultScreen("GLASS"),
            ClockScreenPolicy.defaultScreen("GLASS_REVERSE")
        )
        assertNotEquals(
            ClockScreenPolicy.defaultScreen("ORIGINAL"),
            ClockScreenPolicy.defaultScreen("REVERSE")
        )
        assertNotEquals(
            ClockScreenPolicy.defaultScreen("FROSTED"),
            ClockScreenPolicy.defaultScreen("FROSTED_REVERSE")
        )
    }

    @Test
    fun `every effect that shows the clock can also draw depth`() {
        // The two are separate questions — an effect could gain the clock
        // before it gains segmentation — but every effect now binds a subject
        // mask in its display pass, so the sets match. If they ever diverge,
        // the settings screen must hide the depth switch for the difference.
        listOf(
            "ORIGINAL", "REVERSE",
            "GLASS", "GLASS_REVERSE",
            "COLORFILL", "COLORFILL_REVERSE",
            "NEON", "NEON_REVERSE",
            "FROSTED", "FROSTED_REVERSE",
            "HALFTONE", "HALFTONE_REVERSE"
        ).forEach { effectId ->
            assertTrue(
                "$effectId should support clock depth",
                AtmosphereClockPolicy.supportsDepth(effectId)
            )
        }
    }

    @Test
    fun `unknown effects never claim depth support`() {
        assertFalse(AtmosphereClockPolicy.supportsDepth(null))
        assertFalse(AtmosphereClockPolicy.supportsDepth("SOMETHING_NEW"))
    }
}

/** The fade curve both backends share. */
class ClockOverlayStateTest {

    private val base = ClockOverlayState(
        enabled = true,
        opacity = 1f,
        screenId = ClockScreen.LOCK.id,
        lockedProgress = 0f,
        unlockedProgress = 1f
    )

    @Test
    fun `a disabled clock is always fully transparent`() {
        val disabled = base.copy(enabled = false)
        assertEquals(0f, disabled.effectiveOpacity(0f), 0f)
        assertEquals(0f, disabled.effectiveOpacity(1f), 0f)
    }

    @Test
    fun `a lock-screen clock fades out as the wallpaper unlocks`() {
        assertEquals(1f, base.effectiveOpacity(0f), 1e-4f)
        assertEquals(0f, base.effectiveOpacity(1f), 1e-4f)
    }

    @Test
    fun `a home-screen clock fades in as the wallpaper unlocks`() {
        val home = base.copy(screenId = ClockScreen.HOME.id)
        assertEquals(0f, home.effectiveOpacity(0f), 1e-4f)
        assertEquals(1f, home.effectiveOpacity(1f), 1e-4f)
    }

    @Test
    fun `both stays visible across the whole transition`() {
        val both = base.copy(screenId = ClockScreen.BOTH.id)
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { progress ->
            assertEquals(
                "BOTH should stay visible at progress $progress",
                1f,
                both.effectiveOpacity(progress),
                1e-4f
            )
        }
    }

    @Test
    fun `reversed endpoints do not invert the fade`() {
        // Colour Fill's locked end is progress 1, not 0. The fade is computed
        // from the normalised unlock fraction precisely so effects can
        // disagree about which end is the lock screen without every renderer
        // having to special-case it.
        val reversed = base.copy(lockedProgress = 1f, unlockedProgress = 0f)
        assertEquals(1f, reversed.effectiveOpacity(1f), 1e-4f)
        assertEquals(0f, reversed.effectiveOpacity(0f), 1e-4f)
    }

    @Test
    fun `the user's opacity scales the fade rather than replacing it`() {
        val dimmed = base.copy(opacity = 0.5f)
        assertEquals(0.5f, dimmed.effectiveOpacity(0f), 1e-4f)
    }

    @Test
    fun `sanitizing replaces an unresolved auto colour with the fallback`() {
        // An AUTO colour reaching a renderer would draw an opaque black clock,
        // so it is collapsed here rather than trusted to have been resolved
        // upstream.
        val auto = base.copy(color = ClockPalette.AUTO).sanitized()
        assertFalse(ClockPalette.isAuto(auto.color))
        assertEquals(ClockPalette.DEFAULT_FALLBACK, auto.color)
    }

    @Test
    fun `sanitizing forces the face colour opaque`() {
        val translucent = base.copy(color = 0x00FF8800).sanitized()
        assertEquals(0xFF, (translucent.color ushr 24) and 0xFF)
    }

    @Test
    fun `sanitizing repairs non-finite geometry`() {
        val broken = base.copy(
            lockedProgress = Float.NaN,
            unlockedProgress = Float.POSITIVE_INFINITY,
            textureAspect = Float.NaN
        ).sanitized()
        assertTrue(broken.lockedProgress.isFinite())
        assertTrue(broken.unlockedProgress.isFinite())
        assertTrue(broken.textureAspect.isFinite())
    }

    @Test
    fun `a subject mask is wanted only when depth is on and the clock is shown`() {
        assertTrue(base.copy(depthEnabled = true).needsSubjectMask())
        assertFalse(base.copy(depthEnabled = false).needsSubjectMask())
        assertFalse(
            base.copy(enabled = false, depthEnabled = true).needsSubjectMask()
        )
    }
}

/** The clock face's geometry rules. */
class ClockFaceGeometryTest {

    @Test
    fun `per-axis stretch reproduces the requested shape exactly`() {
        // The stretch is folded into height and texture aspect rather than
        // passed as its own uniform, so this is the algebra the whole feature
        // rests on: if it drifts, every effect silently mis-sizes the clock.
        val state = ClockOverlayState(
            enabled = true,
            height = 0.24f,
            widthScale = 0.7f,
            heightScale = 1.3f
        ).sanitized()

        val rawAspect = 2.5f
        val renderedHeight = state.renderHeight
        val renderedWidth = renderedHeight * state.renderTextureAspect(rawAspect)

        assertEquals(0.24f * 1.3f, renderedHeight, 1e-5f)
        // Width must depend on widthScale alone — the heightScale in the
        // aspect has to cancel the one in the height.
        assertEquals(0.24f * rawAspect * 0.7f, renderedWidth, 1e-5f)
    }

    @Test
    fun `default scales leave the face untouched`() {
        val state = ClockOverlayState(enabled = true, height = 0.24f).sanitized()
        assertEquals(0.24f, state.renderHeight, 1e-5f)
        assertEquals(2f, state.renderTextureAspect(2f), 1e-5f)
    }

    @Test
    fun `a degenerate texture aspect cannot produce a zero-width clock`() {
        val state = ClockOverlayState(enabled = true).sanitized()
        assertTrue(state.renderTextureAspect(0f) > 0f)
        assertTrue(state.renderTextureAspect(Float.NaN) > 0f)
    }

    @Test
    fun `axis scales are clamped to the shared range`() {
        val wild = ClockOverlayState(
            widthScale = 99f,
            heightScale = -4f
        ).sanitized()
        assertEquals(AtmosphereClockPolicy.MAX_AXIS_SCALE, wild.widthScale, 1e-5f)
        assertEquals(AtmosphereClockPolicy.MIN_AXIS_SCALE, wild.heightScale, 1e-5f)
    }

    @Test
    fun `every style is condensed horizontally and stretched vertically`() {
        // Tall-and-narrow is the whole point of the set; a style that lost
        // either half would read as a caption next to the others.
        ClockStyle.entries.forEach { style ->
            assertTrue(
                "${style.id} should be stretched vertically",
                style.verticalStretch > 1.3f
            )
            assertTrue(
                "${style.id} should be condensed horizontally",
                style.horizontalScale in 0.7f..1f
            )
        }
    }

    @Test
    fun `style ids are unique and stable`() {
        val ids = ClockStyle.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // Stored in preferences, so renaming one silently resets everyone
        // using it back to the default.
        listOf("modern", "display", "serif", "mono", "stacked").forEach { id ->
            assertEquals(id, ClockStyle.fromId(id).id)
        }
    }
}
