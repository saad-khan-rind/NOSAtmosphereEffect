package com.app.nosatmosphereeffect.helper

import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperBehaviorPolicyTest {

    @Test
    fun `stored targets round trip and invalid values use both`() {
        val storedValues = mapOf(
            AlwaysAppliedTarget.HOME to "home",
            AlwaysAppliedTarget.LOCK to "lock",
            AlwaysAppliedTarget.BOTH to "both"
        )

        storedValues.forEach { (target, storedValue) ->
            assertEquals(storedValue, target.storedValue)
            assertEquals(target, AlwaysAppliedTarget.fromStoredValue(storedValue))
        }

        listOf(null, "", "HOME", "unknown").forEach { invalidValue ->
            assertEquals(
                AlwaysAppliedTarget.BOTH,
                AlwaysAppliedTarget.fromStoredValue(invalidValue)
            )
        }
    }

    @Test
    fun `behavior settings default to transitions and both surfaces`() {
        val settings = WallpaperBehaviorSettings()

        assertTrue(settings.transitionsEnabled)
        assertEquals(AlwaysAppliedTarget.BOTH, settings.alwaysAppliedTarget)
    }

    @Test
    fun `always applied targets select the expected surfaces`() {
        assertTrue(AlwaysAppliedTarget.HOME.showsEffectOn(WallpaperSurface.HOME))
        assertFalse(AlwaysAppliedTarget.HOME.showsEffectOn(WallpaperSurface.LOCK))

        assertFalse(AlwaysAppliedTarget.LOCK.showsEffectOn(WallpaperSurface.HOME))
        assertTrue(AlwaysAppliedTarget.LOCK.showsEffectOn(WallpaperSurface.LOCK))

        assertTrue(AlwaysAppliedTarget.BOTH.showsEffectOn(WallpaperSurface.HOME))
        assertTrue(AlwaysAppliedTarget.BOTH.showsEffectOn(WallpaperSurface.LOCK))
    }

    @Test
    fun `separate engines resolve to their destination regardless of keyguard state`() {
        listOf(false, true).forEach { isKeyguardLocked ->
            assertEquals(
                WallpaperSurface.HOME,
                WallpaperBehaviorPolicy.resolveSurface(
                    isHomeEngine = true,
                    isLockEngine = false,
                    isKeyguardLocked = isKeyguardLocked
                )
            )
            assertEquals(
                WallpaperSurface.LOCK,
                WallpaperBehaviorPolicy.resolveSurface(
                    isHomeEngine = false,
                    isLockEngine = true,
                    isKeyguardLocked = isKeyguardLocked
                )
            )
        }
    }

    @Test
    fun `shared and unknown engines resolve from keyguard state`() {
        listOf(
            true to true,
            false to false
        ).forEach { (isHomeEngine, isLockEngine) ->
            assertEquals(
                WallpaperSurface.HOME,
                WallpaperBehaviorPolicy.resolveSurface(
                    isHomeEngine = isHomeEngine,
                    isLockEngine = isLockEngine,
                    isKeyguardLocked = false
                )
            )
            assertEquals(
                WallpaperSurface.LOCK,
                WallpaperBehaviorPolicy.resolveSurface(
                    isHomeEngine = isHomeEngine,
                    isLockEngine = isLockEngine,
                    isKeyguardLocked = true
                )
            )
        }
    }

    @Test
    fun `every effect exposes its original and applied endpoints`() {
        val expectedEndpoints = mapOf(
            "ORIGINAL" to EffectStateEndpoints(0f, 1f),
            "REVERSE" to EffectStateEndpoints(0f, 1f),
            "GLASS" to EffectStateEndpoints(0f, 1f),
            "GLASS_REVERSE" to EffectStateEndpoints(0f, 1f),
            "COLORFILL" to EffectStateEndpoints(0f, 1f),
            "COLORFILL_REVERSE" to EffectStateEndpoints(0f, 1f),
            "NEON" to EffectStateEndpoints(1f, 0f),
            "NEON_REVERSE" to EffectStateEndpoints(0f, 1f),
            "FROSTED" to EffectStateEndpoints(0f, 1f),
            "FROSTED_REVERSE" to EffectStateEndpoints(0f, 1f),
            "HALFTONE" to EffectStateEndpoints(0f, 1f),
            "HALFTONE_REVERSE" to EffectStateEndpoints(1f, 0f),
            "VHS" to EffectStateEndpoints(1f, 0f),
            "VHS_REVERSE" to EffectStateEndpoints(0f, 1f)
        )

        assertEquals(EFFECT_IDS, expectedEndpoints.keys)
        expectedEndpoints.forEach { (effectId, endpoints) ->
            assertEquals(effectId, endpoints, EffectStatePolicy.endpoints(effectId))
        }
    }

    @Test
    fun `fixed state policy covers every catalog effect`() {
        assertEquals(
            EffectCatalog.items.map { it.id },
            EFFECT_IDS.toList()
        )
    }

    @Test
    fun `unknown effects use the standard endpoint direction`() {
        val expected = EffectStateEndpoints(
            originalProgress = 0f,
            appliedProgress = 1f
        )

        assertEquals(expected, EffectStatePolicy.endpoints(null))
        assertEquals(expected, EffectStatePolicy.endpoints("NOT_AN_EFFECT"))
    }

    @Test
    fun `every effect maps lock to home progress in its renderer direction`() {
        TRANSITION_DIRECTIONS.forEach { (effectId, reverse) ->
            VALID_PROGRESS_VALUES.forEach { progress ->
                val expected = if (reverse) 1f - progress else progress
                assertEquals(
                    "$effectId at lock-to-home progress $progress",
                    expected,
                    EffectStatePolicy.transitionProgress(effectId, progress),
                    0f
                )
            }
        }
    }

    @Test
    fun `transition progress clamps finite values for every effect`() {
        TRANSITION_DIRECTIONS.forEach { (effectId, reverse) ->
            assertEquals(
                "$effectId below zero",
                if (reverse) 1f else 0f,
                EffectStatePolicy.transitionProgress(effectId, -0.5f),
                0f
            )
            assertEquals(
                "$effectId above one",
                if (reverse) 0f else 1f,
                EffectStatePolicy.transitionProgress(effectId, 1.5f),
                0f
            )
        }
    }

    @Test
    fun `non finite transition progress falls back to lock state for every effect`() {
        TRANSITION_DIRECTIONS.forEach { (effectId, reverse) ->
            listOf(
                Float.NaN,
                Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY
            ).forEach { invalidProgress ->
                assertEquals(
                    "$effectId at invalid progress $invalidProgress",
                    if (reverse) 1f else 0f,
                    EffectStatePolicy.transitionProgress(effectId, invalidProgress),
                    0f
                )
            }
        }
    }

    @Test
    fun `unknown effects use sanitized forward transition progress`() {
        assertEquals(0.25f, EffectStatePolicy.transitionProgress(null, 0.25f), 0f)
        assertEquals(
            0.75f,
            EffectStatePolicy.transitionProgress("NOT_AN_EFFECT", 0.75f),
            0f
        )
        assertEquals(0f, EffectStatePolicy.transitionProgress(null, Float.NaN), 0f)
        assertEquals(
            1f,
            EffectStatePolicy.transitionProgress("NOT_AN_EFFECT", 2f),
            0f
        )
    }

    private companion object {
        val TRANSITION_DIRECTIONS = linkedMapOf(
            "ORIGINAL" to false,
            "REVERSE" to true,
            "GLASS" to false,
            "GLASS_REVERSE" to true,
            "COLORFILL" to true,
            "COLORFILL_REVERSE" to false,
            "NEON" to false,
            "NEON_REVERSE" to false,
            "FROSTED" to false,
            "FROSTED_REVERSE" to true,
            "HALFTONE" to false,
            "HALFTONE_REVERSE" to false,
            "VHS" to false,
            "VHS_REVERSE" to false
        )
        val EFFECT_IDS = TRANSITION_DIRECTIONS.keys
        val VALID_PROGRESS_VALUES = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    }
}
