package com.app.nosatmosphereeffect.renderer.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VulkanCrashPolicyTest {

    private fun crash(at: Long) = VulkanCrashPolicy.Exit(nativeCrash = true, timestamp = at)
    private fun kill(at: Long) = VulkanCrashPolicy.Exit(nativeCrash = false, timestamp = at)

    @Test
    fun `a native crash while Vulkan ran blocks that effect`() {
        val decision = VulkanCrashPolicy.decide(listOf(crash(2_000)), 0, 1_000, "ORIGINAL", false)
        assertEquals("ORIGINAL", decision.blockEffect)
        assertEquals(2_000L, decision.checkedUntil)
    }

    @Test
    fun `an ordinary kill is not a driver fault`() {
        // Low-memory kills and battery managers end the process too.
        assertNull(VulkanCrashPolicy.decide(listOf(kill(2_000)), 0, 1_000, "ORIGINAL", false).blockEffect)
    }

    @Test
    fun `a crash from before Vulkan started is not blamed on it`() {
        assertNull(VulkanCrashPolicy.decide(listOf(crash(500)), 0, 1_000, "ORIGINAL", false).blockEffect)
    }

    @Test
    fun `a crash already dealt with is not counted twice`() {
        val decision = VulkanCrashPolicy.decide(listOf(crash(2_000)), 2_000, 1_000, "ORIGINAL", false)
        assertNull(decision.blockEffect)
        assertEquals(2_000L, decision.checkedUntil)
    }

    @Test
    fun `only the latest exit counts`() {
        // Crashed once, recovered on OpenGL, then was simply killed.
        val decision = VulkanCrashPolicy.decide(listOf(crash(2_000), kill(3_000)), 0, 1_000, "ORIGINAL", false)
        assertNull(decision.blockEffect)
        assertEquals(3_000L, decision.checkedUntil)
    }

    @Test
    fun `a crash during subject detection is left to its own guard`() {
        assertNull(VulkanCrashPolicy.decide(listOf(crash(2_000)), 0, 1_000, "ORIGINAL", true).blockEffect)
    }

    @Test
    fun `no Vulkan session means nothing to block`() {
        assertNull(VulkanCrashPolicy.decide(listOf(crash(2_000)), 0, 0, null, false).blockEffect)
    }
}
