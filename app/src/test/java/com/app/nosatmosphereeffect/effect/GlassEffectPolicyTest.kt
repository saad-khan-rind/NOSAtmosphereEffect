package com.app.nosatmosphereeffect.effect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassEffectPolicyTest {

    @Test
    fun `line count is rounded and clamped to supported bounds`() {
        assertEquals(
            GlassEffectPolicy.MIN_LINE_COUNT,
            GlassEffectPolicy.sanitizeLineCount(Int.MIN_VALUE)
        )
        assertEquals(19, GlassEffectPolicy.sanitizeLineCount(18.6f))
        assertEquals(
            GlassEffectPolicy.MAX_LINE_COUNT,
            GlassEffectPolicy.sanitizeLineCount(Int.MAX_VALUE)
        )
    }

    @Test
    fun `non-finite line counts use the visual default`() {
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_COUNT,
            GlassEffectPolicy.sanitizeLineCount(Float.NaN)
        )
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_COUNT,
            GlassEffectPolicy.sanitizeLineCount(Float.POSITIVE_INFINITY)
        )
    }

    @Test
    fun `line thickness is finite and constrained to the supported rib shape range`() {
        assertEquals(
            GlassEffectPolicy.MIN_LINE_THICKNESS,
            GlassEffectPolicy.sanitizeLineThickness(-1f),
            0f
        )
        assertEquals(
            GlassEffectPolicy.MAX_LINE_THICKNESS,
            GlassEffectPolicy.sanitizeLineThickness(2f),
            0f
        )
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            GlassEffectPolicy.sanitizeLineThickness(Float.NaN),
            0f
        )
        assertTrue(GlassEffectPolicy.sanitizeLineThickness(2f) <= 1f)
    }

    @Test
    fun `legacy defaults migrate to the measured Nothing glass preset`() {
        val migrated = GlassEffectPolicy.resolveStoredSettings(
            lineCount = 18,
            lineThickness = 0.65f,
            presetVersion = 0
        )

        assertEquals(GlassEffectPolicy.DEFAULT_LINE_COUNT, migrated.lineCount)
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            migrated.lineThickness,
            0f
        )
    }

    @Test
    fun `custom glass settings survive the preset migration`() {
        val migrated = GlassEffectPolicy.resolveStoredSettings(
            lineCount = 24,
            lineThickness = 0.45f,
            presetVersion = 0
        )

        assertEquals(24, migrated.lineCount)
        assertEquals(0.45f, migrated.lineThickness, 0f)
    }

    @Test
    fun `current preset version never rewrites an intentional legacy-shaped value`() {
        val current = GlassEffectPolicy.resolveStoredSettings(
            lineCount = 18,
            lineThickness = 0.65f,
            presetVersion = GlassEffectPolicy.CURRENT_PRESET_VERSION
        )

        assertEquals(18, current.lineCount)
        assertEquals(0.65f, current.lineThickness, 0f)
    }

    @Test
    fun `forward and reverse shader progress are exact temporal opposites`() {
        for (step in 0..100) {
            val progress = step / 100f
            assertEquals(
                progress,
                GlassEffectPolicy.shaderProgress(progress, reverse = false),
                0f
            )
            assertEquals(
                1f - progress,
                GlassEffectPolicy.shaderProgress(progress, reverse = true),
                0f
            )
        }
        assertEquals(
            0f,
            GlassEffectPolicy.shaderProgress(Float.NaN, reverse = false),
            0f
        )
        assertEquals(
            1f,
            GlassEffectPolicy.shaderProgress(Float.NaN, reverse = true),
            0f
        )
    }

    @Test
    fun `screen coordinates map safely to line indices including the right edge`() {
        assertEquals(0, GlassEffectPolicy.lineIndex(-1f, 8))
        assertEquals(0, GlassEffectPolicy.lineIndex(0f, 8))
        assertEquals(4, GlassEffectPolicy.lineIndex(0.5f, 8))
        assertEquals(7, GlassEffectPolicy.lineIndex(0.9999f, 8))
        assertEquals(7, GlassEffectPolicy.lineIndex(1f, 8))
        assertEquals(7, GlassEffectPolicy.lineIndex(2f, 8))
    }

    @Test
    fun `rightmost line has the first reveal order`() {
        assertEquals(3, GlassEffectPolicy.revealOrderFromRight(0, 4))
        assertEquals(2, GlassEffectPolicy.revealOrderFromRight(1, 4))
        assertEquals(1, GlassEffectPolicy.revealOrderFromRight(2, 4))
        assertEquals(0, GlassEffectPolicy.revealOrderFromRight(3, 4))
    }

    @Test
    fun `transition endpoints hide and reveal every line exactly`() {
        repeat(12) { column ->
            assertEquals(
                0f,
                GlassEffectPolicy.revealAmount(column, 12, 0f),
                0f
            )
            assertEquals(
                1f,
                GlassEffectPolicy.revealAmount(column, 12, 1f),
                0f
            )
        }
    }

    @Test
    fun `only one line is partially revealing during each transition interval`() {
        val amounts = (0 until 4).map { column ->
            GlassEffectPolicy.revealAmount(column, 4, 0.375f)
        }

        assertEquals(listOf(0f, 0f, 0.5f, 1f), amounts)
    }

    @Test
    fun `each line reveal is monotonic`() {
        repeat(10) { column ->
            var previous = 0f
            for (step in 0..100) {
                val amount = GlassEffectPolicy.revealAmount(
                    column,
                    10,
                    step / 100f
                )
                assertTrue("Line $column went backwards", amount >= previous)
                previous = amount
            }
        }
    }

    @Test
    fun `reverse transition exactly retraces the forward line sequence`() {
        repeat(10) { column ->
            for (step in 0..100) {
                val lockToHomeProgress = step / 100f
                val reverseAmount = GlassEffectPolicy.revealAmount(
                    column,
                    10,
                    GlassEffectPolicy.shaderProgress(lockToHomeProgress, reverse = true)
                )
                val forwardRewound = GlassEffectPolicy.revealAmount(
                    column,
                    10,
                    1f - lockToHomeProgress
                )
                assertEquals(forwardRewound, reverseAmount, 0f)
            }
        }
    }

    @Test
    fun `stored transition styles are stable and invalid values use the default`() {
        assertEquals(
            GlassTransitionStyle.RIGHT_TO_LEFT,
            GlassTransitionStyle.fromStoredValue("right_to_left")
        )
        assertEquals(
            GlassTransitionStyle.FADE,
            GlassTransitionStyle.fromStoredValue("fade")
        )
        assertEquals(
            GlassTransitionStyle.RIGHT_TO_LEFT,
            GlassTransitionStyle.fromStoredValue("unknown")
        )
        assertEquals(
            GlassTransitionStyle.RIGHT_TO_LEFT,
            GlassTransitionStyle.fromStoredValue(null)
        )
    }

    @Test
    fun `fade transition changes every line by the same amount`() {
        for (step in 0..100) {
            val progress = step / 100f
            val amounts = (0 until 12).map { column ->
                GlassEffectPolicy.transitionAmount(
                    column,
                    12,
                    progress,
                    GlassTransitionStyle.FADE
                )
            }
            assertTrue(amounts.all { it == amounts.first() })
        }
    }

    @Test
    fun `both transition styles preserve exact endpoints and reverse symmetry`() {
        for (style in GlassTransitionStyle.entries) {
            repeat(12) { column ->
                assertEquals(
                    0f,
                    GlassEffectPolicy.transitionAmount(column, 12, 0f, style),
                    0f
                )
                assertEquals(
                    1f,
                    GlassEffectPolicy.transitionAmount(column, 12, 1f, style),
                    0f
                )
                for (step in 0..100) {
                    val progress = step / 100f
                    assertEquals(
                        GlassEffectPolicy.transitionAmount(column, 12, 1f - progress, style),
                        GlassEffectPolicy.transitionAmount(
                            column,
                            12,
                            GlassEffectPolicy.shaderProgress(progress, reverse = true),
                            style
                        ),
                        0f
                    )
                }
            }
        }
    }

    @Test
    fun `preset migration preserves new transition and isolation choices`() {
        val settings = GlassEffectPolicy.resolveStoredSettings(
            lineCount = 18,
            lineThickness = 0.65f,
            presetVersion = 0,
            transitionStyle = GlassTransitionStyle.FADE,
            backgroundOnly = true
        )

        assertEquals(GlassTransitionStyle.FADE, settings.transitionStyle)
        assertTrue(settings.backgroundOnly)
    }

    @Test
    fun `reverse sequential transition clears glass from left to right`() {
        val amounts = (0 until 4).map { column ->
            GlassEffectPolicy.transitionAmount(
                columnFromLeft = column,
                lineCount = 4,
                globalProgress = GlassEffectPolicy.shaderProgress(
                    lockToHomeProgress = 0.375f,
                    reverse = true
                ),
                style = GlassTransitionStyle.RIGHT_TO_LEFT
            )
        }

        assertEquals(listOf(0f, 0.5f, 1f, 1f), amounts)
    }
}
