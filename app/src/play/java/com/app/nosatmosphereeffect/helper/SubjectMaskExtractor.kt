package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.dynamite.DynamiteModule
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
 *
 * Google updates the model on its own schedule, and a bad version can crash
 * the app natively (SIGBUS inside its code) or answer nonsense. Before every
 * use the model's version is read, without loading it: a version known to be
 * broken ([KNOWN_BAD_MODEL_VERSIONS]) is never used, and any other version is
 * paused by [SegmentationCrashGuard] after one crash or one broken answer,
 * until Play services moves to another version. A native crash can't be
 * caught, so not calling a broken model is the only way to keep it from
 * crashing the app.
 */
class SubjectMaskExtractor(
    context: Context,
    private val onResult: (requestId: Long, mask: Bitmap?, failed: Boolean) -> Unit
) : Closeable {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "SubjectMaskExtractor"
        private const val MODULE_ID = "com.google.android.gms.mlkit_subject_segmentation"

        /**
         * Versions of Google's subject module known to be broken, by their
         * first six digits (module 263635001, shipped as 263635100400):
         * 263635 crashed with SIGBUS in production and marked every pixel of
         * every photo as subject.
         */
        private val KNOWN_BAD_MODEL_VERSIONS = setOf(263_635)

        /** Last version read, for screens that must not wait on Play services. */
        @Volatile private var cachedModelVersion: Int = 0

        /**
         * The subject model's version as the crash guard keys it, or null
         * when unknown. Never queries Play services: safe on the main thread.
         */
        fun modelVersion(context: Context): String? =
            cachedModelVersion.takeIf { it > 0 }?.let { "mlkit-subject:$it" }

        /** Reads the model's version from Play services. Off the main thread only. */
        private fun readModelVersion(context: Context): Int =
            runCatching { DynamiteModule.getRemoteVersion(context, MODULE_ID) }
                .onFailure { Log.w(TAG, "Unable to read the subject model's version", it) }
                .getOrDefault(0)
                .also { if (it > 0) cachedModelVersion = it }

        private fun isKnownBad(version: Int): Boolean =
            version > 0 && version / 1000 in KNOWN_BAD_MODEL_VERSIONS

        /**
         * What Play services has: not installed, installed and usable, or
         * installed but broken (a known bad version, or one paused after a
         * crash or a broken answer). Never loads the model. Off the main
         * thread only.
         */
        fun modelAvailability(context: Context): SubjectModelPhase {
            val version = readModelVersion(context)
            return when {
                version <= 0 -> SubjectModelPhase.NOT_DOWNLOADED
                isKnownBad(version) || SegmentationCrashGuard.isPausedForModel(context) ->
                    SubjectModelPhase.BROKEN
                else -> SubjectModelPhase.READY
            }
        }

        private const val MAX_INPUT_SIDE = 1024
        private const val CONFIDENT_FOREGROUND = 0.55f
        private const val HIGH_CONFIDENCE = 0.75f
        private const val MIN_FOREGROUND_FRACTION = 0.012f
        private const val MIN_HIGH_CONFIDENCE_FRACTION = 0.003f
        private const val MAX_FOREGROUND_FRACTION = 0.90f
        private const val MASK_LOW = 0.28f
        private const val MASK_HIGH = 0.72f

        /** A spread smaller than this across the whole mask is no real answer. */
        private const val MIN_MASK_SPREAD = 0.01f
    }

    /** Reads the model's version before each use, off the caller's thread. */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SubjectModelCheck").apply { isDaemon = true }
    }

    // Created on first real use, so a version that is never used is never
    // opened either.
    private val segmenterHolder = lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
        )
    }
    private val segmenter get() = segmenterHolder.value

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

        try {
            worker.execute { checkModelThenProcess(inputBitmap, requestId) }
        } catch (error: RejectedExecutionException) {
            inputBitmap.recycle()
        }
    }

    private fun checkModelThenProcess(inputBitmap: Bitmap, requestId: Long) {
        if (closed) {
            inputBitmap.recycle()
            return
        }
        val version = readModelVersion(appContext)
        if (isKnownBad(version)) {
            // Never touched: this version crashes or answers nonsense.
            SubjectMaskDiagnostics.recordRejection(SegmentationCrashGuard.MODEL_PAUSED)
            inputBitmap.recycle()
            if (!closed) onResult(requestId, null, false)
            return
        }
        // The version doubles as the "is it installed" check: asking Play
        // services through a model client would load the model's native code
        // just to find out.
        if (version <= 0) {
            SubjectMaskDiagnostics.recordRejection(
                "Google's subject model isn't on this phone yet. You can download it in " +
                    "Fine tuning, with the Download subject model button."
            )
            inputBitmap.recycle()
            if (!closed) onResult(requestId, null, false)
            return
        }
        processInput(inputBitmap, requestId, modelVersion(appContext))
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
        if (!segmenterHolder.isInitialized()) return
        runCatching { segmenter.close() }
            .onFailure { error -> Log.w(TAG, "Could not close the subject segmenter", error) }
    }

    private fun processInput(inputBitmap: Bitmap, requestId: Long, model: String?) {
        if (!acquire()) {
            inputBitmap.recycle()
            return
        }
        if (!SegmentationCrashGuard.beginAttempt(appContext, model)) {
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
                                            "The model didn't send back a result"
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
                                        "The model sent back something unexpected"
                                    )
                                    return@maskComputation null
                                }

                                var foregroundCount = 0
                                var highConfidenceCount = 0
                                var minX = inputBitmap.width
                                var minY = inputBitmap.height
                                var maxX = -1
                                var maxY = -1

                                var lowest = 1f
                                var highest = 0f
                                for (index in 0 until count) {
                                    val value = buffer.get().takeIf { it.isFinite() }
                                        ?.coerceIn(0f, 1f) ?: 0f
                                    values[index] = value
                                    if (value < lowest) lowest = value
                                    if (value > highest) highest = value
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

                                // The same value for every pixel is no answer
                                // at all: a broken model, not the photo. Paused
                                // for this version so it isn't called again.
                                if (highest - lowest < MIN_MASK_SPREAD) {
                                    Log.w(TAG, "The subject model answered $lowest..$highest for every pixel")
                                    SegmentationCrashGuard.pauseModel(appContext, model)
                                    return@maskComputation null
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
                                                "The subject fills too much of the photo " +
                                                    "(${(foregroundFraction * 100).roundToInt()}%). " +
                                                    "Try one with more background " +
                                                    "showing"
                                            foregroundFraction < MIN_FOREGROUND_FRACTION ->
                                                "Couldn't find a clear subject in this photo"
                                            highConfidenceFraction < MIN_HIGH_CONFIDENCE_FRACTION ->
                                                "Found something, but not clearly enough to use"
                                            else -> "The subject is too small or thin to use"
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
        worker.shutdown()
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
