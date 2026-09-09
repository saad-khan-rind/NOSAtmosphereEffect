package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import java.io.Closeable

/**
 * A subject mask and its GL texture, packaged for effects that had no
 * segmentation of their own.
 *
 * Glass, Halftone and Atmosphere each grew this pipeline inline, because each
 * already wanted a mask for its own "background only" mode. Colour Fill,
 * Frosted and Sketch have no such mode — the clock's depth effect is their
 * only reason to want a mask — so rather than paste the same forty lines into
 * three more renderers, they share this.
 *
 * ## The generation rule
 *
 * [SubjectMaskCoordinator] serves exactly one extraction per generation, so
 * the generation has to advance whenever the image does, and a mask that
 * arrives for a superseded image has to be dropped. Getting that wrong is not
 * a crash — it is a mask silently aligned to the previous photo, which reads
 * as a subject-shaped smear in the wrong place. [onImageLoaded] and
 * [applyPending] are the two halves of that contract.
 *
 * ## Threading
 *
 * Everything except [configure] and [close] must run on the GL thread.
 */
internal class GlesSubjectMask(
    private val context: Context,
    private val label: String,
    onMaskReady: () -> Unit
) : Closeable {

    private val coordinator = SubjectMaskCoordinator(context, onMaskReady)
    private var generationCounter = 0L
    private var currentGeneration = 0L

    var textureId: Int = 0
        private set

    /** True once a real mask has been uploaded for the current image. */
    var hasMask: Boolean = false
        private set

    /** Whether segmentation is switched on at all. */
    val enabled: Boolean
        get() = coordinator.enabled

    /** True only when the shader may actually sample the mask. */
    val ready: Boolean
        get() = coordinator.enabled && hasMask && textureId != 0

    /** Returns true when the setting changed, so callers can force a reload. */
    fun configure(wanted: Boolean): Boolean = coordinator.configure(wanted)

    /**
     * Call on the GL thread immediately after a new wallpaper bitmap has been
     * uploaded, with that same bitmap. Advances the generation, so any mask
     * still in flight for the previous image is discarded on arrival.
     */
    fun onImageLoaded(bitmap: Bitmap) {
        generationCounter++
        currentGeneration = generationCounter
        hasMask = false
        coordinator.request(bitmap, currentGeneration)
    }

    /**
     * Call once per frame on the GL thread, before drawing. Uploads a mask if
     * one has arrived and still matches the image on screen.
     */
    fun applyPending() {
        val pending = coordinator.takePending() ?: return
        try {
            if (pending.generation != currentGeneration || !coordinator.enabled) {
                return
            }
            textureId = uploadMaskTexture(pending.bitmap, textureId)
            hasMask = true
        } finally {
            if (!pending.bitmap.isRecycled) pending.bitmap.recycle()
        }
    }

    /**
     * Binds the mask for this frame and points [uniformName] at it.
     *
     * Bound even when no mask exists: the sampler must reference a real
     * texture unit either way, and the shader gates on its own "mask ready"
     * uniform rather than on what happens to be bound.
     */
    fun bind(
        programId: Int,
        textureUnit: Int,
        textureUnitIndex: Int,
        uniformName: String
    ) {
        GLES30.glActiveTexture(textureUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(programId, uniformName),
            textureUnitIndex
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * Call from onSurfaceCreated after an EGL context loss. The texture id
     * belonged to the destroyed context, so it is forgotten rather than
     * deleted, and the generation is reset so the next image re-requests.
     */
    fun resetForNewContext() {
        textureId = 0
        hasMask = false
        currentGeneration = 0L
        coordinator.discardPending()
    }

    /** Deletes the mask texture. GL thread only. */
    fun deleteTexture() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
        textureId = 0
        hasMask = false
    }

    override fun close() {
        coordinator.close()
    }

    private fun uploadMaskTexture(bitmap: Bitmap, existingTextureId: Int): Int {
        val isNewTexture = existingTextureId == 0
        val target = if (isNewTexture) {
            IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        } else {
            existingTextureId
        }
        check(target != 0) { "OpenGL did not create a $label subject mask" }
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, target)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_S,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_WRAP_T,
                GLES30.GL_CLAMP_TO_EDGE
            )
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) {
                "OpenGL error 0x${error.toString(16)} uploading a $label subject mask"
            }
            return target
        } catch (failure: RuntimeException) {
            if (isNewTexture) {
                GLES30.glDeleteTextures(1, intArrayOf(target), 0)
            }
            throw failure
        }
    }
}
