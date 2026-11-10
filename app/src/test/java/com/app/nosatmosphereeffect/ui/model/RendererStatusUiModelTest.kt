package com.app.nosatmosphereeffect.ui.model

import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimePhase
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatus
import com.app.nosatmosphereeffect.renderer.status.VulkanDeviceCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererStatusUiModelTest {

    @Test
    fun vulkanStateKeepsTrimmedVersion() {
        val status = RendererStatusUiModel.vulkanActive(" 1.3 ")

        assertTrue(status.isVulkanActive)
        assertEquals(RendererBackendUi.VULKAN, status.backend)
        assertEquals("1.3", status.vulkanVersion)
    }

    @Test
    fun missingVersionNeverClaimsVulkanIsActive() {
        listOf(null, "", "   ").forEach { version ->
            val status = RendererStatusUiModel.vulkanActive(version)

            assertFalse(status.isVulkanActive)
            assertEquals(RendererBackendUi.OPENGL_ES, status.backend)
            assertNull(status.vulkanVersion)
        }
    }

    @Test
    fun explicitOpenGlStateContainsNoVulkanVersion() {
        val status = RendererStatusUiModel.openGlActive()

        assertFalse(status.isVulkanActive)
        assertEquals(RendererBackendUi.OPENGL_ES, status.backend)
        assertNull(status.vulkanVersion)
        assertNull(status.fallbackReason)
    }

    @Test
    fun anActualVulkanFallbackRetainsItsNormalizedReason() {
        val status = RendererStatusUiModel.openGlActive("  Swapchain failed  ")

        assertEquals(RendererBackendUi.OPENGL_ES, status.backend)
        assertEquals("Swapchain failed", status.fallbackReason)
    }

    @Test
    fun contradictoryBackendStatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RendererStatusUiModel(RendererBackendUi.VULKAN, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RendererStatusUiModel(RendererBackendUi.OPENGL_ES, "1.3")
        }
    }

    @Test
    fun anyActiveEffectShowsTheVulkanVersionThatActuallyPresented() {
        val status = runtimeStatus(
            effectId = "GLASS",
            activeBackend = GraphicsBackend.VULKAN,
            activeVulkanVersion = 0x00404000
        )

        val ui = rendererStatusUiModel(
            wallpaperActive = true,
            activeEffectId = "GLASS",
            runtimeStatus = status
        )

        assertEquals(RendererBackendUi.VULKAN, ui?.backend)
        assertEquals("1.4", ui?.vulkanVersion)
    }

    @Test
    fun initializingEffectDoesNotClaimThatVulkanIsActive() {
        val status = runtimeStatus(
            effectId = "HALFTONE",
            phase = RendererRuntimePhase.INITIALIZING,
            activeBackend = null,
            activeVulkanVersion = null
        )

        assertNull(
            rendererStatusUiModel(
                wallpaperActive = true,
                activeEffectId = "HALFTONE",
                runtimeStatus = status
            )
        )
    }

    @Test
    fun anyEffectCanReportAnActiveOpenGlRenderer() {
        val fallback = runtimeStatus(
            effectId = "COLOR_FILL",
            activeBackend = GraphicsBackend.OPENGL_ES,
            activeVulkanVersion = null,
            fallbackReason = "Vulkan presentation failed"
        )
        val glass = runtimeStatus(
            effectId = "GLASS",
            selectedBackend = GraphicsBackend.OPENGL_ES,
            activeBackend = GraphicsBackend.OPENGL_ES,
            activeVulkanVersion = null
        )

        val colorFillUi = rendererStatusUiModel(
            wallpaperActive = true,
            activeEffectId = "COLOR_FILL",
            runtimeStatus = fallback
        )
        val glassUi = rendererStatusUiModel(
            wallpaperActive = true,
            activeEffectId = "GLASS",
            runtimeStatus = glass
        )

        assertEquals(RendererBackendUi.OPENGL_ES, colorFillUi?.backend)
        assertEquals("Vulkan presentation failed", colorFillUi?.fallbackReason)
        assertEquals(RendererBackendUi.OPENGL_ES, glassUi?.backend)
        assertNull(glassUi?.fallbackReason)
    }

    @Test
    fun inactiveWallpaperAndStaleEffectStatusStayHidden() {
        val activeStatus = runtimeStatus(
            activeBackend = GraphicsBackend.VULKAN,
            activeVulkanVersion = 0x00403000
        )

        assertNull(
            rendererStatusUiModel(
                wallpaperActive = false,
                activeEffectId = null,
                runtimeStatus = activeStatus
            )
        )
        assertNull(
            rendererStatusUiModel(
                wallpaperActive = true,
                activeEffectId = "COLOR_FILL_REVERSE",
                runtimeStatus = activeStatus
            )
        )
    }

    private fun runtimeStatus(
        effectId: String = "COLOR_FILL",
        phase: RendererRuntimePhase = RendererRuntimePhase.ACTIVE,
        selectedBackend: GraphicsBackend = GraphicsBackend.VULKAN,
        activeBackend: GraphicsBackend?,
        activeVulkanVersion: Int?,
        fallbackReason: String? = null
    ): RendererRuntimeStatus {
        return RendererRuntimeStatus(
            phase = phase,
            effectId = effectId,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = 0x00404000,
            selectedBackend = selectedBackend,
            activeBackend = activeBackend,
            activeVulkanApiVersion = activeVulkanVersion,
            fallbackReason = fallbackReason,
            updatedAtEpochMillis = 1L
        )
    }
}
