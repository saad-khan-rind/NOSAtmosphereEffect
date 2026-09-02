package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.SystemClock
import android.util.Log

/**
 * Keeps a GL texture in sync with the shared [ClockFaceRenderer].
 *
 * GL calls in [ensureUpToDate] must run on the renderer's GL thread — same
 * constraint as the rest of AtmosphereRenderer's texture handling.
 */
class ClockTextureProvider(context: Context) {

    private val face = ClockFaceRenderer(context)

    @Volatile var textureId: Int = 0
        private set
    @Volatile var textureWidth: Int = 0
        private set
    @Volatile var textureHeight: Int = 0
        private set

    /** Dimensions of the storage currently allocated for [textureId]. */
    private var allocatedWidth: Int = 0
    private var allocatedHeight: Int = 0

    val aspectRatio: Float
        get() = if (textureHeight > 0) {
            textureWidth.toFloat() / textureHeight.toFloat()
        } else {
            1f
        }

    var style: ClockStyle
        get() = face.style
        set(value) { face.style = value }

    var showSeconds: Boolean
        get() = face.showSeconds
        set(value) { face.showSeconds = value }

    var animateDigits: Boolean
        get() = face.animateDigits
        set(value) { face.animateDigits = value }

    /**
     * True while a digit transition is in flight, so the renderer knows to
     * ask for another frame. Without this the animation would only advance
     * when something else happened to trigger a draw.
     */
    fun isAnimating(): Boolean = face.isAnimating(SystemClock.uptimeMillis())

    /** True when a redraw is due, animation or not. */
    fun needsRender(): Boolean =
        face.needsRender(System.currentTimeMillis(), SystemClock.uptimeMillis())

    /**
     * Call once per frame while the clock is enabled. Cheap when nothing has
     * changed. Returns true if the texture is ready to draw.
     */
    fun ensureUpToDate(): Boolean {
        val uptime = SystemClock.uptimeMillis()
        val bitmap = try {
            face.render(
                nowMillis = System.currentTimeMillis(),
                uptimeMs = uptime,
                minimumIntervalMs = ANIMATION_MIN_INTERVAL_MS
            )
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to render the Atmosphere clock face", failure)
            return textureId != 0
        }

        if (bitmap == null) return textureId != 0

        return try {
            uploadTexture(bitmap)
            textureWidth = bitmap.width
            textureHeight = bitmap.height
            true
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to upload the Atmosphere clock texture", failure)
            // Force a full re-upload next time rather than leaving a
            // half-initialised texture bound.
            face.invalidate()
            textureId != 0
        }
    }

    fun refreshClockFormatPreference() {
        face.refreshFormat()
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        allocatedWidth = 0
        allocatedHeight = 0
        textureWidth = 0
        textureHeight = 0
        face.release()
    }

    /**
     * Call from onSurfaceCreated after an EGL context loss. The old texture
     * id belonged to the destroyed context — deleting it there would be
     * meaningless (or could collide with an unrelated id in the new one), so
     * just forget it and let [ensureUpToDate] allocate a fresh texture.
     */
    fun resetForNewContext() {
        textureId = 0
        textureWidth = 0
        textureHeight = 0
        allocatedWidth = 0
        allocatedHeight = 0
        face.invalidate()
    }

    private fun uploadTexture(bitmap: Bitmap) {
        // Drain GL errors left over from earlier, unrelated calls this frame
        // (e.g. a blob-array overflow elsewhere in onDrawFrame) — otherwise
        // the check below can misattribute a stray error to this upload and
        // throw, silently disabling the clock every time something upstream
        // leaves an unchecked error queued.
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            // intentionally empty: just flushing the error queue
        }

        val isNewTexture = textureId == 0
        if (isNewTexture) {
            val generated = IntArray(1)
            GLES30.glGenTextures(1, generated, 0)
            check(generated[0] != 0) { "OpenGL did not create a clock texture" }
            textureId = generated[0]
            allocatedWidth = 0
            allocatedHeight = 0
        }

        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            if (isNewTexture) {
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
            }

            // ClockFaceRenderer keeps the bitmap a fixed size for a given
            // face, so after the first upload this is a texSubImage2D into
            // existing storage — which matters because the digit animation
            // re-uploads at frame rate for half a second on every change.
            if (
                allocatedWidth == bitmap.width &&
                allocatedHeight == bitmap.height
            ) {
                GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
            } else {
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                allocatedWidth = bitmap.width
                allocatedHeight = bitmap.height
            }

            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) {
                "OpenGL error 0x${error.toString(16)} while uploading the clock texture"
            }
        } catch (failure: RuntimeException) {
            if (isNewTexture) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = 0
            }
            allocatedWidth = 0
            allocatedHeight = 0
            throw failure
        }
    }

    private companion object {
        const val TAG = "ClockTextureProvider"
        /** ~30fps ceiling on animation re-uploads. */
        const val ANIMATION_MIN_INTERVAL_MS = 32L
    }
}
