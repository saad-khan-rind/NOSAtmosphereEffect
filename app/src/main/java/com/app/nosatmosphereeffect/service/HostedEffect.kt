package com.app.nosatmosphereeffect.service

import android.content.SharedPreferences
import android.graphics.Bitmap
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService

/**
 * One effect's wallpaper hooks, for a service that hosts the effect in place
 * of its own: Shuffle, which shows a different effect each time the phone is
 * locked, inside the one wallpaper the system knows. Every effect's own
 * service gives these (AnimatedEffectWallpaperService.hostIn), so what an
 * effect needs set up is written once, in its service.
 */
internal interface HostedEffect {
    val effectId: String
    val lockedProgress: Float
    val unlockedProgress: Float
    val defaultAnimationDurationMs: Long
    val initialProgress: Float?
    val sleepingProgress: Float
    val blurDrawerWhenHidden: Boolean

    /** A renderer of this effect, its type kept to the effect. */
    fun newRenderer(): HostedRenderer
}

/** A hosted effect's renderer: what a wallpaper engine asks of it. */
internal interface HostedRenderer {
    fun attach(engine: GLWallpaperService.GLEngine)
    fun configure(preferences: SharedPreferences)
    fun setProgress(progress: Float)
    fun setFixedState(effectApplied: Boolean)
    fun setDrawerBlurred(blurred: Boolean)
    fun onAttached(requestRender: () -> Unit)
    fun onVisibilityChanged(visible: Boolean)
    fun reload()
    fun queuePlaylistTransition(bitmap: Bitmap)
    fun rotateOwnImages()
    fun reselectBackend()
    fun release()
}
