package com.app.nosatmosphereeffect.subject

import com.app.nosatmosphereeffect.effect.CanvasSubjectSettings
import com.app.nosatmosphereeffect.effect.GlassEffectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubjectIsolationPolicyTest {

    @Test
    fun `effect toggles use independent preference keys`() {
        assertNotEquals(
            GlassEffectPolicy.BACKGROUND_ONLY_KEY,
            SubjectIsolationPolicy.HALFTONE_BACKGROUND_ONLY_KEY
        )
        assertNotEquals(
            CanvasSubjectSettings.ENABLED_KEY,
            GlassEffectPolicy.BACKGROUND_ONLY_KEY
        )
        assertNotEquals(
            CanvasSubjectSettings.ENABLED_KEY,
            SubjectIsolationPolicy.HALFTONE_BACKGROUND_ONLY_KEY
        )
    }

    @Test
    fun `disabled isolation always applies the full effect`() {
        assertEquals(
            1f,
            SubjectIsolationPolicy.effectCoverage(
                backgroundOnly = false,
                hasSubjectMask = false,
                foregroundConfidence = 1f
            ),
            0f
        )
    }

    @Test
    fun `background only fails closed while a mask is unavailable`() {
        assertEquals(
            0f,
            SubjectIsolationPolicy.effectCoverage(
                backgroundOnly = true,
                hasSubjectMask = false,
                foregroundConfidence = 0f
            ),
            0f
        )
    }

    @Test
    fun `subject confidence protects foreground but not background`() {
        assertEquals(
            1f,
            SubjectIsolationPolicy.effectCoverage(
                backgroundOnly = true,
                hasSubjectMask = true,
                foregroundConfidence = 0f
            ),
            0f
        )
        assertEquals(
            0f,
            SubjectIsolationPolicy.effectCoverage(
                backgroundOnly = true,
                hasSubjectMask = true,
                foregroundConfidence = 1f
            ),
            0f
        )
        assertEquals(
            0f,
            SubjectIsolationPolicy.effectCoverage(
                backgroundOnly = true,
                hasSubjectMask = true,
                foregroundConfidence = Float.NaN
            ),
            0f
        )
    }
}
