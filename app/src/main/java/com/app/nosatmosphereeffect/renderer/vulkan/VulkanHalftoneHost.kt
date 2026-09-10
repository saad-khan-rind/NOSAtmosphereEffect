package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import com.app.nosatmosphereeffect.helper.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.helper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.HalftoneRenderState
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageBridge
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageHost

internal class VulkanHalftoneHost(
    context: Context,
    private val reverse: Boolean,
    initialState: HalftoneRenderState,
    onFatalFailure: (WallpaperRenderHost, String) -> Unit,
    onVulkanActive: (WallpaperRenderHost, Int) -> Unit,
    previewSource: (() -> Bitmap?)? = null
) : VulkanSingleImageHost<HalftoneRenderState>(
    context = context,
    threadName = "AtmoVulkanHalftone",
    initialState = initialState.sanitized(),
    bridge = HalftoneBridge(reverse),
    onFatalFailure = onFatalFailure,
    onVulkanActive = onVulkanActive,
    previewSource = previewSource
) {
    private val subjectMasks = SubjectMaskCoordinator(context, ::requestRender)

    /**
     * The wallpaper clock. Shares Halftone's render state rather than being
     * configured separately, so a progress tick and a clock change take the
     * same path into the shader.
     */
    private val clockOverlay = VulkanClockOverlay(appContext, "Halftone")

    /** Plays the clock's entry animation on the next prepared frame. */
    fun beginClockEntry() {
        clockOverlay.beginEntry()
        requestRender()
    }

    /** Re-reads the system 12/24-hour setting and forces a redraw. */
    fun onTimeChanged() {
        clockOverlay.onTimeChanged()
        requestRender()
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
            effectiveOpacity = current.clock.effectiveOpacity(current.progress),
            upload = { bitmap -> VulkanHalftoneNative.nativeUploadClock(handle, bitmap) },
            requestRender = ::requestRender
        )
        if (!changed) return
        updateEffectState { state ->
            state.copy(
                clock = state.clock.copy(
                    textureAspect = clockOverlay.aspectRatio,
                    faceUploaded = true
                )
            ).sanitized()
        }
    }

    init {
        val safe = initialState.sanitized()
        subjectMasks.configure(
            safe.backgroundOnly || safe.clock.needsSubjectMask()
        )
        clockOverlay.applyState(safe.clock)
        startNativeEngine()
    }

    fun updateState(state: HalftoneRenderState) {
        val sanitized = state.sanitized()
        // Either Halftone's background-only mode or the clock's depth effect
        // can call for a subject mask, independently of the other.
        val maskWanted =
            sanitized.backgroundOnly || sanitized.clock.needsSubjectMask()
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
                clock = sanitized.clock.copy(
                    textureAspect = if (clockOverlay.hasUploadedFace) {
                        clockOverlay.aspectRatio
                    } else {
                        current.clock.textureAspect
                    },
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
        if (!VulkanHalftoneNative.nativeClearSubjectMask(handle)) {
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
            if (!VulkanHalftoneNative.nativeUploadSubjectMask(handle, pending.bitmap)) {
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

private class HalftoneBridge(
    private val reverse: Boolean
) : VulkanSingleImageBridge<HalftoneRenderState> {
    override val effectLabel = "Halftone"

    override fun create(assets: android.content.res.AssetManager): Long {
        if (!VulkanHalftoneNative.libraryLoaded) return 0L
        return VulkanHalftoneNative.nativeCreate(assets, reverse)
    }

    override fun setSurface(
        handle: Long,
        surface: android.view.Surface,
        width: Int,
        height: Int
    ): Boolean {
        return VulkanHalftoneNative.nativeSetSurface(
            handle,
            surface,
            width,
            height
        )
    }

    override fun getApiVersion(handle: Long): Int {
        return VulkanHalftoneNative.nativeGetApiVersion(handle)
    }

    override fun uploadWallpaper(handle: Long, bitmap: Bitmap): Boolean {
        return VulkanHalftoneNative.nativeUploadWallpaper(handle, bitmap)
    }

    override fun setState(
        handle: Long,
        state: HalftoneRenderState,
        scrollOffsetX: Float,
        scrollWindowX: Float
    ) {
        val safe = state.sanitized()
        VulkanHalftoneNative.nativeSetState(
            handle = handle,
            progress = safe.progress,
            dimLevel = safe.dimLevel,
            dotSize = safe.dotSize,
            grayscale = safe.grayscale,
            backgroundOnly = safe.backgroundOnly,
            hasSubject = safe.hasSubject,
            scrollOffsetX = scrollOffsetX,
            scrollWindowX = scrollWindowX,
            clockCenterX = safe.clock.centerX,
            clockTop = safe.clock.top,
            // Per-axis stretch is folded into these two numbers rather
            // than passed separately — see ClockOverlayState.renderHeight.
            clockHeightFraction = safe.clock.renderHeight,
            clockTextureAspect =
                safe.clock.renderTextureAspect(safe.clock.textureAspect),
            // The lock/home fade is folded in here, once, so the shader has no
            // policy in it and both backends share one curve.
            clockOpacity = safe.clock.effectiveOpacity(safe.progress),
            clockUploaded = safe.clock.faceUploaded,
            clockDepth = safe.clock.depthEnabled
        )
    }

    override fun render(handle: Long): Int {
        return VulkanHalftoneNative.nativeRender(handle)
    }

    override fun destroySurface(handle: Long) {
        VulkanHalftoneNative.nativeDestroySurface(handle)
    }

    override fun destroy(handle: Long) {
        VulkanHalftoneNative.nativeDestroy(handle)
    }
}
