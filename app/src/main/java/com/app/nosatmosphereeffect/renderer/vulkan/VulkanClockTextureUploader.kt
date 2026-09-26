package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.app.nosatmosphereeffect.helper.ClockFaceBox
import com.app.nosatmosphereeffect.helper.ClockFaceRenderer
import com.app.nosatmosphereeffect.helper.ClockPlacement
import com.app.nosatmosphereeffect.helper.ClockSceneSink
import com.app.nosatmosphereeffect.helper.ClockStyle

/**
 * Vulkan-side wrapper around the shared [ClockFaceRenderer].
 *
 * The drawing itself is shared with the GLES path now; only the upload step
 * differs (this returns a bitmap for
 * [VulkanAtmosphereNative.nativeUploadClock] instead of touching a GL
 * texture). The previous version duplicated the whole Canvas/Paint routine,
 * which is how the two backends drifted apart.
 *
 * Not thread-confined by itself — the caller (VulkanAtmosphereHost) only ever
 * touches it from its single worker thread, same as everything else there.
 */
internal class VulkanClockTextureUploader(context: Context) {

    private val face = ClockFaceRenderer(context)

    val textureWidth: Int get() = face.width
    val textureHeight: Int get() = face.height
    val aspectRatio: Float get() = face.aspectRatio

    /**
     * True once a real face has been uploaded. Until then the shader must
     * not sample the clock binding: an unwritten optional binding holds the
     * engine's 1x1 clear texture, which is opaque black, so drawing it would
     * paint a solid black rectangle where the clock belongs.
     */
    var hasUploadedFace: Boolean = false
        private set

    var style: ClockStyle
        get() = face.style
        set(value) { face.style = value }

    /** Draws the day and date, wherever its own placement puts it. */
    var showDate: Boolean
        get() = face.showDate
        set(value) { face.showDate = value }

    /** Where the digits sit on screen; the date is placed against them. */
    var clockPlacement: ClockPlacement
        get() = face.clockPlacement
        set(value) { face.clockPlacement = value }

    /** Where the date sits on screen, set and stored like the clock's. */
    var datePlacement: ClockPlacement
        get() = face.datePlacement
        set(value) { face.datePlacement = value }

    /** Width/height of the surface; only the date's placement needs it. */
    var screenAspect: Float
        get() = face.screenAspect
        set(value) { face.screenAspect = value }

    /** Where the digits sit inside the bitmap, for turning the stored
     *  placement into the rectangle the shader samples. */
    val faceBox: ClockFaceBox
        get() = face.faceBox
    var animateDigits: Boolean
        get() = face.animateDigits
        set(value) { face.animateDigits = value }

    var animateEntry: Boolean
        get() = face.animateEntry
        set(value) { face.animateEntry = value }

    var color: Int
        get() = face.color
        set(value) { face.color = value }

    var hourFormatOverride: Boolean?
        get() = face.hourFormatOverride
        set(value) { face.hourFormatOverride = value }

    /** The Adaptive face's stroke weight, 0 thin .. 1 bold. */
    var weight: Float
        get() = face.weight
        set(value) { face.weight = value }

    /** The Adaptive face tints each digit by what is behind it. */
    var adaptiveColors: Boolean
        get() = face.adaptiveColors
        set(value) { face.adaptiveColors = value }

    /** The launcher's page offset; the Adaptive face fits to what is on screen. */
    var scrollOffsetX: Float
        get() = face.scrollOffsetX
        set(value) { face.scrollOffsetX = value }

    /**
     * Hand this to the renderer's SubjectMaskCoordinator: the Adaptive face
     * builds its picture of what is behind it from the same image and mask.
     */
    val sceneSink: ClockSceneSink
        get() = face.sceneSource

    fun isAnimating(): Boolean = face.isAnimating(SystemClock.uptimeMillis())

    /** Starts the entry animation; see ClockFaceRenderer.beginEntry. */
    fun beginEntry() {
        face.beginEntry(SystemClock.uptimeMillis())
    }

    /**
     * Returns a bitmap only when there is something new to upload — null
     * otherwise, so the caller can skip the upload. The bitmap is owned and
     * reused by the face renderer: the caller must NOT recycle it.
     */
    fun renderIfChanged(): Bitmap? {
        val uptime = SystemClock.uptimeMillis()
        return face.render(
            nowMillis = System.currentTimeMillis(),
            uptimeMs = uptime,
            // The native side overwrites the bound image in place when the
            // extent is unchanged, which it is for every frame of an
            // animation, so those cost a staging copy rather than an image
            // allocation plus vkDeviceWaitIdle. (Moving the date resizes the
            // bitmap and does take the slower path, once per change.) The
            // remaining throttle only bounds the per-frame Canvas redraw.
            minimumIntervalMs = if (face.isEntering(uptime)) {
                ENTRY_MIN_INTERVAL_MS
            } else {
                ANIMATION_MIN_INTERVAL_MS
            }
        )
    }

    fun markUploaded() {
        hasUploadedFace = true
    }

    fun refreshClockFormatPreference() {
        face.refreshFormat()
    }

    /**
     * Call after a surface reset so the next frame re-uploads even if the
     * displayed time has not changed — the new surface's descriptor set has
     * no clock content yet.
     */
    fun reset() {
        hasUploadedFace = false
        face.invalidate()
    }

    fun release() {
        hasUploadedFace = false
        face.release()
    }

    private companion object {
        /** ~30fps ceiling on digit-change re-uploads. */
        const val ANIMATION_MIN_INTERVAL_MS = 33L
        /** ~60fps while the one-off entry animation is playing. */
        const val ENTRY_MIN_INTERVAL_MS = 16L
    }
}
