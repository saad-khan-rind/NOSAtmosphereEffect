package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.SubjectMaskCoordinator
import java.io.Closeable

/**
 * Segmentation and its Vulkan binding, for effects whose only reason to want a
 * subject mask is the clock's depth effect.
 *
 * Glass, Halftone and Atmosphere each grew this inline, because each already
 * wanted a mask for its own "background only" mode. Colour Fill and Frosted
 * have no such mode, so rather than paste the same logic into two more hosts
 * they share this — the Vulkan counterpart of
 * [com.app.nosatmosphereeffect.helper.GlesSubjectMask].
 *
 * ## The generation rule
 *
 * [SubjectMaskCoordinator] serves one extraction per generation, and a mask
 * that arrives for a superseded image must be dropped rather than uploaded.
 * Getting that wrong is not a crash — it is a mask aligned to the previous
 * photo, which reads as a subject-shaped smear in the wrong place.
 *
 * ## Failures are not fatal
 *
 * A host's prepareFrameOnWorker returning false takes the whole Vulkan backend
 * down permanently for the build. That is far too heavy a response to a
 * decorative overlay's mask failing to upload, so every path here logs and
 * degrades to "no depth this frame" instead of reporting failure upwards.
 *
 * ## Threading
 *
 * [configure] is safe from any thread. [onImageUploaded] and [uploadIfNeeded]
 * must run on the host's render worker.
 */
internal class VulkanClockDepthMask(
    context: Context,
    private val effectLabel: String,
    onMaskReady: () -> Unit
) : Closeable {

    private val coordinator = SubjectMaskCoordinator(context, onMaskReady)

    private var retainedMask: Bitmap? = null
    private var maskRevision = 0L
    private var uploadedRevision = NO_REVISION
    private var currentGeneration = NO_GENERATION

    val enabled: Boolean
        get() = coordinator.enabled

    /** Returns true when the setting changed, so callers can force a reload. */
    fun configure(wanted: Boolean): Boolean {
        val changed = coordinator.configure(wanted)
        if (changed && !wanted) {
            retainedMask.recycleSafely()
            retainedMask = null
            maskRevision++
        }
        return changed
    }

    /**
     * Call from onWallpaperUploadedOnWorker with the image that was just
     * uploaded. Dispatches an extraction if one is wanted.
     */
    fun onImageUploaded(bitmap: Bitmap, textureGeneration: Long) {
        currentGeneration = textureGeneration
        retainedMask.recycleSafely()
        retainedMask = null
        maskRevision++
        if (!coordinator.enabled || bitmap.isRecycled) return
        coordinator.request(bitmap, textureGeneration)
    }

    /**
     * Call from prepareFrameOnWorker. Returns true when the effect's state
     * should now report a usable mask.
     *
     * The upload is keyed on a revision rather than repeated every frame:
     * segmentation results change only when the image or the setting does.
     */
    fun uploadIfNeeded(
        handle: Long,
        textureGeneration: Long,
        upload: (Long, Bitmap) -> Boolean,
        clear: (Long) -> Boolean
    ): Boolean {
        consumePending(textureGeneration)

        val mask = retainedMask
        if (!coordinator.enabled || mask == null || mask.isRecycled) {
            if (uploadedRevision != NO_REVISION) {
                // Back to the engine's clear texture, so a stale mask cannot
                // keep cutting a subject out of a frame that no longer has one.
                runCatching { clear(handle) }
                uploadedRevision = NO_REVISION
            }
            return false
        }
        if (uploadedRevision == maskRevision) return true

        val uploaded = runCatching { upload(handle, mask) }.getOrElse { failure ->
            Log.w(TAG, "Unable to upload the $effectLabel clock depth mask", failure)
            false
        }
        if (!uploaded) return uploadedRevision != NO_REVISION
        uploadedRevision = maskRevision
        return true
    }

    /**
     * Call from onSurfaceResetOnWorker: the new surface's descriptor set holds
     * no mask, so the next frame must re-upload even though nothing changed.
     */
    fun onSurfaceReset() {
        coordinator.discardPending()
        uploadedRevision = NO_REVISION
    }

    override fun close() {
        coordinator.close()
        retainedMask.recycleSafely()
        retainedMask = null
    }

    private fun consumePending(textureGeneration: Long) {
        val pending = coordinator.takePending() ?: return
        val stale = !coordinator.enabled ||
            pending.generation != currentGeneration ||
            pending.generation != textureGeneration
        if (stale) {
            pending.bitmap.recycleSafely()
            return
        }
        retainedMask.recycleSafely()
        retainedMask = pending.bitmap
        maskRevision++
    }

    private fun Bitmap?.recycleSafely() {
        if (this != null && !isRecycled) recycle()
    }

    private companion object {
        const val TAG = "VulkanClockDepthMask"
        const val NO_REVISION = -1L
        const val NO_GENERATION = -1L
    }
}
