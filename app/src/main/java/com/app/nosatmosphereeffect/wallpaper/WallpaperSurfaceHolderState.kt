package com.app.nosatmosphereeffect.wallpaper

import android.view.SurfaceHolder

internal class WallpaperSurfaceHolderState {
    private var holder: SurfaceHolder? = null

    fun remember(holder: SurfaceHolder) {
        this.holder = holder
    }

    fun requireHolder(): SurfaceHolder {
        return checkNotNull(holder) {
            "Android has not attached a wallpaper surface holder"
        }
    }

    fun clear() {
        holder = null
    }
}

internal object WallpaperSurfaceHolderConstruction {
    private val currentHolder = ThreadLocal<SurfaceHolder?>()

    fun holder(): SurfaceHolder? = currentHolder.get()

    fun <T> withHolder(holder: SurfaceHolder, factory: () -> T): T {
        val previous = currentHolder.get()
        currentHolder.set(holder)
        return try {
            factory()
        } finally {
            if (previous == null) {
                currentHolder.remove()
            } else {
                currentHolder.set(previous)
            }
        }
    }
}
