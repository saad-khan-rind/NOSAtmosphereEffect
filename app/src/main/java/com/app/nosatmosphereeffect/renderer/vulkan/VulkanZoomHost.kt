package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import com.app.nosatmosphereeffect.subject.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.transition.WakeTransition
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.zoom.ZoomRenderState
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageBridge
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageHost

internal class VulkanZoomHost(
    context: Context,
    private val reverse: Boolean,
    initialState: ZoomRenderState,
    onFatalFailure: (WallpaperRenderHost, String) -> Unit,
    onVulkanActive: (WallpaperRenderHost, Int) -> Unit,
    previewSource: (() -> Bitmap?)? = null
) : VulkanSingleImageHost<ZoomRenderState>(
    context = context,
    threadName = "AtmoVulkanZoom",
    initialState = initialState.sanitized(),
    bridge = ZoomBridge(effectFirst = reverse),
    onFatalFailure = onFatalFailure,
    onVulkanActive = onVulkanActive,
    previewSource = previewSource
) {
    private val subjectMasks = SubjectMaskCoordinator(context, ::requestRender)

    /**
     * The wallpaper clock. Shares the effect's render state rather than being
     * configured separately, so a progress tick and a clock change take the
     * same path into the shader.
     */
    private val clockOverlay = VulkanClockOverlay(appContext, "Zoom")

    init {
        // The Adaptive clock fits itself around the subject this renderer's
        // own segmentation finds, in the image it actually draws.
        subjectMasks.sceneSink = clockOverlay.sceneSink
    }


    /** Plays the clock's entry animation on the next prepared frame. */
    override fun beginClockEntry() {
        clockOverlay.beginEntry()
        // A clock that is off costs nothing: no frame for it, ever.
        if (clockOverlay.state.enabled) requestRender()
    }

    /** Re-reads the system 12/24-hour setting and forces a redraw. */
    override fun onTimeChanged() {
        clockOverlay.onTimeChanged()
        if (clockOverlay.state.enabled) requestRender()
    }

    /**
     * Uploads a clock face when there is one to upload.
     *
     * The freshly uploaded aspect and "a face exists" flag are read from the
     * overlay's own fields inside the update lambda, never carried forward
     * from a snapshot taken before it. This runs on the render worker while
     * updateState runs on whichever thread changed a preference, and a
     * snapshot read outside the lambda would silently lose whichever of the
     * two landed second.
     */
    private fun uploadClockOnWorker(handle: Long) {
        val current = currentEffectState()
        val changed = clockOverlay.uploadIfNeeded(
            scrollOffsetX = wallpaperScrollOffsetX,
            scrollWindowX = wallpaperScrollWindowX,
            surfaceAspect = surfaceAspect,
            effectiveOpacity = current.clock.effectiveOpacity(current.progress),
            upload = { bitmap -> VulkanZoomNative.nativeUploadClock(handle, bitmap) },
            requestRender = ::requestRender
        )
        if (!changed) return
        updateEffectState { state ->
            state.copy(
                clock = clockOverlay.withFaceMetrics(state.clock).copy(
                    faceUploaded = true
                )
            ).sanitized()
        }
    }

    init {
        val safe = initialState.sanitized()
        subjectMasks.configure(safe.clock.needsSubjectMask())
        clockOverlay.applyState(safe.clock)
        startNativeEngine()
    }

    fun updateState(state: ZoomRenderState) {
        val sanitized = state.sanitized()
        // Only the clock's depth effect calls for a subject mask.
        val maskWanted = sanitized.clock.needsSubjectMask()
        val backgroundModeChanged = subjectMasks.configure(maskWanted)
        clockOverlay.applyState(sanitized.clock)
        updateEffectState { current ->
            sanitized.copy(
                hasSubject = if (backgroundModeChanged) {
                    false
                } else {
                    current.hasSubject && maskWanted
                },
                // Read from the overlay's own fields, never carried forward
                // from a snapshot — see uploadClockOnWorker.
                clock = clockOverlay.withFaceMetrics(sanitized.clock, current.clock).copy(
                    faceUploaded = clockOverlay.hasUploadedFace &&
                        sanitized.clock.enabled
                )
            ).sanitized()
        }
        if (maskWanted && backgroundModeChanged) {
            reloadTexture()
        }
    }

    override fun onWallpaperUploadedOnWorker(
        handle: Long,
        bitmap: Bitmap,
        textureGeneration: Long
    ): Boolean {
        updateEffectState { current ->
            current.copy(hasSubject = false).sanitized()
        }
        if (!VulkanZoomNative.nativeClearSubjectMask(handle)) {
            return false
        }
        subjectMasks.request(bitmap, textureGeneration)
        return true
    }

    override fun prepareFrameOnWorker(
        handle: Long,
        textureGeneration: Long
    ): Boolean {
        uploadClockOnWorker(handle)
        val pending = subjectMasks.takePending() ?: return true
        try {
            if (
                pending.generation != textureGeneration ||
                !subjectMasks.enabled
            ) {
                return true
            }
            if (!VulkanZoomNative.nativeUploadSubjectMask(handle, pending.bitmap)) {
                return false
            }
            updateEffectState { current ->
                current.copy(hasSubject = true).sanitized()
            }
            return true
        } finally {
            if (!pending.bitmap.isRecycled) pending.bitmap.recycle()
        }
    }

    override fun onSurfaceResetOnWorker() {
        subjectMasks.discardPending()
        // The new surface's descriptor set has no clock content yet.
        clockOverlay.reset()
        updateEffectState { current ->
            current.copy(
                hasSubject = false,
                clock = current.clock.copy(faceUploaded = false)
            ).sanitized()
        }
    }

    override fun onEffectResourcesReleased() {
        subjectMasks.close()
        clockOverlay.release()
    }
}

private class ZoomBridge(
    /** The effect shows at the start of the transition rather than the end. */
    private val effectFirst: Boolean
) : VulkanSingleImageBridge<ZoomRenderState> {
    override val effectLabel = "Zoom"

    override fun create(assets: android.content.res.AssetManager): Long {
        if (!VulkanZoomNative.libraryLoaded) return 0L
        return VulkanZoomNative.nativeCreate(assets, effectFirst)
    }

    override fun setSurface(
        handle: Long,
        surface: android.view.Surface,
        width: Int,
        height: Int
    ): Boolean {
        return VulkanZoomNative.nativeSetSurface(
            handle,
            surface,
            width,
            height
        )
    }

    override fun getApiVersion(handle: Long): Int {
        return VulkanZoomNative.nativeGetApiVersion(handle)
    }

    override fun uploadWallpaper(handle: Long, bitmap: Bitmap): Boolean {
        return VulkanZoomNative.nativeUploadWallpaper(handle, bitmap)
    }

    override fun setState(
        handle: Long,
        state: ZoomRenderState,
        scrollOffsetX: Float,
        scrollWindowX: Float
    ) {
        val safe = state.sanitized()
        VulkanZoomNative.nativeSetState(
            handle = handle,
            progress = safe.progress,
            dimLevel = safe.dimLevel,
            hasSubject = safe.hasSubject,
            scrollOffsetX = scrollOffsetX,
            scrollWindowX = scrollWindowX,
            clockCenterX = safe.clock.centerX,
            clockTop = safe.clock.renderTop,
            // Per-axis stretch is folded into these two numbers rather
            // than passed separately — see ClockOverlayState.renderHeight.
            clockHeightFraction = safe.clock.renderHeight,
            clockTextureAspect =
                safe.clock.renderTextureAspect(safe.clock.textureAspect),
            // The lock/home fade is folded in here, once, so the shader has no
            // policy in it and both backends share one curve.
            clockOpacity = safe.clock.effectiveOpacity(safe.progress),
            clockUploaded = safe.clock.faceUploaded,
            clockDepth = safe.clock.depthEnabled,
            clockGlass = safe.clock.glassMeta,
            wallpaperZoom = WakeTransition.zoom(safe.clock.wallpaperZoom) *
                ZoomRenderState.photoZoom(effectFirst, safe.progress)
        )
    }

    override fun render(handle: Long): Int {
        return VulkanZoomNative.nativeRender(handle)
    }

    override fun destroySurface(handle: Long) {
        VulkanZoomNative.nativeDestroySurface(handle)
    }

    override fun destroy(handle: Long) {
        VulkanZoomNative.nativeDestroy(handle)
    }
}
