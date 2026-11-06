package com.app.nosatmosphereeffect.renderer.status

import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererRuntimeStatusRepositoryTest {
    @Test
    fun everyWallpaperEffectCanPublishEitherRuntimeBackend() {
        ALL_EFFECT_IDS.forEach { effectId ->
            val openGlTracker = tracker()
            val openGlSession = openGlTracker.recordSelection(
                effectId = effectId,
                selectedBackend = GraphicsBackend.OPENGL_ES,
                vulkanCapability = VulkanDeviceCapability.UNKNOWN,
                probedVulkanApiVersion = null
            )
            val openGl = openGlTracker.recordOpenGlActive(openGlSession)

            assertEquals(effectId, openGl.effectId)
            assertEquals(GraphicsBackend.OPENGL_ES, openGl.activeBackend)
            assertFalse(openGl.isVulkanActive)

            val vulkanTracker = tracker()
            val vulkanSession = vulkanTracker.recordSelection(
                effectId = effectId,
                selectedBackend = GraphicsBackend.VULKAN,
                vulkanCapability = VulkanDeviceCapability.SUPPORTED,
                probedVulkanApiVersion = VULKAN_1_3
            )
            vulkanTracker.recordVulkanInitializing(vulkanSession)
            val vulkan = vulkanTracker.recordVulkanActive(vulkanSession, VULKAN_1_2)

            assertEquals(effectId, vulkan.effectId)
            assertEquals(GraphicsBackend.VULKAN, vulkan.activeBackend)
            assertEquals(VULKAN_1_2, vulkan.activeVulkanApiVersion)
            assertTrue(vulkan.isVulkanActive)
        }
    }

    @Test
    fun selectionAndInitializationNeverClaimVulkanIsActive() {
        val tracker = tracker()

        val session = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        val selected = tracker.currentStatus()
        val initializing = tracker.recordVulkanInitializing(session)

        assertEquals(RendererRuntimePhase.SELECTED, selected.phase)
        assertEquals(GraphicsBackend.VULKAN, selected.selectedBackend)
        assertNull(selected.activeBackend)
        assertFalse(selected.isVulkanActive)
        assertEquals(RendererRuntimePhase.INITIALIZING, initializing.phase)
        assertNull(initializing.activeBackend)
        assertFalse(initializing.isVulkanActive)
    }

    @Test
    fun firstSuccessfulPresentCanPublishTheActiveVulkanVersion() {
        val tracker = tracker()
        val session = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        tracker.recordVulkanInitializing(session)

        val active = tracker.recordVulkanActive(session, VULKAN_1_2)

        assertEquals(RendererRuntimePhase.ACTIVE, active.phase)
        assertEquals(GraphicsBackend.VULKAN, active.activeBackend)
        assertEquals(VULKAN_1_2, active.activeVulkanApiVersion)
        assertEquals(VULKAN_1_3, active.probedVulkanApiVersion)
        assertTrue(active.isVulkanActive)
    }

    @Test
    fun openGlFallbackRetainsSelectionAndReasonButClearsVulkanVersion() {
        val tracker = tracker()
        val session = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        tracker.recordVulkanInitializing(session)

        val fallback = tracker.recordOpenGlActive(
            session = session,
            reason = "  Swapchain creation failed  "
        )

        assertEquals(RendererRuntimePhase.ACTIVE, fallback.phase)
        assertEquals(GraphicsBackend.VULKAN, fallback.selectedBackend)
        assertEquals(GraphicsBackend.OPENGL_ES, fallback.activeBackend)
        assertNull(fallback.activeVulkanApiVersion)
        assertEquals("Swapchain creation failed", fallback.fallbackReason)
        assertFalse(fallback.isVulkanActive)
    }

    @Test
    fun anOpenGlSelectionCanDescribeAnUnsupportedDeviceWithoutPretendingItIsActive() {
        val tracker = tracker()

        tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.OPENGL_ES,
            vulkanCapability = VulkanDeviceCapability.UNSUPPORTED,
            probedVulkanApiVersion = null,
            fallbackReason = "Vulkan 1.1 is not advertised by this device"
        )
        val selected = tracker.currentStatus()

        assertEquals(VulkanDeviceCapability.UNSUPPORTED, selected.vulkanCapability)
        assertEquals(GraphicsBackend.OPENGL_ES, selected.selectedBackend)
        assertNull(selected.activeBackend)
        assertFalse(selected.isVulkanActive)
    }

    @Test
    fun persistedActiveStateNeedsConfirmationFromTheNewProcess() {
        val store = FakeStore()
        val firstProcess = tracker(store = store, processSessionId = "process-a")
        val firstSession = firstProcess.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        firstProcess.recordVulkanActive(firstSession, VULKAN_1_2)

        val secondProcess = tracker(store = store, processSessionId = "process-b")
        val restored = secondProcess.currentStatus()

        assertEquals(RendererRuntimePhase.AWAITING_CONFIRMATION, restored.phase)
        assertEquals(GraphicsBackend.VULKAN, restored.selectedBackend)
        assertNull(restored.activeBackend)
        assertNull(restored.activeVulkanApiVersion)
        assertFalse(restored.isVulkanActive)

        val secondSession = secondProcess.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        val confirmed = secondProcess.recordVulkanActive(secondSession, VULKAN_1_2)
        assertTrue(confirmed.isVulkanActive)
    }

    @Test
    fun listenersReceiveTheSnapshotImmediatelyAndEveryAcceptedTransition() {
        val tracker = tracker()
        val observed = mutableListOf<RendererRuntimeStatus>()
        val listener = RendererRuntimeStatusListener(observed::add)

        tracker.addListener(listener)
        val session = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.OPENGL_ES,
            vulkanCapability = VulkanDeviceCapability.UNSUPPORTED,
            probedVulkanApiVersion = null,
            fallbackReason = "No compatible Vulkan driver"
        )
        tracker.recordOpenGlActive(session, "No compatible Vulkan driver")
        tracker.removeListener(listener)
        tracker.recordReleased(session)

        assertEquals(
            listOf(
                RendererRuntimePhase.IDLE,
                RendererRuntimePhase.SELECTED,
                RendererRuntimePhase.ACTIVE
            ),
            observed.map(RendererRuntimeStatus::phase)
        )
    }

    @Test
    fun aLateCallbackFromAnotherEffectCannotReplaceTheCurrentStatus() {
        val tracker = tracker()
        tracker.recordSelection(
            effectId = "GLASS",
            selectedBackend = GraphicsBackend.OPENGL_ES,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        val glassSelection = tracker.currentStatus()
        val colorFillSession = RendererRuntimeSession("unregistered")

        val result = tracker.recordVulkanActive(colorFillSession, VULKAN_1_2)

        assertEquals(glassSelection, result)
        assertEquals("GLASS", tracker.currentStatus().effectId)
        assertNull(tracker.currentStatus().activeBackend)
    }

    @Test
    fun releasingAnOldEffectCannotClearTheNewRenderer() {
        val tracker = tracker()
        val glassSession = tracker.recordSelection(
            effectId = "GLASS",
            selectedBackend = GraphicsBackend.OPENGL_ES,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        tracker.recordOpenGlActive(glassSession)

        val result = tracker.recordReleased(RendererRuntimeSession("unregistered"))

        assertEquals(RendererRuntimePhase.ACTIVE, result.phase)
        assertEquals("GLASS", result.effectId)
        assertEquals(GraphicsBackend.OPENGL_ES, result.activeBackend)
    }

    @Test
    fun releasingOneOfTwoEnginesForTheSameEffectKeepsTheOtherActive() {
        val tracker = tracker()
        val sessions = List(2) {
            tracker.recordSelection(
                effectId = COLOR_FILL,
                selectedBackend = GraphicsBackend.VULKAN,
                vulkanCapability = VulkanDeviceCapability.SUPPORTED,
                probedVulkanApiVersion = VULKAN_1_3
            )
        }
        tracker.recordVulkanActive(sessions.first(), VULKAN_1_2)

        val afterFirstRelease = tracker.recordReleased(sessions.last())
        val afterFinalRelease = tracker.recordReleased(sessions.first())

        assertEquals(RendererRuntimePhase.ACTIVE, afterFirstRelease.phase)
        assertTrue(afterFirstRelease.isVulkanActive)
        assertEquals(RendererRuntimePhase.RELEASED, afterFinalRelease.phase)
        assertNull(afterFinalRelease.activeBackend)
        assertFalse(afterFinalRelease.isVulkanActive)
    }

    @Test
    fun openGlCallbackCannotOverwriteAnotherLiveVulkanEngineForTheSameEffect() {
        val tracker = tracker()
        val vulkanSession = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        val fallbackSession = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        tracker.recordVulkanActive(vulkanSession, VULKAN_1_2)

        val aggregate = tracker.recordOpenGlActive(
            fallbackSession,
            "The second engine failed"
        )

        assertTrue(aggregate.isVulkanActive)
        assertEquals(GraphicsBackend.VULKAN, aggregate.activeBackend)
        assertNull(aggregate.fallbackReason)
    }

    @Test
    fun releasingVulkanEngineRevealsRemainingOpenGlFallback() {
        val tracker = tracker()
        val vulkanSession = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        val fallbackSession = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        tracker.recordVulkanActive(vulkanSession, VULKAN_1_2)
        tracker.recordOpenGlActive(fallbackSession, "Swapchain creation failed")

        val aggregate = tracker.recordReleased(vulkanSession)

        assertEquals(RendererRuntimePhase.ACTIVE, aggregate.phase)
        assertEquals(GraphicsBackend.OPENGL_ES, aggregate.activeBackend)
        assertEquals("Swapchain creation failed", aggregate.fallbackReason)
        assertFalse(aggregate.isVulkanActive)
    }

    @Test
    fun unresolvedVulkanEnginePreventsPrematureOpenGlClaim() {
        val tracker = tracker()
        val openGlSession = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.OPENGL_ES,
            vulkanCapability = VulkanDeviceCapability.UNSUPPORTED,
            probedVulkanApiVersion = null
        )
        tracker.recordOpenGlActive(openGlSession)
        val vulkanSession = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )

        val initializing = tracker.recordVulkanInitializing(vulkanSession)

        assertEquals(RendererRuntimePhase.INITIALIZING, initializing.phase)
        assertNull(initializing.activeBackend)
        assertFalse(initializing.isVulkanActive)
    }

    @Test
    fun callbacksAndDuplicateReleaseForAClosedSessionAreIgnored() {
        val tracker = tracker()
        val session = tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.VULKAN,
            vulkanCapability = VulkanDeviceCapability.SUPPORTED,
            probedVulkanApiVersion = VULKAN_1_3
        )
        val released = tracker.recordReleased(session)

        assertEquals(released, tracker.recordVulkanActive(session, VULKAN_1_2))
        assertEquals(released, tracker.recordOpenGlActive(session))
        assertEquals(released, tracker.recordReleased(session))
    }

    @Test
    fun invalidVulkanSelectionAndVersionAreRejected() {
        val tracker = tracker()

        expectIllegalArgument {
            tracker.recordSelection(
                effectId = COLOR_FILL,
                selectedBackend = GraphicsBackend.VULKAN,
                vulkanCapability = VulkanDeviceCapability.UNKNOWN,
                probedVulkanApiVersion = null
            )
        }
        expectIllegalArgument {
            val session = tracker.recordSelection(
                effectId = COLOR_FILL,
                selectedBackend = GraphicsBackend.VULKAN,
                vulkanCapability = VulkanDeviceCapability.SUPPORTED,
                probedVulkanApiVersion = VULKAN_1_3
            )
            tracker.recordVulkanActive(session, 0)
        }
    }

    @Test
    fun persistenceAndListenerFailuresDoNotCrashTheRenderer() {
        var reportedFailures = 0
        val tracker = RendererRuntimeStatusTracker(
            store = object : RendererRuntimeStatusStore {
                override fun load(): StoredRendererRuntimeStatus? = null

                override fun save(storedStatus: StoredRendererRuntimeStatus) {
                    error("disk full")
                }
            },
            processSessionId = "process-a",
            clock = { 42L },
            onError = { reportedFailures++ }
        )
        tracker.addListener { error("detached screen") }

        tracker.recordSelection(
            effectId = COLOR_FILL,
            selectedBackend = GraphicsBackend.OPENGL_ES,
            vulkanCapability = VulkanDeviceCapability.UNSUPPORTED,
            probedVulkanApiVersion = null
        )
        val status = tracker.currentStatus()

        assertEquals(RendererRuntimePhase.SELECTED, status.phase)
        assertEquals(3, reportedFailures)
    }

    private fun tracker(
        store: RendererRuntimeStatusStore = FakeStore(),
        processSessionId: String = "process-a"
    ): RendererRuntimeStatusTracker {
        var now = 100L
        return RendererRuntimeStatusTracker(
            store = store,
            processSessionId = processSessionId,
            clock = { now++ }
        )
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }

    private class FakeStore : RendererRuntimeStatusStore {
        var storedStatus: StoredRendererRuntimeStatus? = null

        override fun load(): StoredRendererRuntimeStatus? = storedStatus

        override fun save(storedStatus: StoredRendererRuntimeStatus) {
            this.storedStatus = storedStatus
        }
    }

    private companion object {
        const val COLOR_FILL = "COLOR_FILL"
        const val VULKAN_1_2 = 0x00402000
        const val VULKAN_1_3 = 0x00403000
        val ALL_EFFECT_IDS = listOf(
            "ATMOSPHERE",
            "ATMOSPHERE_REVERSE",
            "GLASS",
            "GLASS_REVERSE",
            "COLOR_FILL",
            "COLOR_FILL_REVERSE",
            "CANVAS_SKETCH",
            "CANVAS_SKETCH_REVERSE",
            "FROSTED",
            "FROSTED_REVERSE",
            "HALFTONE",
            "HALFTONE_REVERSE"
        )
    }
}
