package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

/** Runs the bundled U2NetP foreground model without network or Play services. */
class SubjectMaskExtractor(
    context: Context,
    private val onResult: (requestId: Long, mask: Bitmap?) -> Unit
) : Closeable {

    private companion object {
        const val TAG = "SubjectMaskExtractor"
        const val MODEL_ASSET = "models/u2netp_320x320.tflite"
        const val INPUT_SIZE = 320
        const val THREAD_COUNT = 2
        const val CONFIDENT_FOREGROUND = 0.55f
        const val HIGH_CONFIDENCE = 0.75f
        const val MIN_FOREGROUND_FRACTION = 0.012f
        const val MIN_HIGH_CONFIDENCE_FRACTION = 0.003f
        const val MIN_RAW_CONFIDENCE = 0.40f
        const val MIN_CONFIDENCE_RANGE = 0.10f
        const val MASK_LOW = 0.28f
        const val MASK_HIGH = 0.72f
    }

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "canvas-subject-segmentation")
    }
    private val closeLock = Any()
    private val interpreterLock = Any()
    private val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
        .order(ByteOrder.nativeOrder())
    private val outputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 4)
        .order(ByteOrder.nativeOrder())
    private val sourcePixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val confidenceValues = FloatArray(INPUT_SIZE * INPUT_SIZE)
    private val maskPixels = IntArray(INPUT_SIZE * INPUT_SIZE)

    @Volatile private var closed = false
    private var interpreter: Interpreter? = null
    private var modelBuffer: ByteBuffer? = null

    fun extract(bitmap: Bitmap, requestId: Long) {
        if (closed || bitmap.width <= 0 || bitmap.height <= 0) return

        val inputBitmap = try {
            makeInputBitmap(bitmap)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare an image for subject segmentation", error)
            if (!closed) onResult(requestId, null)
            return
        }

        try {
            worker.execute {
                val mask = try {
                    if (closed) null else inferMask(inputBitmap)
                } catch (error: Throwable) {
                    // Throwable, not Exception: a large/malformed wallpaper
                    // can push the bundled TFLite model into OutOfMemoryError
                    // on lower-end devices, which is an Error, not an
                    // Exception — this is a best-effort visual feature, so
                    // degrade to "no mask" instead of crashing the app.
                    Log.w(TAG, "Bundled subject segmentation failed", error)
                    null
                } finally {
                    inputBitmap.recycle()
                }

                if (closed) {
                    mask?.recycle()
                } else {
                    onResult(requestId, mask)
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "Subject-segmentation request was rejected", error)
            inputBitmap.recycle()
            if (!closed) onResult(requestId, null)
        }
    }

    private fun inferMask(inputBitmap: Bitmap): Bitmap? {
        inputBitmap.getPixels(
            sourcePixels,
            0,
            INPUT_SIZE,
            0,
            0,
            INPUT_SIZE,
            INPUT_SIZE
        )

        inputBuffer.clear()
        for (pixel in sourcePixels) {
            inputBuffer.putFloat((((pixel shr 16) and 0xFF) / 255f - 0.485f) / 0.229f)
            inputBuffer.putFloat((((pixel shr 8) and 0xFF) / 255f - 0.456f) / 0.224f)
            inputBuffer.putFloat(((pixel and 0xFF) / 255f - 0.406f) / 0.225f)
        }
        inputBuffer.rewind()
        outputBuffer.clear()

        synchronized(interpreterLock) {
            if (closed) return null
            val engine = interpreter ?: createInterpreter().also { interpreter = it }
            engine.runForMultipleInputsOutputs(
                arrayOf(inputBuffer),
                hashMapOf<Int, Any>(0 to outputBuffer)
            )
        }
        outputBuffer.rewind()

        var rawMin = Float.POSITIVE_INFINITY
        var rawMax = Float.NEGATIVE_INFINITY
        for (index in confidenceValues.indices) {
            val value = outputBuffer.float.takeIf { it.isFinite() } ?: 0f
            confidenceValues[index] = value
            if (value < rawMin) rawMin = value
            if (value > rawMax) rawMax = value
        }

        val range = rawMax - rawMin
        if (rawMax < MIN_RAW_CONFIDENCE || range < MIN_CONFIDENCE_RANGE) return null

        var foregroundCount = 0
        var highConfidenceCount = 0
        var minX = INPUT_SIZE
        var minY = INPUT_SIZE
        var maxX = -1
        var maxY = -1

        for (index in confidenceValues.indices) {
            val normalized = ((confidenceValues[index] - rawMin) / range).coerceIn(0f, 1f)
            confidenceValues[index] = normalized
            if (normalized >= CONFIDENT_FOREGROUND) {
                foregroundCount++
                val x = index % INPUT_SIZE
                val y = index / INPUT_SIZE
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            if (normalized >= HIGH_CONFIDENCE) highConfidenceCount++
        }

        val pixelCount = confidenceValues.size
        val foregroundFraction = foregroundCount.toFloat() / pixelCount
        val highConfidenceFraction = highConfidenceCount.toFloat() / pixelCount
        val subjectWidth = maxX - minX + 1
        val subjectHeight = maxY - minY + 1
        val hasUsefulBounds =
            subjectWidth >= INPUT_SIZE * 0.04f && subjectHeight >= INPUT_SIZE * 0.04f

        if (foregroundFraction < MIN_FOREGROUND_FRACTION ||
            highConfidenceFraction < MIN_HIGH_CONFIDENCE_FRACTION ||
            !hasUsefulBounds
        ) {
            return null
        }

        for (index in confidenceValues.indices) {
            val t = ((confidenceValues[index] - MASK_LOW) / (MASK_HIGH - MASK_LOW))
                .coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val gray = (smooth * 255f).roundToInt()
            maskPixels[index] = 0xFF000000.toInt() or
                (gray shl 16) or (gray shl 8) or gray
        }
        return Bitmap.createBitmap(
            maskPixels,
            INPUT_SIZE,
            INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )
    }

    private fun createInterpreter(): Interpreter {
        val bytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        val directBuffer = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .put(bytes)
        directBuffer.rewind()
        modelBuffer = directBuffer
        return Interpreter(
            directBuffer,
            Interpreter.Options()
                .setNumThreads(THREAD_COUNT)
                .setUseXNNPACK(true)
        )
    }

    private fun makeInputBitmap(source: Bitmap): Bitmap {
        // Get most of the way to the model's input size in halving steps
        // first (see BitmapDownscale) so a full-resolution wallpaper isn't
        // squeezed down to 320x320 in one steep, aliasing-prone pass — that
        // single-pass squeeze is what let the same photo segment fine from
        // the smaller in-app preview bitmap but come back wrong for the
        // full-resolution wallpaper bitmap.
        val staged = BitmapDownscale.toStagedSize(source, INPUT_SIZE, INPUT_SIZE)
        if (staged.width == INPUT_SIZE && staged.height == INPUT_SIZE) return staged

        val target = createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(target).drawBitmap(
            staged,
            null,
            Rect(0, 0, INPUT_SIZE, INPUT_SIZE),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        staged.recycle()
        return target
    }

    override fun close() {
        val shouldClose = synchronized(closeLock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!shouldClose) return

        try {
            worker.execute(::closeInterpreter)
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "Subject-segmentation cleanup was rejected", error)
        } finally {
            worker.shutdown()
        }
    }

    private fun closeInterpreter() {
        synchronized(interpreterLock) {
            try {
                interpreter?.close()
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not close the subject-segmentation interpreter", error)
            } finally {
                interpreter = null
                modelBuffer = null
            }
        }
    }
}
