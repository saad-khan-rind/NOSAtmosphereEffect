package com.app.nosatmosphereeffect.wallpaper

import android.view.SurfaceHolder

interface WallpaperRenderHost {
    fun onSurfaceCreated(holder: SurfaceHolder)

    fun onSurfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    )

    fun onSurfaceDestroyed(holder: SurfaceHolder)

    /**
     * Synchronously gives up this host's ownership of [holder] while keeping the
     * host reusable if a replacement cannot be attached.
     */
    fun quiesceSurface(holder: SurfaceHolder) {
        onSurfaceDestroyed(holder)
    }

    fun onResume()

    fun onPause()

    fun requestRender()

    fun setWallpaperOffset(xOffset: Float)

    fun close()
}
