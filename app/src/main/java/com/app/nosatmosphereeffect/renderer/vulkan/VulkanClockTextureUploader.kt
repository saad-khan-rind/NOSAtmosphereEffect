package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.core.graphics.createBitmap
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders "HH:mm" (respecting the device's 12/24-hour setting) to a
 * transparent bitmap for upload via [VulkanAtmosphereNative.nativeUploadClock].
 *
 * Deliberately a separate class from
 * [com.app.nosatmosphereeffect.helper.ClockTextureProvider] (the GLES
 * equivalent) rather than a shared one: the two differ in their final
 * upload step (GL texture vs native Vulkan bitmap upload) and keeping them
 * independent means this addition can't regress the already-working GLES
 * clock. The Canvas/Paint rendering logic below is intentionally the same
 * shape as that class — see it for the reasoning on each choice (padding,
 * shadow, minute-granularity caching).
 *
 * Not thread-confined by itself — the caller (VulkanAtmosphereHost) only
 * ever touches it from its single worker thread, same as everything else
 * in that class.
 */
internal class VulkanClockTextureUploader(private val context: Context) {

    var textureWidth: Int = 0
        private set
    var textureHeight: Int = 0
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
     * Returns a freshly-rendered bitmap only when the minute has changed
     * since the last call (or on the very first call) — null otherwise, so
     * the caller can skip uploading when there's nothing new. The caller
     * owns the returned bitmap and must recycle it once uploaded.
     */
    fun renderIfMinuteChanged(): Bitmap? {
        val now = LocalTime.now()
        val minuteKey = now.hour * 60L + now.minute
        if (minuteKey == lastRenderedMinuteKey && textureWidth > 0) return null

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

        textureWidth = width
        textureHeight = height
        lastRenderedMinuteKey = minuteKey
        return bitmap
    }

    /** Re-reads the 12/24-hour system setting; call if it may have changed. */
    fun refreshClockFormatPreference() {
        formatter = build24HourAwareFormatter()
        lastRenderedMinuteKey = Long.MIN_VALUE
    }

    /** Call after a surface reset so the next frame re-uploads, even if
     * the clock text itself hasn't changed — the new surface's descriptor
     * set has no clock content yet. */
    fun reset() {
        lastRenderedMinuteKey = Long.MIN_VALUE
        textureWidth = 0
        textureHeight = 0
    }

    private fun build24HourAwareFormatter(): DateTimeFormatter {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    }

    private companion object {
        const val TEXT_SIZE_PX = 640f
        const val PADDING_PX = 60f
        const val SHADOW_RADIUS_PX = 40f
        const val SHADOW_DY_PX = 10f
        const val SHADOW_COLOR = 0x99000000.toInt()
    }
}
