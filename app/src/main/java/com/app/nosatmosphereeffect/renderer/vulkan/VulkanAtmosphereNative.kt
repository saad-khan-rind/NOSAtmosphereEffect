package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.view.Surface
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderState
import com.app.nosatmosphereeffect.renderer.vulkan.common.VulkanSingleImageBridge

internal object VulkanAtmosphereNative {
    external fun nativeCreate(
        assets: AssetManager,
        reverse: Boolean
    ): Long

    external fun nativeSetSurface(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int
    ): Boolean

    external fun nativeGetApiVersion(handle: Long): Int

    external fun nativeUploadWallpaper(handle: Long, bitmap: Bitmap): Boolean

    external fun nativeUploadBlurred(handle: Long, bitmap: Bitmap): Boolean

    external fun nativeUploadMask(handle: Long, bitmap: Bitmap): Boolean

    external fun nativeClearMask(handle: Long): Boolean

    external fun nativeUploadClock(handle: Long, bitmap: Bitmap): Boolean

    external fun nativeClearClock(handle: Long): Boolean

    external fun nativeSetState(
        handle: Long,
        progress: Float,
        dimLevel: Float,
        noiseEnabled: Boolean,
        noiseScale: Float,
        noiseStrength: Float,
        saturation: Float,
        contrast: Float,
        glassEnabled: Boolean,
        glassLineCount: Float,
        glassLineThickness: Float,
        scrollOffsetX: Float,
        scrollWindowX: Float,
        backgroundOnly: Boolean,
        hasSubject: Boolean,
        drawerBlur: Float,
        clockCenterX: Float,
        clockTop: Float,
        clockHeightFraction: Float,
        clockTextureAspect: Float,
        clockOpacity: Float,
        clockEnabled: Boolean,
        blobColors: FloatArray,
        blobPositions: FloatArray,
        blobSizes: FloatArray,
        blobCount: Int
    ): Boolean

    external fun nativeRender(handle: Long): Int

    external fun nativeDestroySurface(handle: Long)

    external fun nativeDestroy(handle: Long)
}

internal class VulkanAtmosphereBridge(
    private val reverse: Boolean
) : VulkanSingleImageBridge<AtmosphereRenderState> {
    override val effectLabel: String =
        if (reverse) "Reverse Atmosphere" else "Atmosphere"

    override fun create(assets: AssetManager): Long {
        return if (VulkanNative.libraryLoaded) {
            VulkanAtmosphereNative.nativeCreate(assets, reverse)
        } else {
            0L
        }
    }

    override fun setSurface(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int
    ): Boolean {
        return VulkanAtmosphereNative.nativeSetSurface(
            handle,
            surface,
            width,
            height
        )
    }

    override fun getApiVersion(handle: Long): Int {
        return VulkanAtmosphereNative.nativeGetApiVersion(handle)
    }

    override fun uploadWallpaper(handle: Long, bitmap: Bitmap): Boolean {
        return VulkanAtmosphereNative.nativeUploadWallpaper(handle, bitmap)
    }

    override fun setState(
        handle: Long,
        state: AtmosphereRenderState,
        scrollOffsetX: Float,
        scrollWindowX: Float
    ) {
        val safe = state.sanitized()
        check(VulkanAtmosphereNative.nativeSetState(
            handle = handle,
            progress = safe.progress,
            dimLevel = safe.dimLevel,
            noiseEnabled = safe.noiseEnabled,
            noiseScale = safe.noiseScale,
            noiseStrength = safe.noiseStrength,
            saturation = safe.saturation,
            contrast = safe.contrast,
            glassEnabled = safe.glassEnabled,
            glassLineCount = safe.glassLineCount.toFloat(),
            glassLineThickness = safe.glassLineThickness,
            scrollOffsetX = scrollOffsetX,
            scrollWindowX = scrollWindowX,
            backgroundOnly = safe.glassBackgroundOnly,
            hasSubject = safe.hasSubject,
            drawerBlur = safe.drawerBlur,
            clockCenterX = safe.clockCenterX,
            clockTop = safe.clockTop,
            clockHeightFraction = safe.clockHeight,
            clockTextureAspect = safe.clockTextureAspect,
            clockOpacity = safe.clockOpacity,
            clockEnabled = safe.clockEnabled,
            blobColors = safe.blobs.colors,
            blobPositions = safe.blobs.positions,
            blobSizes = safe.blobs.sizes,
            blobCount = safe.blobs.count
        )) {
            "The native Vulkan Atmosphere state could not be updated"
        }
    }

    override fun render(handle: Long): Int {
        return VulkanAtmosphereNative.nativeRender(handle)
    }

    override fun destroySurface(handle: Long) {
        VulkanAtmosphereNative.nativeDestroySurface(handle)
    }

    override fun destroy(handle: Long) {
        VulkanAtmosphereNative.nativeDestroy(handle)
    }
}
