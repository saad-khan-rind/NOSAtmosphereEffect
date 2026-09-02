package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.SubjectMaskCoordinator
import com.app.nosatmosphereeffect.helper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.AtmosphereBlobFrame
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderState
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageHost

internal class VulkanAtmosphereHost(
    context: Context,
    private val reverse: Boolean,
    initialState: AtmosphereRenderState,
    onFatalFailure: (VulkanAtmosphereHost, String) -> Unit,
    onVulkanActive: (VulkanAtmosphereHost, Int) -> Unit,
    previewSource: (() -> Bitmap?)? = null
) : VulkanSingleImageHost<AtmosphereRenderState>(
    context = context,
    threadName = if (reverse) {
        "AtmoVulkanReverseAtmosphere"
    } else {
        "AtmoVulkanAtmosphere"
    },
    initialState = initialState.sanitized(),
    bridge = VulkanAtmosphereBridge(reverse),
    onFatalFailure = { host: WallpaperRenderHost, reason: String ->
        onFatalFailure(host as VulkanAtmosphereHost, reason)
    },
    onVulkanActive = { host: WallpaperRenderHost, version: Int ->
        onVulkanActive(host as VulkanAtmosphereHost, version)
    },
    previewSource = previewSource
) {
    private val blobPlanner = AtmosphereBlobPlanner()
    private val subjectMasks = SubjectMaskCoordinator(appContext) {
        requestRender()
    }
    private val clockTexture = VulkanClockTextureUploader(appContext)

    init {
        subjectMasks.configure(initialState.sanitized().needsSubjectMask())
        applyClockConfiguration(initialState.sanitized())
        startNativeEngine()
    }

    /**
     * Face settings (style/seconds/animation) live on the uploader, not in
     * the uniform buffer, because changing any of them changes the bitmap
     * rather than how the shader reads it.
     */
    private fun applyClockConfiguration(state: AtmosphereRenderState) {
        clockTexture.style = state.clockStyle
        clockTexture.showSeconds = state.clockShowSeconds
        clockTexture.animateDigits = state.clockAnimate
        clockTexture.color = state.clockColor
    }

    fun updateState(state: AtmosphereRenderState) {
        val safe = state.sanitized()
        val current = currentEffectState()
        if (safe.progress == 0f && current.progress != 0f) {
            blobPlanner.rerollTargets()
        }
        // Either Glass's background-only mode or the clock's depth effect
        // can call for a subject mask, independently of the other.
        val isolationEnabled = safe.needsSubjectMask()
        val isolationChanged = subjectMasks.configure(isolationEnabled)
        applyClockConfiguration(safe)
        updateEffectState {
            safe.copy(
                hasSubject = if (isolationEnabled) current.hasSubject else false,
                clockTextureAspect = current.clockTextureAspect,
                clockFaceUploaded = current.clockFaceUploaded && safe.clockEnabled,
                blobs = blobPlanner.frame(safe.progress)
            )
        }
        if (isolationChanged && isolationEnabled) {
            reloadTexture()
        }
    }

    override fun onWallpaperUploadedOnWorker(
        handle: Long,
        bitmap: Bitmap,
        textureGeneration: Long
    ): Boolean {
        updateEffectState {
            it.copy(
                hasSubject = false,
                blobs = AtmosphereBlobFrame()
            )
        }
        if (!VulkanAtmosphereNative.nativeClearMask(handle)) {
            return false
        }

        val blurred = try {
            AtmosphereImageProcessor.createBlurredBitmap(bitmap)
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Unable to preblur the Vulkan Atmosphere wallpaper", failure)
            return false
        } catch (failure: OutOfMemoryError) {
            Log.e(TAG, "Not enough memory to preblur the Atmosphere wallpaper", failure)
            return false
        }
        try {
            if (!VulkanAtmosphereNative.nativeUploadBlurred(handle, blurred)) {
                return false
            }
            blobPlanner.replaceImage(blurred)
            updateEffectState {
                it.copy(blobs = blobPlanner.frame(it.progress))
            }
        } finally {
            blurred.recycleSafely()
        }

        if (subjectMasks.enabled) {
            runCatching {
                subjectMasks.request(bitmap, textureGeneration)
            }.onFailure { failure ->
                Log.w(TAG, "Unable to request a Vulkan Atmosphere subject mask", failure)
            }
        }
        return true
    }

    override fun prepareFrameOnWorker(
        handle: Long,
        textureGeneration: Long
    ): Boolean {
        var success = true

        val pending = subjectMasks.takePending()
        if (pending != null) {
            try {
                if (pending.generation == textureGeneration && subjectMasks.enabled) {
                    val uploaded = VulkanAtmosphereNative.nativeUploadMask(
                        handle,
                        pending.bitmap
                    )
                    updateEffectState { it.copy(hasSubject = uploaded) }
                    success = uploaded
                }
            } finally {
                pending.bitmap.recycleSafely()
            }
        }

        if (success && currentEffectState().clockEnabled) {
            uploadClockFrame(handle)
        }

        return success
    }

    /**
     * Uploads a clock face when there is one to upload, and keeps the frame
     * pump running while a digit transition is in flight.
     *
     * Failures here are deliberately non-fatal: prepareFrameOnWorker
     * returning false takes the whole Vulkan backend down permanently (see
     * VulkanSingleImageHost.drawOnWorker), which is far too heavy a response
     * to a decorative overlay failing to upload.
     */
    private fun uploadClockFrame(handle: Long) {
        val bitmap = try {
            clockTexture.renderIfChanged()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to render the Vulkan Atmosphere clock face", failure)
            null
        }

        if (bitmap != null) {
            // The bitmap is owned and reused by the face renderer — do not
            // recycle it here. The previous version did, which meant every
            // frame after the first uploaded a recycled bitmap.
            if (VulkanAtmosphereNative.nativeUploadClock(handle, bitmap)) {
                clockTexture.markUploaded()
                val aspect = clockTexture.aspectRatio
                updateEffectState {
                    it.copy(
                        clockTextureAspect = aspect,
                        clockFaceUploaded = true
                    )
                }
            } else {
                Log.w(TAG, "Unable to upload the Vulkan Atmosphere clock texture")
            }
        }

        // A static wallpaper produces no frames on its own, so without this
        // the digit animation would freeze part-way and the time would only
        // change when something unrelated triggered a draw.
        if (clockTexture.isAnimating()) {
            requestRender()
        }
    }

    override fun onSurfaceResetOnWorker() {
        subjectMasks.discardPending()
        clockTexture.reset()
        updateEffectState {
            it.copy(
                hasSubject = false,
                clockFaceUploaded = false,
                blobs = AtmosphereBlobFrame()
            )
        }
    }

    override fun onEffectResourcesReleased() {
        subjectMasks.close()
        clockTexture.release()
    }

    /**
     * Re-reads the system 12/24-hour setting and forces a redraw. Called from
     * AtmosphereService's time-tick receiver.
     */
    fun onTimeChanged() {
        clockTexture.refreshClockFormatPreference()
        clockTexture.reset()
        requestRender()
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private companion object {
        const val TAG = "VulkanAtmosphereHost"
    }
}
