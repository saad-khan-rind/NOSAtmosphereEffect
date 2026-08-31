package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Uses the optional Google Play services subject model only when it is already
 * installed. Model downloads remain an explicit action in Advanced Settings.
 */
class SubjectMaskExtractor(
    context: Context,
    private val onResult: (requestId: Long, mask: Bitmap?) -> Unit
) : Closeable {

    private companion object {
        const val TAG = "SubjectMaskExtractor"
        const val MAX_INPUT_SIDE = 1024
        const val CONFIDENT_FOREGROUND = 0.55f
        const val HIGH_CONFIDENCE = 0.75f
        const val MIN_FOREGROUND_FRACTION = 0.012f
        const val MIN_HIGH_CONFIDENCE_FRACTION = 0.003f
        const val MAX_FOREGROUND_FRACTION = 0.90f
        const val MASK_LOW = 0.28f
        const val MASK_HIGH = 0.72f
    }

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
    )
    private val moduleClient = ModuleInstall.getClient(context.applicationContext)

    @Volatile private var closed = false

    fun extract(bitmap: Bitmap, requestId: Long) {
        if (closed || bitmap.width <= 0 || bitmap.height <= 0) return
        val inputBitmap = try {
            makeInputBitmap(bitmap)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare an image for subject segmentation", error)
            if (!closed) onResult(requestId, null)
            return
        }

        moduleClient.areModulesAvailable(segmenter)
            .addOnSuccessListener { availability ->
                when {
                    closed -> inputBitmap.recycle()
                    !availability.areModulesAvailable() -> {
                        inputBitmap.recycle()
                        onResult(requestId, null)
                    }
                    else -> processInput(inputBitmap, requestId)
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Could not check subject-segmentation module availability", error)
                inputBitmap.recycle()
                if (!closed) onResult(requestId, null)
            }
    }

    private fun processInput(inputBitmap: Bitmap, requestId: Long) {
        try {
            segmenter.process(InputImage.fromBitmap(inputBitmap, 0))
                .addOnSuccessListener { result ->
                    val mask = if (closed) {
                        null
                    } else {
                        try {
                            run maskComputation@ {
                                val confidence = result.foregroundConfidenceMask
                                    ?: return@maskComputation null
                                val count = inputBitmap.width * inputBitmap.height
                                val values = FloatArray(count)
                                val buffer = confidence.duplicate()
                                buffer.rewind()
                                if (buffer.remaining() < count) {
                                    Log.w(
                                        TAG,
                                        "Subject mask contained ${buffer.remaining()} values; expected $count"
                                    )
                                    return@maskComputation null
                                }

                                var foregroundCount = 0
                                var highConfidenceCount = 0
                                var minX = inputBitmap.width
                                var minY = inputBitmap.height
                                var maxX = -1
                                var maxY = -1

                                for (index in 0 until count) {
                                    val value = buffer.get().takeIf { it.isFinite() }
                                        ?.coerceIn(0f, 1f) ?: 0f
                                    values[index] = value
                                    if (value >= CONFIDENT_FOREGROUND) {
                                        foregroundCount++
                                        val x = index % inputBitmap.width
                                        val y = index / inputBitmap.width
                                        if (x < minX) minX = x
                                        if (x > maxX) maxX = x
                                        if (y < minY) minY = y
                                        if (y > maxY) maxY = y
                                    }
                                    if (value >= HIGH_CONFIDENCE) highConfidenceCount++
                                }

                                val foregroundFraction = foregroundCount.toFloat() / count
                                val highConfidenceFraction = highConfidenceCount.toFloat() / count
                                val subjectWidth = maxX - minX + 1
                                val subjectHeight = maxY - minY + 1
                                val hasUsefulBounds =
                                    subjectWidth >= inputBitmap.width * 0.04f &&
                                        subjectHeight >= inputBitmap.height * 0.04f

                                if (foregroundFraction !in MIN_FOREGROUND_FRACTION..MAX_FOREGROUND_FRACTION ||
                                    highConfidenceFraction < MIN_HIGH_CONFIDENCE_FRACTION ||
                                    !hasUsefulBounds
                                ) {
                                    return@maskComputation null
                                }

                                val pixels = IntArray(count)
                                for (index in values.indices) {
                                    val value =
                                        ((values[index] - MASK_LOW) / (MASK_HIGH - MASK_LOW))
                                            .coerceIn(0f, 1f)
                                    val smooth = value * value * (3f - 2f * value)
                                    val gray = (smooth * 255f).roundToInt()
                                    pixels[index] = 0xFF000000.toInt() or
                                        (gray shl 16) or (gray shl 8) or gray
                                }
                                Bitmap.createBitmap(
                                    pixels,
                                    inputBitmap.width,
                                    inputBitmap.height,
                                    Bitmap.Config.ARGB_8888
                                )
                            }
                        } catch (error: Throwable) {
                            Log.w(TAG, "Could not create a subject mask", error)
                            null
                        }
                    }

                    if (closed) {
                        mask?.recycle()
                    } else {
                        onResult(requestId, mask)
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Subject segmentation failed", error)
                    if (!closed) onResult(requestId, null)
                }
                .addOnCompleteListener {
                    inputBitmap.recycle()
                }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not start subject segmentation", error)
            inputBitmap.recycle()
            if (!closed) onResult(requestId, null)
        }
    }

    private fun makeInputBitmap(source: Bitmap): Bitmap {
        val longestSide = max(source.width, source.height)
        if (longestSide <= MAX_INPUT_SIDE) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }

        val scale = MAX_INPUT_SIDE.toFloat() / longestSide
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        return BitmapDownscale.toStagedSize(source, width, height)
    }

    override fun close() {
        if (closed) return
        closed = true
        segmenter.close()
    }
}
