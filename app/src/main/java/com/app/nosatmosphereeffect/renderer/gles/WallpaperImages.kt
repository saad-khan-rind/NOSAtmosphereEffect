package com.app.nosatmosphereeffect.renderer.gles

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.clock.ClockSceneSink
import com.app.nosatmosphereeffect.image.WallpaperFitHelper
import com.app.nosatmosphereeffect.subject.SubjectMaskCoordinator

/**
 * The wallpaper image an effect draws, as textures in its OpenGL ES context:
 * loaded and fitted to the surface (again when the surface size changes),
 * swapped for the next playlist image, and given its subject mask when
 * segmentation finds one. For effects that draw the photo as it is; GL
 * thread only, apart from [queuePlaylistTransition], [reload] and [release].
 */
internal class WallpaperImages(
    private val context: Context,
    /** For error messages. */
    private val effect: String,
    private val previewSource: (() -> Bitmap?)?,
    /** Loads the image for a surface of this width and height: the wallpaper, unless an effect draws another. */
    private val loadImage: (Int, Int) -> WallpaperFitHelper.RenderImage = { width, height ->
        WallpaperFitHelper.loadForRender(context, width, height, previewSource)
    },
    onSubjectMaskUpdated: () -> Unit
) {
    var current = WallpaperTextureSet()
        private set
    private var next = WallpaperTextureSet()

    /** The share of the image's width the screen shows, for wallpaper scrolling. */
    var windowX = 1f
        private set
    private var nextWindowX = 1f

    private val subjectMasks = SubjectMaskCoordinator(context) { onSubjectMaskUpdated() }

    /** The Adaptive clock fits itself around the subject found here, in the image actually drawn. */
    var sceneSink: ClockSceneSink?
        get() = subjectMasks.sceneSink
        set(value) {
            subjectMasks.sceneSink = value
        }

    /** Whether segmentation is on, whatever it is for. */
    val masksEnabled: Boolean get() = subjectMasks.enabled

    /** Whether the image drawn has its subject mask. */
    val maskReady: Boolean get() = subjectMasks.enabled && current.hasSubject

    @Volatile private var needsReload = false
    @Volatile private var surfaceWidth = 0
    @Volatile private var surfaceHeight = 0
    private var fittedForWidth = -1
    private var fittedForHeight = -1
    private var generationCounter = 0L

    private val playlistLock = Any()
    private var pendingPlaylistBitmap: Bitmap? = null
    private var released = false

    /** The next playlist image; it takes over at the next frame. Any thread. */
    fun queuePlaylistTransition(bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            Log.w(TAG, "Ignoring a recycled playlist bitmap")
            return
        }
        var rejected = false
        val replaced = synchronized(playlistLock) {
            if (released) {
                rejected = true
                null
            } else {
                pendingPlaylistBitmap.also { pendingPlaylistBitmap = bitmap }
            }
        }
        if (rejected) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        if (replaced != null && replaced !== bitmap && !replaced.isRecycled) replaced.recycle()
    }

    /** Loads the wallpaper again at the next frame. Any thread. */
    fun reload() {
        needsReload = true
    }

    /**
     * Turns segmentation on or off. Reloads on the turn on, as that is what
     * dispatches the extraction: the coordinator serves one request per image,
     * so an image loaded while the mask was unwanted would never get one.
     */
    fun wantSubjectMask(wanted: Boolean) {
        if (subjectMasks.configure(wanted) && wanted) needsReload = true
    }

    fun release() {
        val pending = synchronized(playlistLock) {
            if (released) return
            released = true
            pendingPlaylistBitmap.also { pendingPlaylistBitmap = null }
        }
        subjectMasks.close()
        if (pending != null && !pending.isRecycled) pending.recycle()
    }

    /** A fresh GL context: the old textures went with the old one. */
    fun onSurfaceCreated() {
        current.reset()
        next.reset()
        subjectMasks.discardPending()
        needsReload = true
    }

    /** A new surface size: the image is fitted again unless it already fits. */
    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (width != fittedForWidth || height != fittedForHeight) needsReload = true
    }

    /** Before drawing: the next playlist image, a reload, and a mask that has arrived. Throws on failure. */
    fun prepareFrame() {
        processPlaylistTransition()
        if (needsReload) {
            needsReload = false
            try {
                load()
            } catch (failure: Exception) {
                needsReload = true
                throw failure
            }
        }
        applyPendingSubjectMask()
    }

    /** A frame failed: without an image, the next frame loads it again. */
    fun onFrameFailed() {
        if (!current.isValid()) needsReload = true
    }

    private fun load() {
        fittedForWidth = surfaceWidth
        fittedForHeight = surfaceHeight
        val render = loadImage(surfaceWidth, surfaceHeight)
        val bitmap = render.bitmap
        try {
            val photo = GlesTexture.upload(bitmap, "the $effect photo", mipmaps = true)
            delete(current)
            windowX = render.windowX
            current.width = bitmap.width
            current.height = bitmap.height
            current.generation = ++generationCounter
            current.hasSubject = false
            current.photoId = photo
            subjectMasks.request(bitmap, current.generation)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun processPlaylistTransition() {
        val raw = synchronized(playlistLock) {
            pendingPlaylistBitmap.also { pendingPlaylistBitmap = null }
        } ?: return
        var bitmap: Bitmap? = null
        try {
            val render = WallpaperFitHelper.fitForRender(context, raw, surfaceWidth, surfaceHeight)
            bitmap = render.bitmap
            nextWindowX = render.windowX
            fittedForWidth = surfaceWidth
            fittedForHeight = surfaceHeight

            deleteMask(next)
            next.photoId = GlesTexture.upload(bitmap, "the $effect photo", existingId = next.photoId, mipmaps = true)
            next.width = bitmap.width
            next.height = bitmap.height
            next.generation = ++generationCounter
            next.hasSubject = false

            val previous = current
            current = next
            next = previous
            val previousWindowX = windowX
            windowX = nextWindowX
            nextWindowX = previousWindowX
            subjectMasks.request(bitmap, current.generation)
        } catch (failure: Exception) {
            Log.e(TAG, "Unable to apply the next $effect playlist image", failure)
            delete(next)
            needsReload = true
        } finally {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            if (!raw.isRecycled) raw.recycle()
        }
    }

    private fun applyPendingSubjectMask() {
        val pending = subjectMasks.takePending() ?: return
        try {
            if (pending.generation != current.generation || !subjectMasks.enabled) return
            current.maskId = GlesTexture.upload(pending.bitmap, "the $effect subject mask", existingId = current.maskId)
            current.hasSubject = true
        } finally {
            if (!pending.bitmap.isRecycled) pending.bitmap.recycle()
        }
    }

    private fun deleteMask(set: WallpaperTextureSet) {
        GlesTexture.delete(set.maskId)
        set.maskId = 0
        set.hasSubject = false
    }

    private fun delete(set: WallpaperTextureSet) {
        GlesTexture.delete(set.photoId)
        GlesTexture.delete(set.maskId)
        set.reset()
    }

    private companion object {
        const val TAG = "WallpaperImages"
    }
}
