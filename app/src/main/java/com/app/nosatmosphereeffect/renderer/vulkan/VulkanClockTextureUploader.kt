package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.app.nosatmosphereeffect.helper.ClockFaceRenderer
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

    var showSeconds: Boolean
        get() = face.showSeconds
        set(value) { face.showSeconds = value }

    var animateDigits: Boolean
        get() = face.animateDigits
        set(value) { face.animateDigits = value }

    var color: Int
        get() = face.color
        set(value) { face.color = value }

    fun isAnimating(): Boolean = face.isAnimating(SystemClock.uptimeMillis())

    /**
     * Returns a bitmap only when there is something new to upload — null
     * otherwise, so the caller can skip the upload. The bitmap is owned and
     * reused by the face renderer: the caller must NOT recycle it.
     */
    fun renderIfChanged(): Bitmap? {
        return face.render(
            nowMillis = System.currentTimeMillis(),
            uptimeMs = SystemClock.uptimeMillis(),
            // Vulkan reallocates the sampled image on every upload, so
            // animation frames are throttled harder than on GLES, where the
            // same call is a texSubImage2D into existing storage.
            minimumIntervalMs = ANIMATION_MIN_INTERVAL_MS
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
        /** ~20fps ceiling on animation re-uploads. */
        const val ANIMATION_MIN_INTERVAL_MS = 50L
    }
}
