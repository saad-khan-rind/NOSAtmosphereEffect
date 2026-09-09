package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.helper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.GlassRenderState
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageHost

internal class VulkanGlassHost(
    context: Context,
    initialState: GlassRenderState,
    onFatalFailure: (VulkanGlassHost, String) -> Unit,
    onVulkanActive: (VulkanGlassHost, Int) -> Unit,
    previewSource: (() -> Bitmap?)? = null
) : VulkanSingleImageHost<GlassRenderState>(
    context = context,
    threadName = "AtmoVulkanGlass",
    initialState = initialState.sanitized(),
    bridge = VulkanGlassBridge,
    onFatalFailure = { host: WallpaperRenderHost, reason: String ->
        onFatalFailure(host as VulkanGlassHost, reason)
    },
    onVulkanActive = { host: WallpaperRenderHost, version: Int ->
        onVulkanActive(host as VulkanGlassHost, version)
    },
    previewSource = previewSource
) {
    private val subjectMasks = SubjectMaskCoordinator(appContext) {
        requestRender()
    }

    /**
     * The wallpaper clock. Shares Glass's render state rather than being
     * configured separately, so a progress tick and a clock change take the
     * same path into the shader.
     */
    private val clockOverlay = VulkanClockOverlay(appContext, "Glass")

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
            upload = { bitmap -> VulkanGlassNative.nativeUploadClock(handle, bitmap) },
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

    fun updateState(state: GlassRenderState) {
        val safe = state.sanitized()
        // Either Glass's background-only mode or the clock's depth effect can
        // call for a subject mask, independently of the other.
        val maskWanted = safe.backgroundOnly || safe.clock.needsSubjectMask()
        val backgroundChanged = subjectMasks.configure(maskWanted)
        clockOverlay.applyState(safe.clock)
        updateEffectState { current ->
            safe.copy(
                hasSubject = if (maskWanted) {
                    current.hasSubject
                } else {
                    false
                },
                // Read from the overlay's own fields, never carried forward
                // from a snapshot — see uploadClockOnWorker.
                clock = safe.clock.copy(
                    textureAspect = if (clockOverlay.hasUploadedFace) {
                        clockOverlay.aspectRatio
                    } else {
                        current.clock.textureAspect
                    },
                    faceUploaded = clockOverlay.hasUploadedFace &&
                        safe.clock.enabled
                )
            )
        }
        if (backgroundChanged && maskWanted) {
            reloadTexture()
        }
    }

    override fun onWallpaperUploadedOnWorker(
        handle: Long,
        bitmap: Bitmap,
        textureGeneration: Long
    ): Boolean {
        updateEffectState { it.copy(hasSubject = false) }
        if (!VulkanGlassNative.nativeClearMask(handle)) {
            return false
        }
        if (subjectMasks.enabled) {
            runCatching {
                subjectMasks.request(bitmap, textureGeneration)
            }.onFailure { failure ->
                Log.w(TAG, "Unable to request a Vulkan Glass subject mask", failure)
            }
        }
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
            val uploaded = VulkanGlassNative.nativeUploadMask(
                handle,
                pending.bitmap
            )
            updateEffectState {
                it.copy(hasSubject = uploaded)
            }
            return uploaded
        } finally {
            pending.bitmap.recycleSafely()
        }
    }

    override fun onSurfaceResetOnWorker() {
        subjectMasks.discardPending()
        // The new surface's descriptor set has no clock content yet.
        clockOverlay.reset()
        updateEffectState {
            it.copy(
                hasSubject = false,
                clock = it.clock.copy(faceUploaded = false)
            )
        }
    }

    override fun onEffectResourcesReleased() {
        subjectMasks.close()
        clockOverlay.release()
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private companion object {
        const val TAG = "VulkanGlassHost"
    }
}
