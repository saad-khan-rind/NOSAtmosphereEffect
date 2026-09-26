package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable

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
            pendingMask?.bitmap.also { pendingMask = null }
        }
        bitmap.recycleSafely()
    }

    private fun onMaskResult(generation: Long, bitmap: Bitmap?) {
        val fingerprint = synchronized(lock) { fingerprints.remove(generation) }
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

    override fun close() {
        var extractorToClose: SubjectMaskExtractor? = null
        var bitmapToRecycle: Bitmap? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            enabled = false
            latestRequest = -1L
            extractorToClose = extractor
            extractor = null
            bitmapToRecycle = pendingMask?.bitmap
            pendingMask = null
        }
        extractorToClose?.close()
        bitmapToRecycle.recycleSafely()
    }

    private companion object {
        const val MAX_TRACKED_GENERATIONS = 8
    }

    private fun Bitmap?.recycleSafely() {
        if (this != null && !isRecycled) recycle()
    }
}
