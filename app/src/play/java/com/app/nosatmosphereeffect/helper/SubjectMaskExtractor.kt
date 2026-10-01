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
 *
 * [onResult]'s `failed` is true when no mask came back because something went
 * wrong — Play services unreachable or updating, a caught error, a skipped
 * attempt after a crash — rather than because the photo has no usable subject.
 * Those are worth asking again later; see [SubjectMaskCoordinator].
 */
class SubjectMaskExtractor(
    context: Context,
    private val onResult: (requestId: Long, mask: Bitmap?, failed: Boolean) -> Unit
) : Closeable {

    private val appContext = context.applicationContext

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
    private val lock = Any()
    private var inFlight = 0
    private var segmenterClosed = false

    fun extract(bitmap: Bitmap, requestId: Long) {
        if (closed || bitmap.width <= 0 || bitmap.height <= 0) return
        val inputBitmap = try {
            makeInputBitmap(bitmap)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare an image for subject segmentation", error)
            SubjectMaskDiagnostics.recordFailure("Preparing image (Play)", error)
            if (!closed) onResult(requestId, null, true)
            return
        }

        if (!acquire()) {
            inputBitmap.recycle()
            return
        }
        try {
            moduleClient.areModulesAvailable(segmenter)
                .addOnSuccessListener { availability ->
                    when {
                        closed -> inputBitmap.recycle()
                        !availability.areModulesAvailable() -> {
                            SubjectMaskDiagnostics.recordRejection(
                                "Play services subject model isn't installed yet"
                            )
                            inputBitmap.recycle()
                            onResult(requestId, null, false)
                        }
                        else -> processInput(inputBitmap, requestId)
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Could not check subject-segmentation module availability", error)
                    SubjectMaskDiagnostics.recordFailure("Checking module availability", error)
                    inputBitmap.recycle()
                    if (!closed) onResult(requestId, null, true)
                }
                .addOnCompleteListener { release() }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not check subject-segmentation module availability", error)
            SubjectMaskDiagnostics.recordFailure("Checking module availability", error)
            inputBitmap.recycle()
            release()
            if (!closed) onResult(requestId, null, true)
        }
    }

    /**
     * Keeps the segmenter open while work is in flight. Closing it mid-inference
     * unmaps the Play services model under the running interpreter (SIGBUS in
     * dl-MlkitSubjectSegmentation), so close() defers to the last release().
     */
    private fun acquire(): Boolean = synchronized(lock) {
        if (closed || segmenterClosed) return false
        inFlight++
        true
    }

    private fun release() {
        val closeNow = synchronized(lock) {
            inFlight = (inFlight - 1).coerceAtLeast(0)
            closed && inFlight == 0 && !segmenterClosed && run {
                segmenterClosed = true
                true
            }
        }
        if (closeNow) closeSegmenter()
    }

    private fun closeSegmenter() {
        runCatching { segmenter.close() }
            .onFailure { error -> Log.w(TAG, "Could not close the subject segmenter", error) }
    }

    private fun processInput(inputBitmap: Bitmap, requestId: Long) {
        if (!acquire()) {
            inputBitmap.recycle()
            return
        }
        if (!SegmentationCrashGuard.beginAttempt(appContext)) {
            inputBitmap.recycle()
            release()
            // Skipped once after a crash: worth another try. Disabled for
            // good after repeated crashes: not until it is reset.
            if (!closed) onResult(requestId, null, !SegmentationCrashGuard.isDisabled(appContext))
            return
        }
        try {
            segmenter.process(InputImage.fromBitmap(inputBitmap, 0))
                .addOnSuccessListener { result ->
                    var buildFailed = false
                    val mask = if (closed) {
                        null
                    } else {
                        try {
                            run maskComputation@ {
                                val confidence = result.foregroundConfidenceMask
                                    ?: run {
                                        SubjectMaskDiagnostics.recordRejection(
                                            "No confidence mask returned"
                                        )
                                        return@maskComputation null
                                    }
                                val count = inputBitmap.width * inputBitmap.height
                                val values = FloatArray(count)
                                val buffer = confidence.duplicate()
                                buffer.rewind()
                                if (buffer.remaining() < count) {
                                    Log.w(
                                        TAG,
                                        "Subject mask contained ${buffer.remaining()} values; expected $count"
                                    )
                                    SubjectMaskDiagnostics.recordRejection(
                                        "Mask data was the wrong size"
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
                                    SubjectMaskDiagnostics.recordRejection(
                                        when {
                                            foregroundFraction > MAX_FOREGROUND_FRACTION ->
                                                "Subject fills too much of the photo " +
                                                    "(${(foregroundFraction * 100).roundToInt()}% " +
                                                    "— try a photo with more visible " +
                                                    "background)"
                                            foregroundFraction < MIN_FOREGROUND_FRACTION ->
                                                "No confident subject found in the photo"
                                            highConfidenceFraction < MIN_HIGH_CONFIDENCE_FRACTION ->
                                                "Subject detected but confidence was too low"
                                            else -> "Subject bounds were too small/thin to use"
                                        }
                                    )
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
                            SubjectMaskDiagnostics.recordFailure("Building mask bitmap", error)
                            buildFailed = true
                            null
                        }
                    }

                    if (mask != null) SubjectMaskDiagnostics.recordSuccess()

                    SegmentationCrashGuard.endAttempt(appContext)
                    if (closed) {
                        mask?.recycle()
                    } else {
                        onResult(requestId, mask, buildFailed)
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "Subject segmentation failed", error)
                    SubjectMaskDiagnostics.recordFailure("Segmentation", error)
                    SegmentationCrashGuard.endAttempt(appContext)
                    if (!closed) onResult(requestId, null, true)
                }
                .addOnCompleteListener {
                    inputBitmap.recycle()
                    release()
                }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not start subject segmentation", error)
            SubjectMaskDiagnostics.recordFailure("Starting segmentation", error)
            SegmentationCrashGuard.endAttempt(appContext)
            inputBitmap.recycle()
            release()
            if (!closed) onResult(requestId, null, true)
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
        val closeNow = synchronized(lock) {
            if (closed) return
            closed = true
            inFlight == 0 && !segmenterClosed && run {
                segmenterClosed = true
                true
            }
        }
        if (closeNow) closeSegmenter()
    }
}
