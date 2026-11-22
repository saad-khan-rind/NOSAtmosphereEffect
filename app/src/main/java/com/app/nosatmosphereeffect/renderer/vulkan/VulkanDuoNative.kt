package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.view.Surface

internal object VulkanDuoNative {
    val libraryLoaded: Boolean
        get() = VulkanNative.libraryLoaded

    external fun nativeCreate(assets: AssetManager): Long

    external fun nativeSetSurface(
        handle: Long,
        surface: Surface,
        width: Int,
        height: Int
    ): Boolean

    external fun nativeGetApiVersion(handle: Long): Int

    /** The lock screen's photo. */
    external fun nativeUploadWallpaper(
        handle: Long,
        bitmap: Bitmap
    ): Boolean

    /** The home screen's photo. */
    external fun nativeUploadHome(
        handle: Long,
        bitmap: Bitmap
    ): Boolean

    external fun nativeSetState(
        handle: Long,
        progress: Float,
        dimLevel: Float,
        /** DuoStyle's ordinal. */
        style: Int,
        scrollOffsetX: Float,
        /** The share of each photo's width on screen. */
        lockWindowX: Float,
        homeWindowX: Float,
        /** A wake transition's zoom on the wallpaper, else 1. */
        wallpaperZoom: Float
    )

    external fun nativeRender(handle: Long): Int

    external fun nativeDestroySurface(handle: Long)

    external fun nativeDestroy(handle: Long)
}
