package com.app.nosatmosphereeffect.renderer.vulkan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanFailurePolicyTest {
    @Test
    fun `a scoped driver failure blocks only that effect`() {
        assertTrue(
            VulkanFailurePolicy.isBlocked(
                effectId = "GLASS",
                currentFailureId = CURRENT,
                scopedFailureId = CURRENT,
                legacyFailureId = null
            )
        )
        assertFalse(
            VulkanFailurePolicy.isBlocked(
                effectId = "HALFTONE",
                currentFailureId = CURRENT,
                scopedFailureId = "another-build",
                legacyFailureId = null
            )
        )
    }

    @Test
    fun `the old global failure protects only color fill`() {
        assertTrue(
            VulkanFailurePolicy.isBlocked(
                effectId = "color_fill_reverse",
                currentFailureId = CURRENT,
                scopedFailureId = null,
                legacyFailureId = CURRENT
            )
        )
        assertFalse(
            VulkanFailurePolicy.isBlocked(
                effectId = "ATMOSPHERE",
                currentFailureId = CURRENT,
                scopedFailureId = null,
                legacyFailureId = CURRENT
            )
        )
    }

    @Test
    fun `obsolete atmosphere state failures are repaired for both directions`() {
        assertTrue(
            VulkanFailurePolicy.shouldClearObsoleteAtmosphereStateFailure(
                effectId = "ATMOSPHERE",
                currentFailureId = CURRENT,
                scopedFailureId = CURRENT,
                failureReason = "The Vulkan Atmosphere state could not be updated"
            )
        )
        assertTrue(
            VulkanFailurePolicy.shouldClearObsoleteAtmosphereStateFailure(
                effectId = "atmosphere_reverse",
                currentFailureId = CURRENT,
                scopedFailureId = CURRENT,
                failureReason = "The Vulkan Reverse Atmosphere state could not be updated"
            )
        )
    }

    @Test
    fun `repair preserves unrelated current and stale failures`() {
        assertFalse(
            VulkanFailurePolicy.shouldClearObsoleteAtmosphereStateFailure(
                effectId = "ATMOSPHERE",
                currentFailureId = CURRENT,
                scopedFailureId = CURRENT,
                failureReason = "The wallpaper texture could not be uploaded to Vulkan"
            )
        )
        assertFalse(
            VulkanFailurePolicy.shouldClearObsoleteAtmosphereStateFailure(
                effectId = "GLASS",
                currentFailureId = CURRENT,
                scopedFailureId = CURRENT,
                failureReason = "The Vulkan Atmosphere state could not be updated"
            )
        )
        assertFalse(
            VulkanFailurePolicy.shouldClearObsoleteAtmosphereStateFailure(
                effectId = "ATMOSPHERE_REVERSE",
                currentFailureId = CURRENT,
                scopedFailureId = "another-build",
                failureReason = "The Vulkan Reverse Atmosphere state could not be updated"
            )
        )
    }

    private companion object {
        const val CURRENT = "device-and-version"
    }
}
