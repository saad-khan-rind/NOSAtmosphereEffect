package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.renderer.duo.DuoHomeImage
import com.app.nosatmosphereeffect.renderer.duo.DuoRenderState
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageBridge
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageHost
import com.app.nosatmosphereeffect.transition.WakeTransition
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Duo on Vulkan. The shared host loads the lock screen's photo (the
 * wallpaper); this adds the home screen's (DuoHomeImage) in a second texture,
 * loaded again whenever its file may have changed or the surface is new.
 */
internal class VulkanDuoHost(
    context: Context,
    initialState: DuoRenderState,
    onFatalFailure: (WallpaperRenderHost, String) -> Unit,
    onVulkanActive: (WallpaperRenderHost, Int) -> Unit,
    previewSource: (() -> Bitmap?)? = null
) : VulkanSingleImageHost<DuoRenderState>(
    context = context,
    threadName = "AtmoVulkanDuo",
    initialState = initialState.sanitized(),
    bridge = DuoBridge,
    onFatalFailure = onFatalFailure,
    onVulkanActive = onVulkanActive,
    previewSource = previewSource
) {
    /** The home photo on the GPU is not the file's: upload it before the next frame. */
    private val homeStale = AtomicBoolean(true)

    init {
        startNativeEngine()
    }

    fun updateState(state: DuoRenderState) {
        // The home photo's scroll window is this host's own knowledge, not the controller's.
        updateEffectState { current -> state.copy(homeWindowX = current.homeWindowX).sanitized() }
    }

    /** The home photo's file changed: a new pick, or a playlist's next one. */
    fun reloadHomeImage() {
        homeStale.set(true)
        requestRender()
    }

    // Duo has no clock: two photos have no one subject to fit it to.
    override fun beginClockEntry() = Unit

    override fun onTimeChanged() = Unit

    override fun onWallpaperUploadedOnWorker(
        handle: Long,
        bitmap: Bitmap,
        textureGeneration: Long
    ): Boolean {
        // A reload covers both photos, and a new surface starts with neither.
        homeStale.set(true)
        return true
    }

    override fun prepareFrameOnWorker(handle: Long, textureGeneration: Long): Boolean {
        if (!homeStale.compareAndSet(true, false)) return true
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) {
            homeStale.set(true)
            return true
        }
        // Failing to load it keeps what is there: at worst the lock photo on both screens.
        val image = runCatching { DuoHomeImage.loadForRender(appContext, width, height) }
            .onFailure { Log.w(TAG, "Unable to load Duo's home photo", it) }
            .getOrNull() ?: return true
        try {
            if (!VulkanDuoNative.nativeUploadHome(handle, image.bitmap)) return false
            updateEffectState { it.copy(homeWindowX = image.windowX).sanitized() }
        } finally {
            if (!image.bitmap.isRecycled) image.bitmap.recycle()
        }
        return true
    }

    override fun onSurfaceResetOnWorker() {
        homeStale.set(true)
    }

    private companion object {
        const val TAG = "VulkanDuoHost"
    }
}

private object DuoBridge : VulkanSingleImageBridge<DuoRenderState> {
    override val effectLabel = "Duo"

    override fun create(assets: android.content.res.AssetManager): Long {
        if (!VulkanDuoNative.libraryLoaded) return 0L
        return VulkanDuoNative.nativeCreate(assets)
    }

    override fun setSurface(handle: Long, surface: android.view.Surface, width: Int, height: Int): Boolean =
        VulkanDuoNative.nativeSetSurface(handle, surface, width, height)

    override fun getApiVersion(handle: Long): Int = VulkanDuoNative.nativeGetApiVersion(handle)

    override fun uploadWallpaper(handle: Long, bitmap: Bitmap): Boolean =
        VulkanDuoNative.nativeUploadWallpaper(handle, bitmap)

    override fun setState(handle: Long, state: DuoRenderState, scrollOffsetX: Float, scrollWindowX: Float) {
        val safe = state.sanitized()
        VulkanDuoNative.nativeSetState(
            handle = handle,
            progress = safe.progress,
            dimLevel = safe.dimLevel,
            style = safe.style.ordinal,
            scrollOffsetX = scrollOffsetX,
            lockWindowX = scrollWindowX,
            homeWindowX = safe.homeWindowX,
            wallpaperZoom = WakeTransition.zoom(1f)
        )
    }

    override fun render(handle: Long): Int = VulkanDuoNative.nativeRender(handle)

    override fun destroySurface(handle: Long) = VulkanDuoNative.nativeDestroySurface(handle)

    override fun destroy(handle: Long) = VulkanDuoNative.nativeDestroy(handle)
}
