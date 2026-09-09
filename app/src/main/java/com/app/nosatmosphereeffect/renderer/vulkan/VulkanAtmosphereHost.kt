package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
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

    /**
     * Set on the main thread when the engine becomes visible, consumed on the
     * worker. Same reason as the GLES path: ClockFaceRenderer is confined to
     * the thread that draws it, and onVisibilityChanged does not arrive on
     * that thread.
     */
    @Volatile private var pendingClockEntry = false

    /**
     * Whether a real subject mask is bound to the mask binding right now.
     *
     * Deliberately a field on the host and NOT a value carried through
     * AtmosphereRenderState. `hasSubject` describes a GPU resource, not a
     * user setting, and every attempt to carry it in the state object has
     * lost it: the state is rebuilt from the controller's snapshot on every
     * progress tick of the unlock animation, on every preference change and
     * on every colour re-derivation, each of which has to remember to carry
     * the flag forward. Segmentation finishes somewhere inside that traffic,
     * so one missed carry-forward permanently reverts the flag — the mask has
     * already been consumed by takePending() and the coordinator will not
     * re-dispatch for a generation it has served.
     *
     * The uniform value is now derived from this field at the point of use,
     * so there is exactly one writer and no carry-forward to get wrong.
     */
    @Volatile private var subjectMaskUploaded = false

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
        clockTexture.animateEntry = state.clockAnimate
        clockTexture.color = state.clockColor
        clockTexture.hourFormatOverride =
            AtmosphereClockPolicy.hourFormatOverride(state.clockHourFormat)
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
        // Carried-over dynamic fields MUST be read from this lambda's own
        // argument, not from the `current` snapshot above.
        //
        // hasSubject, clockTextureAspect and clockFaceUploaded are all
        // written by prepareFrameOnWorker on the render worker, while this
        // runs on whichever thread called applyState — and applyState runs on
        // every progress tick of the lock/unlock animation. Reading a
        // snapshot taken before the update meant a mask that finished
        // extracting inside that window was silently overwritten with false.
        //
        // That loss was permanent, which is why it read as "background-only
        // and clock depth never work" rather than as a flicker:
        // takePending() had already consumed the mask, and the coordinator
        // will not re-dispatch for a generation it has already served, so
        // nothing set hasSubject again until the image itself changed.
        // Segmentation takes a few hundred milliseconds and the unlock
        // animation is running for exactly that long, so the window is hit
        // most times rather than rarely.
        //
        // VulkanGlassHost and VulkanHalftoneHost already do this correctly;
        // this host was the odd one out.
        if (!isolationEnabled) subjectMaskUploaded = false
        updateEffectState { previous ->
            safe.copy(
                // Read from the host's own fields, never carried forward from
                // the previous state — see subjectMaskUploaded.
                hasSubject = isolationEnabled && subjectMaskUploaded,
                clockTextureAspect = if (clockTexture.hasUploadedFace) {
                    clockTexture.aspectRatio
                } else {
                    previous.clockTextureAspect
                },
                clockFaceUploaded = clockTexture.hasUploadedFace && safe.clockEnabled,
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
        subjectMaskUploaded = false
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

    /**
     * Plays the clock's entry animation on the next prepared frame. Called
     * when the wallpaper engine becomes visible.
     */
    fun beginClockEntry() {
        pendingClockEntry = true
        requestRender()
    }

    override fun prepareFrameOnWorker(
        handle: Long,
        textureGeneration: Long
    ): Boolean {
        val pending = subjectMasks.takePending()
        if (pending != null) {
            try {
                // A mask extracted for an older generation is dropped: a newer
                // wallpaper landed while this was in flight, and the request
                // for that one was already dispatched.
                if (
                    pending.generation == textureGeneration &&
                    subjectMasks.enabled
                ) {
                    val uploaded = VulkanAtmosphereNative.nativeUploadMask(
                        handle,
                        pending.bitmap
                    )
                    subjectMaskUploaded = uploaded
                    updateEffectState { it.copy(hasSubject = uploaded) }
                    if (!uploaded) {
                        // Deliberately NOT fatal. Returning false here takes
                        // the entire Vulkan backend down permanently for the
                        // build (see VulkanSingleImageHost.drawOnWorker and
                        // VulkanFailureStore), which was tolerable while masks
                        // were only computed for an opt-in Glass sub-feature.
                        // The clock's depth effect turns them on by default,
                        // so one bad mask would now cost every user Vulkan
                        // entirely. Degrade to "no subject" instead.
                        Log.w(
                            TAG,
                            "Subject mask upload failed; continuing without depth"
                        )
                    }
                }
            } finally {
                pending.bitmap.recycleSafely()
            }
        }

        if (currentEffectState().clockEnabled) {
            uploadClockFrame(handle)
        }

        return true
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
        // Held until the clock would actually be on screen — see the same
        // guard in AtmosphereRenderer.drawClockOverlay.
        if (pendingClockEntry && currentEffectState().effectiveClockOpacity() > 0f) {
            pendingClockEntry = false
            clockTexture.beginEntry()
        }
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
        subjectMaskUploaded = false
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
