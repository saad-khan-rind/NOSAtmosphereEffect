package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.text.format.DateFormat
import android.util.Log
import androidx.core.graphics.createBitmap
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders "HH:mm" (respecting the device's 12/24-hour setting) to a
 * transparent bitmap and keeps a GL texture in sync with it.
 *
 * GL calls in [ensureUpToDate] must run on the renderer's GL thread — same
 * constraint as the rest of [AtmosphereRenderer]'s texture handling.
 */
class ClockTextureProvider(private val context: Context) {

    @Volatile var textureId: Int = 0
        private set
    @Volatile var textureWidth: Int = 0
        private set
    @Volatile var textureHeight: Int = 0
        private set

    private var lastRenderedMinuteKey: Long = Long.MIN_VALUE
    private var formatter: DateTimeFormatter = build24HourAwareFormatter()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        setShadowLayer(SHADOW_RADIUS_PX, 0f, SHADOW_DY_PX, SHADOW_COLOR)
    }

    /**
     * Call once per frame while the clock is enabled. Cheap when the
     * minute hasn't changed. Returns true if the texture is ready to draw.
     */
    fun ensureUpToDate(): Boolean {
        val now = LocalTime.now()
        val minuteKey = now.hour * 60L + now.minute
        if (minuteKey == lastRenderedMinuteKey && textureId != 0) {
            return true
        }
        return try {
            val bitmap = renderBitmap(now)
            try {
                textureId = uploadTexture(bitmap, textureId)
                textureWidth = bitmap.width
                textureHeight = bitmap.height
                lastRenderedMinuteKey = minuteKey
                true
            } finally {
                bitmap.recycle()
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to render the Atmosphere clock texture", failure)
            false
        }
    }

    /** Re-reads the 12/24-hour system setting; call if it may have changed. */
    fun refreshClockFormatPreference() {
        formatter = build24HourAwareFormatter()
        lastRenderedMinuteKey = Long.MIN_VALUE
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        lastRenderedMinuteKey = Long.MIN_VALUE
    }

    /**
     * Call from onSurfaceCreated after an EGL context loss. The old texture
     * id belonged to the destroyed context — deleting it there would be
     * meaningless (or could collide with an unrelated id in the new one),
     * so just forget it and let [ensureUpToDate] allocate a fresh texture.
     */
    fun resetForNewContext() {
        textureId = 0
        textureWidth = 0
        textureHeight = 0
        lastRenderedMinuteKey = Long.MIN_VALUE
    }

    private fun build24HourAwareFormatter(): DateTimeFormatter {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    }

    private fun renderBitmap(now: LocalTime): Bitmap {
        val text = formatter.format(now)
        textPaint.textSize = TEXT_SIZE_PX
        val metrics = textPaint.fontMetrics
        val textWidth = textPaint.measureText(text)

        val width = (textWidth + PADDING_PX * 2f).toInt().coerceAtLeast(1)
        val height = (metrics.bottom - metrics.top + PADDING_PX * 2f).toInt().coerceAtLeast(1)

        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val baseline = PADDING_PX - metrics.top
        canvas.drawText(text, width / 2f, baseline, textPaint)
        return bitmap
    }

    private fun uploadTexture(bitmap: Bitmap, existingTextureId: Int): Int {
        // Drain any GL errors left over from earlier, unrelated calls this
        // frame (e.g. a blob-array overflow elsewhere in onDrawFrame) —
        // otherwise the check() below can misattribute a stray error to
        // this upload and throw, silently disabling the clock every time
        // something upstream leaves an unchecked error queued.
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            // intentionally empty: just flushing the error queue
        }
        val isNewTexture = existingTextureId == 0
        val textureId = if (isNewTexture) {
            val generated = IntArray(1)
            GLES30.glGenTextures(1, generated, 0)
            generated[0]
        } else {
            existingTextureId
        }
        check(textureId != 0) { "OpenGL did not create a clock texture" }
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
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
            // Bitmap dimensions change every render (text width varies), so
            // always reallocate storage rather than texSubImage2D into it.
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            val error = GLES30.glGetError()
            check(error == GLES30.GL_NO_ERROR) {
                "OpenGL error 0x${error.toString(16)} while uploading the clock texture"
            }
            return textureId
        } catch (failure: RuntimeException) {
            if (isNewTexture) {
                GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            }
            throw failure
        }
    }

    private companion object {
        const val TAG = "ClockTextureProvider"
        const val TEXT_SIZE_PX = 640f
        const val PADDING_PX = 60f
        const val SHADOW_RADIUS_PX = 40f
        const val SHADOW_DY_PX = 10f
        const val SHADOW_COLOR = 0x99000000.toInt()
    }
}
