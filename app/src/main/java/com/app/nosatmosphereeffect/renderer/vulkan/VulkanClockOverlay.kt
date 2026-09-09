package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.ClockOverlayState

/**
 * The Vulkan half of the wallpaper clock, packaged so an effect host gains the
 * feature by owning one field and calling [uploadIfNeeded] from its
 * prepare-frame hook.
 *
 * VulkanAtmosphereHost predates this and keeps its own inlined copy;
 * everything else goes through here.
 *
 * ## Threading
 *
 * [applyState] and [beginEntry] are called from whichever thread updated the
 * render state; everything that touches the face renderer happens inside
 * [uploadIfNeeded] on the host's single worker. That is why the incoming state
 * is staged in a volatile rather than pushed straight into the uploader: the
 * style and seconds setters recycle the face bitmap, which the worker is the
 * only thread allowed to touch.
 *
 * ## Failures are not fatal
 *
 * A host's prepareFrameOnWorker returning false takes the entire Vulkan
 * backend down permanently for the build (see VulkanSingleImageHost.drawOnWorker
 * and VulkanFailureStore). That is far too heavy a response to a decorative
 * overlay failing to upload, so every path here logs and degrades to "no
 * clock this frame" instead of reporting failure upwards.
 */
internal class VulkanClockOverlay(
    context: Context,
    private val effectLabel: String
) {
    private val uploader = VulkanClockTextureUploader(context)

    @Volatile private var pendingState: ClockOverlayState? = ClockOverlayState()
    @Volatile private var appliedState: ClockOverlayState = ClockOverlayState()
    @Volatile private var pendingEntry = false
    @Volatile private var pendingFormatRefresh = false

    /** The most recent settings, whether or not the worker has seen them. */
    val state: ClockOverlayState
        get() = pendingState ?: appliedState

    val aspectRatio: Float
        get() = uploader.aspectRatio

    val hasUploadedFace: Boolean
        get() = uploader.hasUploadedFace

    fun applyState(next: ClockOverlayState) {
        pendingState = next.sanitized()
    }

    /** Plays the entry animation once the clock would actually be on screen. */
    fun beginEntry() {
        pendingEntry = true
    }

    /**
     * Re-reads the system 12/24-hour setting and forces a re-upload. Safe from
     * any thread — the work happens on the next prepared frame.
     */
    fun onTimeChanged() {
        pendingFormatRefresh = true
    }

    /**
     * Uploads a clock face when there is one to upload, and keeps the frame
     * pump running while an animation is in flight.
     *
     * [effectiveOpacity] is the clock's strength for this frame with the
     * lock/home fade already folded in — the same number the shader is given.
     * [upload] performs the native call; [requestRender] asks for another
     * frame.
     *
     * Returns true when the face texture or its aspect ratio changed, so the
     * caller knows to refresh the fields the shader reads.
     */
    fun uploadIfNeeded(
        effectiveOpacity: Float,
        upload: (Bitmap) -> Boolean,
        requestRender: () -> Unit
    ): Boolean {
        pendingState?.let { next ->
            pendingState = null
            appliedState = next
            uploader.style = next.style
            uploader.showSeconds = next.showSeconds
            uploader.animateDigits = next.animate
            uploader.animateEntry = next.animate
            uploader.color = next.color
            uploader.hourFormatOverride = next.hourFormatOverride
        }
        if (pendingFormatRefresh) {
            pendingFormatRefresh = false
            uploader.refreshClockFormatPreference()
            uploader.reset()
        }
        if (!appliedState.enabled) return false

        // Held until the clock would actually be on screen, so waking onto the
        // wrong side of the transition does not spend the entry animation
        // behind a zero opacity.
        if (pendingEntry && effectiveOpacity > 0f) {
            pendingEntry = false
            uploader.beginEntry()
        }

        var changed = false
        val bitmap = try {
            uploader.renderIfChanged()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to render the Vulkan $effectLabel clock face", failure)
            null
        }

        if (bitmap != null) {
            // The bitmap is owned and reused by the face renderer — never
            // recycle it here.
            if (upload(bitmap)) {
                uploader.markUploaded()
                changed = true
            } else {
                Log.w(TAG, "Unable to upload the Vulkan $effectLabel clock texture")
            }
        }

        // A static wallpaper produces no frames on its own, so without this the
        // digit animation would freeze part-way and the time would only change
        // when something unrelated triggered a draw.
        if (uploader.isAnimating()) requestRender()
        return changed
    }

    /**
     * Call after a surface reset: the new surface's descriptor set has no clock
     * content yet, so the next frame must re-upload even though the displayed
     * time has not changed.
     */
    fun reset() {
        uploader.reset()
    }

    fun release() {
        uploader.release()
    }

    private companion object {
        const val TAG = "VulkanClockOverlay"
    }
}
