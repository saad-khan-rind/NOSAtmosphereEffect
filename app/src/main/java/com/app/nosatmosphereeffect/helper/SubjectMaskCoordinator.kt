package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.Closeable
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One renderer's subject masks: one extraction per image, answers cached by
 * pixels, stale answers dropped.
 *
 * ## A failed extraction is retried
 *
 * Each image is segmented once, and its answer stands until the image
 * changes. That used to include answers that were only failures — Play
 * services unreachable or mid-update after the phone sat idle, an attempt
 * skipped after a crash, a caught error — which were indistinguishable from
 * "this photo has no subject". The Adaptive clock then ran its digits over
 * the person and the depth effect vanished, in the wallpaper and in every
 * preview, until something rebuilt the renderers (switching the graphics
 * backend did). Failures are now asked again for the same image, a few
 * times with growing gaps, only while the screen is on; a real "no subject"
 * still stands.
 */
internal class SubjectMaskCoordinator(
    context: Context,
    private val onMaskReady: () -> Unit
) : Closeable {

    data class PendingMask(
        val generation: Long,
        val bitmap: Bitmap
    )

    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    var enabled = false
        private set

    private var closed = false
    private var latestRequest = -1L
    private var extractor: SubjectMaskExtractor? = null
    private var pendingMask: PendingMask? = null
    /** Cache fingerprint of the image each in-flight generation was cut from. */
    private val fingerprints = HashMap<Long, Long>()

    /**
     * A small copy of the latest image sent for extraction, kept until it
     * has a real answer, so a failure can be retried after the caller has
     * recycled its bitmap.
     */
    private var retryImage: Bitmap? = null
    private var retryGeneration = -1L
    private var retryAttempts = 0
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { retryExtraction() }

    /**
     * Shown every image this segments and every answer it gets, so the
     * Adaptive clock can fit itself around the same subject the renderer
     * masks with. Optional: only renderers that draw the clock set it.
     */
    @Volatile var sceneSink: ClockSceneSink? = null

    fun configure(enabled: Boolean): Boolean {
        var extractorToClose: SubjectMaskExtractor? = null
        var bitmapToRecycle: Bitmap? = null
        val changed = synchronized(lock) {
            if (closed || this.enabled == enabled) {
                false
            } else {
                this.enabled = enabled
                if (!enabled) {
                    latestRequest = -1L
                    extractorToClose = extractor
                    extractor = null
                    bitmapToRecycle = pendingMask?.bitmap
                    pendingMask = null
                    clearRetryLocked()
                }
                true
            }
        }
        extractorToClose?.close()
        bitmapToRecycle.recycleSafely()
        return changed
    }

    /**
     * Returns true only when an extraction was actually dispatched. Callers
     * use this to decide whether the generation has been served — marking it
     * served on a dropped request means it is never retried.
     */
    fun request(bitmap: Bitmap, generation: Long): Boolean {
        // Fingerprinted before taking the lock: it reads ~2k pixels, and the
        // caller recycles [bitmap] as soon as this returns.
        val fingerprint = if (bitmap.isRecycled) null else SubjectMaskCache.fingerprint(bitmap)
        val cached = fingerprint?.let(SubjectMaskCache::get)
        val activeExtractor = synchronized(lock) {
            if (closed || !enabled || bitmap.isRecycled) {
                cached?.recycle()
                return false
            }
            // One extraction per image. Callers can ask repeatedly — the GLES
            // renderer used to, once per frame, while waiting for a mask — and
            // segmentation is far too expensive to run speculatively. A new
            // image gets a new generation; configure(false) resets this, so
            // toggling the feature off and on still re-runs it.
            if (latestRequest == generation) {
                cached?.recycle()
                return false
            }
            latestRequest = generation
            if (cached != null) null else extractor ?: SubjectMaskExtractor(
                appContext,
                ::onMaskResult
            ).also { extractor = it }
        }
        // Before the extraction is dispatched, while the caller still holds
        // the bitmap: the answer can arrive (from the cache) before this
        // returns, and the scene must already know which image it is for.
        sceneSink?.let { sink ->
            runCatching { sink.onSceneImage(generation, bitmap) }
        }
        if (activeExtractor == null) {
            // Same pixels were segmented recently: serve that result instead
            // of running inference again.
            onMaskResult(generation, cached)
            return true
        }
        val retryCopy = runCatching { retryCopyOf(bitmap) }
            .onFailure { Log.w(TAG, "Unable to keep the image for a retry", it) }
            .getOrNull()
        synchronized(lock) {
            clearRetryLocked()
            if (latestRequest == generation && retryCopy != null) {
                retryImage = retryCopy
                retryGeneration = generation
            } else {
                retryCopy?.recycle()
            }
        }
        if (fingerprint != null) {
            synchronized(lock) {
                fingerprints[generation] = fingerprint
                // Only the newest few generations can still deliver.
                while (fingerprints.size > MAX_TRACKED_GENERATIONS) {
                    fingerprints.remove(fingerprints.keys.min())
                }
            }
        }
        activeExtractor.extract(bitmap, generation)
        return true
    }

    fun takePending(): PendingMask? = synchronized(lock) {
        pendingMask.also { pendingMask = null }
    }

    fun discardPending() {
        val bitmap = synchronized(lock) {
            latestRequest = -1L
            clearRetryLocked()
            pendingMask?.bitmap.also { pendingMask = null }
        }
        bitmap.recycleSafely()
    }

    private fun onMaskResult(generation: Long, bitmap: Bitmap?, failed: Boolean = false) {
        // A failure for the image still on screen is asked again later; any
        // other answer is final, so the kept copy is no longer needed.
        val retrying = synchronized(lock) {
            if (generation != retryGeneration) {
                false
            } else if (bitmap == null && failed && !closed && enabled &&
                generation == latestRequest && retryAttempts < RETRY_DELAYS_MS.size
            ) {
                true
            } else {
                clearRetryLocked()
                false
            }
        }
        if (retrying) scheduleRetry()
        // Kept through a retry, so its answer is still cached for the others.
        val fingerprint = synchronized(lock) {
            if (retrying) fingerprints[generation] else fingerprints.remove(generation)
        }
        // Read before the mask is handed on: the consumer recycles it once it
        // has been uploaded. A null answer is news too — "no subject here"
        // lets the Adaptive clock run its digits full length.
        val current = synchronized(lock) { !closed && enabled && generation == latestRequest }
        if (current) {
            sceneSink?.let { sink ->
                runCatching { sink.onSceneMask(generation, bitmap?.takeIf { !it.isRecycled }) }
            }
        }
        if (bitmap == null) return
        // Cached even when this particular consumer has moved on: the next
        // engine or surface to show the same photo wants exactly this mask.
        if (fingerprint != null) SubjectMaskCache.put(fingerprint, bitmap)

        var replaced: Bitmap? = null
        val accepted = synchronized(lock) {
            if (closed || !enabled || generation != latestRequest) {
                false
            } else if (pendingMask?.generation?.let { it > generation } == true) {
                false
            } else {
                replaced = pendingMask?.bitmap
                pendingMask = PendingMask(generation, bitmap)
                true
            }
        }

        if (!accepted) {
            bitmap.recycleSafely()
            return
        }
        replaced.recycleSafely()
        onMaskReady()
    }

    private fun scheduleRetry() {
        val delay = synchronized(lock) { RETRY_DELAYS_MS[retryAttempts.coerceIn(0, RETRY_DELAYS_MS.lastIndex)] }
        retryHandler.removeCallbacks(retryRunnable)
        retryHandler.postDelayed(retryRunnable, delay)
    }

    /** Main thread. Runs the kept image through extraction again. */
    private fun retryExtraction() {
        // Nobody can see the result with the screen off, and an extraction
        // then is wasted battery: wait, without using up an attempt.
        val interactive = runCatching {
            appContext.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        }.getOrDefault(true)
        if (!interactive) {
            retryHandler.postDelayed(retryRunnable, SCREEN_OFF_RECHECK_MS)
            return
        }
        val (activeExtractor, image, generation) = synchronized(lock) {
            val image = retryImage
            if (closed || !enabled || image == null || image.isRecycled ||
                retryGeneration != latestRequest
            ) {
                clearRetryLocked()
                return
            }
            retryAttempts++
            val activeExtractor = extractor ?: SubjectMaskExtractor(appContext, ::onMaskResult)
                .also { extractor = it }
            Triple(activeExtractor, image, retryGeneration)
        }
        Log.i(TAG, "Retrying subject detection for image $generation")
        // The extractor copies what it needs before returning, and the kept
        // image stays owned here until an answer is final.
        runCatching { activeExtractor.extract(image, generation) }
            .onFailure { failure ->
                Log.w(TAG, "Unable to retry subject detection", failure)
                onMaskResult(generation, null, failed = true)
            }
    }

    /** Under [lock]. */
    private fun clearRetryLocked() {
        retryHandler.removeCallbacks(retryRunnable)
        retryImage.recycleSafely()
        retryImage = null
        retryGeneration = -1L
        retryAttempts = 0
    }

    /** At most the size extraction itself works at, so the copy stays small. */
    private fun retryCopyOf(source: Bitmap): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val longest = max(source.width, source.height)
        if (longest <= RETRY_IMAGE_MAX_SIDE) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = RETRY_IMAGE_MAX_SIDE.toFloat() / longest
        return BitmapDownscale.toStagedSize(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    override fun close() {
        var extractorToClose: SubjectMaskExtractor? = null
        var bitmapToRecycle: Bitmap? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            enabled = false
            latestRequest = -1L
            clearRetryLocked()
            extractorToClose = extractor
            extractor = null
            bitmapToRecycle = pendingMask?.bitmap
            pendingMask = null
        }
        extractorToClose?.close()
        bitmapToRecycle.recycleSafely()
    }

    private companion object {
        const val TAG = "SubjectMaskCoordinator"
        const val MAX_TRACKED_GENERATIONS = 8
        /** The extractors' own working size (Play's MAX_INPUT_SIDE). */
        const val RETRY_IMAGE_MAX_SIDE = 1024
        /** One retry after each; about 8 minutes in all with the screen on. */
        val RETRY_DELAYS_MS = longArrayOf(3_000L, 15_000L, 60_000L, 180_000L, 300_000L)
        const val SCREEN_OFF_RECHECK_MS = 60_000L
    }

    private fun Bitmap?.recycleSafely() {
        if (this != null && !isRecycled) recycle()
    }
}
