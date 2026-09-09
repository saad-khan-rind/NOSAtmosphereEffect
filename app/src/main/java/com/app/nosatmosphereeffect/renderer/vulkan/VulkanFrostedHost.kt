package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.FrostedRenderState
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageBridge
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageHost

internal class VulkanFrostedHost(
    context: Context,
    initialState: FrostedRenderState,
    onFatalFailure: (WallpaperRenderHost, String) -> Unit,
    onVulkanActive: (WallpaperRenderHost, Int) -> Unit,
    previewSource: (() -> Bitmap?)? = null
) : VulkanSingleImageHost<FrostedRenderState>(
    context = context,
    threadName = "AtmoVulkanFrosted",
    initialState = initialState.sanitized(),
    bridge = FrostedBridge,
    onFatalFailure = onFatalFailure,
    onVulkanActive = onVulkanActive,
    previewSource = previewSource
) {
    /**
     * The wallpaper clock. Shares Frosted's render state rather than being
     * configured separately, so a progress tick and a clock change take the
     * same path into the shader.
     */
    private val clockOverlay = VulkanClockOverlay(appContext, "Frosted")

    /**
     * Segmentation for the clock's depth effect. Frosted has no "background
     * only" mode, so this is the mask's only consumer and it runs only while
     * depth is switched on.
     */
    private val clockDepthMask = VulkanClockDepthMask(
        appContext,
        "Frosted",
        ::requestRender
    )

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
            upload = { bitmap -> VulkanFrostedNative.nativeUploadClock(handle, bitmap) },
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

    /**
     * Keeps the state's clock fields in step with the overlay.
     *
     * Called from updateState on whichever thread changed a preference; the
     * dynamic fields are read from the overlay rather than carried forward
     * from a snapshot, for the reason above.
     */
    private fun syncClockState(clock: ClockOverlayState) {
        clockOverlay.applyState(clock)
        updateEffectState { current ->
            current.copy(
                clock = clock.copy(
                    textureAspect = if (clockOverlay.hasUploadedFace) {
                        clockOverlay.aspectRatio
                    } else {
                        current.clock.textureAspect
                    },
                    faceUploaded = clockOverlay.hasUploadedFace && clock.enabled
                )
            ).sanitized()
        }
    }

    init {
        val safeInitial = initialState.sanitized()
        clockOverlay.applyState(safeInitial.clock)
        clockDepthMask.configure(safeInitial.clock.needsSubjectMask())
        startNativeEngine()
    }

    fun updateState(state: FrostedRenderState) {
        val safe = state.sanitized()
        updateEffectState { safe }
        syncClockState(safe.clock)
        // Reloading on the transition into "wanted" is what dispatches the
        // extraction: one request is served per image, so an image uploaded
        // while depth was off would otherwise never get a mask.
        if (clockDepthMask.configure(safe.clock.needsSubjectMask()) &&
            safe.clock.needsSubjectMask()
        ) {
            reloadTexture()
        }
    }

    override fun prepareFrameOnWorker(
        handle: Long,
        textureGeneration: Long
    ): Boolean {
        uploadClockOnWorker(handle)
        val hasSubject = clockDepthMask.uploadIfNeeded(
            handle = handle,
            textureGeneration = textureGeneration,
            upload = VulkanFrostedNative::nativeUploadSubjectMask,
            clear = VulkanFrostedNative::nativeClearSubjectMask
        )
        if (hasSubject != currentEffectState().hasSubject) {
            updateEffectState { it.copy(hasSubject = hasSubject).sanitized() }
        }
        return true
    }

    override fun onSurfaceResetOnWorker() {
        // The new surface's descriptor set has no clock or mask content yet.
        clockOverlay.reset()
        clockDepthMask.onSurfaceReset()
        updateEffectState {
            it.copy(
                clock = it.clock.copy(faceUploaded = false),
                hasSubject = false
            ).sanitized()
        }
    }

    override fun onEffectResourcesReleased() {
        clockOverlay.release()
        clockDepthMask.close()
    }

    override fun onWallpaperUploadedOnWorker(
        handle: Long,
        bitmap: Bitmap,
        textureGeneration: Long
    ): Boolean {
        clockDepthMask.onImageUploaded(bitmap, textureGeneration)
        val radius = currentEffectState().blurRadiusPixels
        if (radius < 1) {
            return VulkanFrostedNative.nativeUploadBlurred(handle, bitmap)
        }

        val blurred = try {
            AtmosphereImageProcessor.createBlurredBitmap(
                source = bitmap,
                radius = radius
            )
        } catch (failure: RuntimeException) {
            Log.e(TAG, "Unable to preblur the Vulkan Frosted wallpaper", failure)
            return false
        } catch (failure: OutOfMemoryError) {
            Log.e(TAG, "Not enough memory to preblur the Frosted wallpaper", failure)
            return false
        }
        return try {
            VulkanFrostedNative.nativeUploadBlurred(handle, blurred)
        } finally {
            if (!blurred.isRecycled) blurred.recycle()
        }
    }

    private companion object {
        const val TAG = "VulkanFrostedHost"
    }
}

private object FrostedBridge :
    VulkanSingleImageBridge<FrostedRenderState> {
    override val effectLabel = "Frosted"

    override fun create(assets: android.content.res.AssetManager): Long {
        if (!VulkanFrostedNative.libraryLoaded) return 0L
        return VulkanFrostedNative.nativeCreate(assets)
    }

    override fun setSurface(
        handle: Long,
        surface: android.view.Surface,
        width: Int,
        height: Int
    ): Boolean {
        return VulkanFrostedNative.nativeSetSurface(
            handle,
            surface,
            width,
            height
        )
    }

    override fun getApiVersion(handle: Long): Int {
        return VulkanFrostedNative.nativeGetApiVersion(handle)
    }

    override fun uploadWallpaper(handle: Long, bitmap: Bitmap): Boolean {
        return VulkanFrostedNative.nativeUploadSharp(handle, bitmap)
    }

    override fun setState(
        handle: Long,
        state: FrostedRenderState,
        scrollOffsetX: Float,
        scrollWindowX: Float
    ) {
        val safe = state.sanitized()
        VulkanFrostedNative.nativeSetState(
            handle = handle,
            progress = safe.progress,
            dimLevel = safe.dimLevel,
            enableNoise = safe.enableNoise,
            noiseScale = safe.noiseScale,
            noiseStrength = safe.noiseStrength,
            drawerBlur = safe.drawerBlur,
            scrollOffsetX = scrollOffsetX,
            scrollWindowX = scrollWindowX,
            clockCenterX = safe.clock.centerX,
            clockTop = safe.clock.top,
            clockHeightFraction = safe.clock.height,
            clockTextureAspect = safe.clock.textureAspect,
            // The lock/home fade is folded in here, once, so the shader has no
            // policy in it and both backends share one curve.
            clockOpacity = safe.clock.effectiveOpacity(safe.progress),
            clockUploaded = safe.clock.faceUploaded,
            // Depth needs a mask, so the user's switch is ANDed with one
            // existing — the shader must never sample the clear texture.
            clockDepth = safe.clock.depthEnabled && safe.hasSubject
        )
    }

    override fun render(handle: Long): Int {
        return VulkanFrostedNative.nativeRender(handle)
    }

    override fun destroySurface(handle: Long) {
        VulkanFrostedNative.nativeDestroySurface(handle)
    }

    override fun destroy(handle: Long) {
        VulkanFrostedNative.nativeDestroy(handle)
    }
}
