package com.app.nosatmosphereeffect.renderer.vulkan

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import com.app.nosatmosphereeffect.helper.WallpaperFitHelper
import com.app.nosatmosphereeffect.helper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.ColorFillRenderState
import com.app.nosatmosphereeffect.renderer.vulkan.common.SwapchainRecreationBudget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class VulkanColorFillHost(
    context: Context,
    private val reverse: Boolean,
    initialState: ColorFillRenderState,
    private val onFatalFailure: (VulkanColorFillHost, String) -> Unit,
    private val onVulkanActive: (VulkanColorFillHost, Int) -> Unit = { _, _ -> },
    private val previewSource: (() -> Bitmap?)? = null
) : WallpaperRenderHost {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("AtmoVulkanColorFill").apply { start() }
    private val worker = Handler(renderThread.looper)
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val renderQueued = AtomicBoolean(false)
    private val activeReported = AtomicBoolean(false)

    private val latestState = AtomicReference(initialState.sanitized())

    /**
     * The wallpaper clock. Colour Fill has its own standalone host rather than
     * sharing VulkanSingleImageHost, so the hooks the other effects override
     * are open-coded here — [uploadClockOnWorker] is called from the draw path
     * and [clockOverlay] is reset with the surface.
     */
    private val clockOverlay = VulkanClockOverlay(appContext, "Colour Fill")

    /**
     * Segmentation for the clock's depth effect. Colour Fill has no
     * "background only" mode, so this is the mask's only consumer and it runs
     * only while depth is switched on.
     */
    private val clockDepthMask = VulkanClockDepthMask(
        appContext,
        "Colour Fill",
        ::requestRender
    )

    /**
     * Counts images, not surfaces.
     *
     * This host's `generation` tracks the surface, which survives a wallpaper
     * change and is recreated on rotation — neither of which is the question
     * the mask needs answered. Feeding it to the mask would let a stale mask
     * pass the freshness check after a playlist advance.
     */
    private var clockMaskGeneration = 0L
    private val nativeHandle = AtomicLong(0L)
    private val pendingPlaylistBitmap = AtomicReference<Bitmap?>(null)
    private val swapchainRetryBudget =
        SwapchainRecreationBudget(maxAttempts = MAX_SWAPCHAIN_RETRIES)

    @Volatile
    private var surfaceGeneration = 0L

    @Volatile
    private var latestSurface: Surface? = null

    @Volatile
    private var latestWidth = 0

    @Volatile
    private var latestHeight = 0

    private var ready = false
    private var readyGeneration = NO_SURFACE_GENERATION
    private var swapchainRecoveryQueued = false

    @Volatile
    var initializedApiVersion: VulkanApiVersion? = null
        private set

    private var paused = false
    private var needsReload = true

    init {
        postToWorker(operation = "starting the native renderer") {
            if (closed.get() || failed.get()) {
                renderThread.quitSafely()
                return@postToWorker
            }
            val libraryLoaded = try {
                VulkanNative.libraryLoaded
            } catch (failure: Throwable) {
                Log.e(TAG, "Unable to query the Vulkan native library", failure)
                false
            }
            if (!libraryLoaded) {
                failOnWorker("The Vulkan native library could not be loaded")
                return@postToWorker
            }
            val createdHandle = try {
                VulkanNative.nativeCreate(appContext.assets, reverse)
            } catch (failure: Throwable) {
                Log.e(TAG, "Unable to create the native Color Fill engine", failure)
                0L
            }
            if (createdHandle == 0L) {
                failOnWorker("The Vulkan Color Fill engine could not be created")
                return@postToWorker
            }
            if (!adoptNativeHandle(createdHandle)) {
                renderThread.quitSafely()
            }
        }
    }

    fun updateState(state: ColorFillRenderState) {
        val sanitized = state.sanitized()
        clockOverlay.applyState(sanitized.clock)
        // Reloading on the transition into "wanted" is what dispatches the
        // extraction: one request is served per image, so an image uploaded
        // while depth was off would otherwise never get a mask.
        if (clockDepthMask.configure(sanitized.clock.needsSubjectMask()) &&
            sanitized.clock.needsSubjectMask()
        ) {
            reloadTexture()
        }
        latestState.updateAndGet { current ->
            current.copy(
                progress = sanitized.progress,
                dimLevel = sanitized.dimLevel,
                originX = sanitized.originX,
                originY = sanitized.originY,
                // The dynamic fields are read from the overlay rather than
                // carried forward from a snapshot: this runs on whichever
                // thread changed a preference while uploadClockOnWorker runs
                // on the render worker, and a stale read would silently lose
                // whichever landed second.
                clock = sanitized.clock.copy(
                    textureAspect = if (clockOverlay.hasUploadedFace) {
                        clockOverlay.aspectRatio
                    } else {
                        current.clock.textureAspect
                    },
                    faceUploaded = clockOverlay.hasUploadedFace &&
                        sanitized.clock.enabled
                )
            ).sanitized()
        }
        postIfActive {
            applyStateOnWorker()
        }
    }

    /** Plays the clock's entry animation on the next drawn frame. */
    fun beginClockEntry() {
        clockOverlay.beginEntry()
        requestRender()
    }

    /** Re-reads the system 12/24-hour setting and forces a redraw. */
    fun onTimeChanged() {
        clockOverlay.onTimeChanged()
        requestRender()
    }

    /**
     * Uploads a clock face when there is one to upload. Failures are logged
     * and skipped rather than failing the renderer: a decorative overlay must
     * not be able to take the whole Vulkan backend down.
     */
    private fun uploadClockDepthMaskOnWorker(handle: Long) {
        val hasSubject = clockDepthMask.uploadIfNeeded(
            handle = handle,
            textureGeneration = clockMaskGeneration,
            upload = VulkanNative::nativeUploadSubjectMask,
            clear = VulkanNative::nativeClearSubjectMask
        )
        if (hasSubject == latestState.get().hasSubject) return
        latestState.updateAndGet { it.copy(hasSubject = hasSubject).sanitized() }
    }

    private fun uploadClockOnWorker(handle: Long) {
        val current = latestState.get()
        val changed = clockOverlay.uploadIfNeeded(
            effectiveOpacity = current.clock.effectiveOpacity(current.progress),
            upload = { bitmap -> VulkanNative.nativeUploadClock(handle, bitmap) },
            requestRender = ::requestRender
        )
        if (!changed) return
        latestState.updateAndGet { state ->
            state.copy(
                clock = state.clock.copy(
                    textureAspect = clockOverlay.aspectRatio,
                    faceUploaded = true
                )
            ).sanitized()
        }
    }

    fun reloadTexture() {
        postIfActive {
            needsReload = true
            val generation = surfaceGeneration
            if (ready && loadActiveTextureOnWorker(generation)) {
                drawOnWorker(generation)
            }
        }
    }

    fun queuePlaylistTransition(bitmap: Bitmap) {
        if (closed.get() || failed.get()) {
            bitmap.recycleSafely()
            return
        }
        postToWorker(
            operation = "queueing a playlist wallpaper",
            onRejected = { bitmap.recycleSafely() }
        ) {
            if (closed.get() || failed.get()) {
                bitmap.recycleSafely()
                return@postToWorker
            }
            val generation = surfaceGeneration
            if (!isReadyForGeneration(generation)) {
                replacePendingPlaylistBitmap(bitmap)
                return@postToWorker
            }
            if (uploadPlaylistBitmapOnWorker(bitmap, generation)) {
                drawOnWorker(generation)
            }
        }
    }

    override fun onSurfaceCreated(holder: SurfaceHolder) {
        latestSurface = holder.surface
    }

    override fun onSurfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        if (width <= 0 || height <= 0) return
        val generation = ++surfaceGeneration
        val surface = holder.surface
        latestSurface = surface
        latestWidth = width
        latestHeight = height
        postIfActive {
            if (generation != surfaceGeneration) return@postIfActive
            swapchainRetryBudget.reset()
            swapchainRecoveryQueued = false
            initializeSurfaceOnWorker(surface, width, height, generation)
        }
    }

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {
        clearSurfaceReference()
        postIfActive {
            destroySurfaceOnWorker(failRenderer = true)
        }
    }

    override fun quiesceSurface(holder: SurfaceHolder) {
        clearSurfaceReference()
        runSynchronouslyIfActive {
            check(destroySurfaceOnWorker(failRenderer = false)) {
                "The Vulkan Color Fill surface could not be quiesced"
            }
        }
    }

    override fun onResume() {
        postIfActive {
            paused = false
            drawOnWorker()
        }
    }

    override fun onPause() {
        postIfActive {
            paused = true
        }
    }

    override fun requestRender() {
        if (closed.get() || failed.get()) return
        if (!renderQueued.compareAndSet(false, true)) return
        postToWorker(
            operation = "queueing a Color Fill frame",
            onRejected = { renderQueued.set(false) }
        ) {
            renderQueued.set(false)
            if (!closed.get() && !failed.get()) {
                drawOnWorker()
            }
        }
    }

    override fun setWallpaperOffset(xOffset: Float) {
        latestState.updateAndGet { current ->
            current.copy(scrollOffsetX = xOffset).sanitized()
        }
        postIfActive {
            applyStateOnWorker()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (failed.get()) {
            releaseNativeResources()
            renderThread.quitSafely()
            return
        }
        postToWorker(operation = "releasing the Color Fill renderer") {
            releaseNativeOnWorker()
            renderThread.quitSafely()
        }
    }

    private fun initializeSurfaceOnWorker(
        surface: Surface,
        width: Int,
        height: Int,
        generation: Long
    ) {
        val handle = nativeHandle.get()
        if (handle == 0L) {
            failOnWorker("The Vulkan Color Fill engine is unavailable")
            return
        }
        if (!isCurrentSurface(surface, generation)) {
            discardStaleSurfaceOnWorker()
            return
        }

        ready = false
        readyGeneration = NO_SURFACE_GENERATION
        initializedApiVersion = null
        val initialized = try {
            VulkanNative.nativeSetSurface(handle, surface, width, height)
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to initialize the Vulkan wallpaper surface", failure)
            false
        }
        if (!isCurrentSurface(surface, generation)) {
            discardStaleSurfaceOnWorker()
            return
        }
        if (!initialized) {
            failOnWorker("The Vulkan swapchain could not be initialized")
            return
        }
        val apiVersion = try {
            VulkanApiVersion.fromEncoded(
                VulkanNative.nativeGetApiVersion(handle)
            )
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to read the initialized Vulkan API version", failure)
            null
        }
        if (apiVersion == null) {
            failOnWorker("The initialized Vulkan API version is unavailable")
            return
        }
        initializedApiVersion = apiVersion
        ready = true
        readyGeneration = generation

        val pending = pendingPlaylistBitmap.getAndSet(null)
        val textureReady = if (pending != null) {
            uploadPlaylistBitmapOnWorker(pending, generation)
        } else {
            loadActiveTextureOnWorker(generation)
        }
        if (!textureReady) return
        if (!isCurrentSurface(surface, generation)) {
            discardStaleSurfaceOnWorker()
            return
        }
        drawOnWorker(generation)
    }

    private fun loadActiveTextureOnWorker(generation: Long): Boolean {
        val width = latestWidth
        val height = latestHeight
        if (!isReadyForGeneration(generation) || width <= 0 || height <= 0) {
            needsReload = true
            return false
        }
        val renderImage = runCatching {
            WallpaperFitHelper.loadForRender(
                appContext,
                width,
                height,
                previewSource
            )
        }.getOrElse { failure ->
            if (isCurrentGeneration(generation)) {
                failOnWorker("The active wallpaper could not be prepared: ${failure.message}")
            }
            return false
        }
        return uploadRenderImageOnWorker(renderImage, generation)
    }

    private fun uploadPlaylistBitmapOnWorker(bitmap: Bitmap, generation: Long): Boolean {
        val width = latestWidth
        val height = latestHeight
        if (!isReadyForGeneration(generation) || width <= 0 || height <= 0) {
            replacePendingPlaylistBitmap(bitmap)
            return false
        }
        val renderImage = runCatching {
            WallpaperFitHelper.fitForRender(appContext, bitmap, width, height)
        }.getOrElse { failure ->
            bitmap.recycleSafely()
            if (isCurrentGeneration(generation)) {
                failOnWorker("The playlist wallpaper could not be prepared: ${failure.message}")
            }
            return false
        }
        return uploadRenderImageOnWorker(renderImage, generation)
    }

    private fun uploadRenderImageOnWorker(
        renderImage: WallpaperFitHelper.RenderImage,
        generation: Long
    ): Boolean {
        val bitmap = renderImage.bitmap
        if (!isReadyForGeneration(generation)) {
            bitmap.recycleSafely()
            needsReload = true
            return false
        }
        val handle = nativeHandle.get()
        val uploadSucceeded = try {
            if (handle == 0L) {
                false
            } else {
                VulkanNative.nativeUploadBitmap(handle, bitmap).also { ok ->
                    // Dispatched before the recycle in the finally below: the
                    // extractor needs the pixels.
                    if (ok) {
                        clockMaskGeneration++
                        clockDepthMask.onImageUploaded(bitmap, clockMaskGeneration)
                    }
                }
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to upload the Color Fill texture", failure)
            false
        } finally {
            bitmap.recycleSafely()
        }

        if (!isCurrentGeneration(generation)) {
            discardStaleSurfaceOnWorker()
            return false
        }
        if (!uploadSucceeded) {
            failOnWorker("The wallpaper texture could not be uploaded to Vulkan")
            return false
        }

        latestState.updateAndGet { current ->
            current.copy(scrollWindowX = renderImage.windowX).sanitized()
        }
        needsReload = false
        return applyStateOnWorker()
    }

    private fun applyStateOnWorker(): Boolean {
        val handle = nativeHandle.get()
        if (handle == 0L || failed.get()) return false
        val state = latestState.get()
        return try {
            VulkanNative.nativeSetState(
                handle = handle,
                progress = state.progress,
                dimLevel = state.dimLevel,
                originX = state.originX,
                originY = state.originY,
                scrollOffsetX = state.scrollOffsetX,
                scrollWindowX = state.scrollWindowX,
                clockCenterX = state.clock.centerX,
                clockTop = state.clock.top,
                // Per-axis stretch is folded into these two numbers rather
                // than passed separately — see ClockOverlayState.renderHeight.
                clockHeightFraction = state.clock.renderHeight,
                clockTextureAspect =
                    state.clock.renderTextureAspect(state.clock.textureAspect),
                // The lock/home fade is folded in here, once, so the shader
                // has no policy in it and both backends share one curve.
                clockOpacity = state.clock.effectiveOpacity(state.progress),
                clockUploaded = state.clock.faceUploaded,
                // Depth needs a mask, so the user's switch is ANDed with one
                // existing — the shader must never sample the clear texture.
                clockDepth = state.clock.depthEnabled && state.hasSubject
            )
            true
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to update the Vulkan Color Fill state", failure)
            failOnWorker("The Vulkan Color Fill state could not be updated")
            false
        }
    }

    private fun drawOnWorker(generation: Long = surfaceGeneration) {
        val handle = nativeHandle.get()
        if (paused || failed.get() || handle == 0L) return
        if (!isReadyForGeneration(generation)) {
            discardStaleSurfaceOnWorker()
            return
        }
        if (needsReload && !loadActiveTextureOnWorker(generation)) return
        // Before the state is pushed, so this frame's uniforms already carry
        // whatever the uploads just changed.
        uploadClockOnWorker(handle)
        uploadClockDepthMaskOnWorker(handle)
        if (!applyStateOnWorker()) return
        val result = try {
            VulkanNative.nativeRender(handle)
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to render the Vulkan Color Fill frame", failure)
            RENDER_FATAL
        }
        if (!isCurrentGeneration(generation)) {
            discardStaleSurfaceOnWorker()
            return
        }
        when (result) {
            RENDER_SUCCESS -> {
                swapchainRetryBudget.reset()
                reportVulkanActive(apiVersion = initializedApiVersion)
            }
            RENDER_RECREATE -> scheduleSwapchainRecoveryOnWorker(generation)
            else -> failOnWorker("The Vulkan driver failed while presenting Color Fill")
        }
    }

    private fun scheduleSwapchainRecoveryOnWorker(generation: Long) {
        if (swapchainRecoveryQueued || failed.get() || closed.get()) return
        if (!swapchainRetryBudget.tryAcquire()) {
            failOnWorker(
                "The Vulkan Color Fill swapchain remained out of date after " +
                    "$MAX_SWAPCHAIN_RETRIES recovery attempts"
            )
            return
        }

        swapchainRecoveryQueued = true
        postToWorker(
            operation = "recovering the Color Fill swapchain",
            onRejected = { swapchainRecoveryQueued = false }
        ) {
            swapchainRecoveryQueued = false
            if (closed.get() || failed.get()) return@postToWorker

            val surface = latestSurface
            val width = latestWidth
            val height = latestHeight
            if (surface == null ||
                width <= 0 ||
                height <= 0 ||
                !isCurrentSurface(surface, generation)
            ) {
                discardStaleSurfaceOnWorker()
                return@postToWorker
            }
            initializeSurfaceOnWorker(surface, width, height, generation)
        }
    }

    private fun reportVulkanActive(apiVersion: VulkanApiVersion?) {
        if (apiVersion == null || !activeReported.compareAndSet(false, true)) return
        mainHandler.post {
            onVulkanActive(this, apiVersion.encoded)
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean {
        val surface = latestSurface ?: return false
        val surfaceValid = try {
            surface.isValid
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to query the Color Fill surface state", failure)
            false
        }
        return generation == surfaceGeneration &&
            surfaceValid &&
            latestWidth > 0 &&
            latestHeight > 0
    }

    private fun isCurrentSurface(surface: Surface, generation: Long): Boolean {
        return latestSurface === surface && isCurrentGeneration(generation)
    }

    private fun isReadyForGeneration(generation: Long): Boolean {
        return ready && readyGeneration == generation && isCurrentGeneration(generation)
    }

    private fun resetSurfaceStateOnWorker() {
        ready = false
        readyGeneration = NO_SURFACE_GENERATION
        initializedApiVersion = null
        needsReload = true
        swapchainRecoveryQueued = false
        swapchainRetryBudget.reset()
        // The new surface's descriptor set has no clock content yet, so the
        // next frame must re-upload even though the time has not changed.
        clockOverlay.reset()
        clockDepthMask.onSurfaceReset()
        latestState.updateAndGet { state ->
            state.copy(
                clock = state.clock.copy(faceUploaded = false),
                hasSubject = false
            ).sanitized()
        }
    }

    private fun discardStaleSurfaceOnWorker() {
        resetSurfaceStateOnWorker()
        val handle = nativeHandle.get()
        if (handle != 0L &&
            !destroyNativeSurfaceSafely(
                handle,
                "discarding a stale Color Fill surface"
            )
        ) {
            failOnWorker("The stale Vulkan Color Fill surface could not be discarded")
        }
    }

    private fun clearSurfaceReference() {
        ++surfaceGeneration
        latestSurface = null
        latestWidth = 0
        latestHeight = 0
    }

    private fun destroySurfaceOnWorker(failRenderer: Boolean): Boolean {
        resetSurfaceStateOnWorker()
        val handle = nativeHandle.get()
        if (handle == 0L) return true
        val destroyed = destroyNativeSurfaceSafely(
            handle,
            "destroying the Color Fill wallpaper surface"
        )
        if (!destroyed && failRenderer) {
            failOnWorker("The Vulkan Color Fill surface could not be destroyed")
        }
        return destroyed
    }

    private fun runSynchronouslyIfActive(action: () -> Unit) {
        if (closed.get() || failed.get()) return
        if (Looper.myLooper() == renderThread.looper) {
            action()
            return
        }

        val completion = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val accepted = try {
            worker.post {
                try {
                    if (!closed.get() && !failed.get()) action()
                } catch (workerFailure: Throwable) {
                    failure.set(workerFailure)
                } finally {
                    completion.countDown()
                }
            }
        } catch (workerFailure: Throwable) {
            throw IllegalStateException(
                "The Vulkan Color Fill worker could not quiesce its surface",
                workerFailure
            )
        }
        check(accepted) {
            "The Vulkan Color Fill worker could not quiesce its surface"
        }

        var interrupted = false
        while (true) {
            try {
                completion.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        failure.get()?.let { workerFailure ->
            throw IllegalStateException(
                "The Vulkan Color Fill surface could not be quiesced",
                workerFailure
            )
        }
    }

    private fun postIfActive(action: () -> Unit) {
        if (closed.get() || failed.get()) return
        postToWorker(operation = "queueing Color Fill renderer work") {
            if (!closed.get() && !failed.get()) action()
        }
    }

    private fun postToWorker(
        operation: String,
        onRejected: () -> Unit = {},
        action: () -> Unit
    ): Boolean {
        val accepted = try {
            worker.post {
                try {
                    action()
                } catch (failure: Throwable) {
                    Log.e(TAG, "The Color Fill worker failed while $operation", failure)
                    if (closed.get()) {
                        releaseNativeResources()
                        renderThread.quitSafely()
                    } else {
                        failOnWorker(
                            "The Vulkan Color Fill worker failed while $operation"
                        )
                    }
                }
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "The Color Fill worker failed while $operation", failure)
            false
        }
        if (accepted) return true

        try {
            onRejected()
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to clean up rejected Color Fill work", failure)
        }
        handleRejectedWorkerPost(operation)
        return false
    }

    private fun handleRejectedWorkerPost(operation: String) {
        val reason = "The Vulkan Color Fill worker rejected work while $operation"
        if (closed.get()) {
            Log.w(TAG, reason)
            releaseNativeResources()
            renderThread.quitSafely()
        } else {
            failOnWorker(reason)
        }
    }

    private fun failOnWorker(reason: String) {
        if (!failed.compareAndSet(false, true)) return
        Log.e(TAG, reason)
        releaseNativeResources()
        renderThread.quitSafely()
        try {
            mainHandler.post {
                onFatalFailure(this, reason)
            }
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to report the Vulkan Color Fill failure", failure)
        }
    }

    private fun releaseNativeOnWorker() {
        releaseNativeResources()
    }

    private fun releaseNativeResources() {
        clockOverlay.release()
        clockDepthMask.close()
        ready = false
        readyGeneration = NO_SURFACE_GENERATION
        initializedApiVersion = null
        needsReload = true
        swapchainRecoveryQueued = false
        pendingPlaylistBitmap.getAndSet(null)?.recycleSafely()
        val handle = nativeHandle.getAndSet(0L)
        if (handle != 0L) {
            destroyNativeHandleSafely(handle)
        }
    }

    private fun adoptNativeHandle(handle: Long): Boolean {
        if (closed.get() ||
            failed.get() ||
            !nativeHandle.compareAndSet(0L, handle)
        ) {
            destroyNativeHandleSafely(handle)
            return false
        }
        if (!closed.get() && !failed.get()) return true

        if (nativeHandle.compareAndSet(handle, 0L)) {
            destroyNativeHandleSafely(handle)
        }
        return false
    }

    private fun destroyNativeSurfaceSafely(
        handle: Long,
        operation: String
    ): Boolean {
        return try {
            VulkanNative.nativeDestroySurface(handle)
            true
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to finish $operation", failure)
            false
        }
    }

    private fun destroyNativeHandleSafely(handle: Long) {
        try {
            VulkanNative.nativeDestroy(handle)
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to destroy the Vulkan Color Fill engine", failure)
        }
    }

    private fun replacePendingPlaylistBitmap(bitmap: Bitmap) {
        pendingPlaylistBitmap.getAndSet(bitmap)?.recycleSafely()
    }

    private fun Bitmap.recycleSafely() {
        try {
            if (!isRecycled) recycle()
        } catch (failure: Throwable) {
            Log.w(TAG, "Unable to recycle a Color Fill bitmap", failure)
        }
    }

    private companion object {
        const val TAG = "VulkanColorFill"
        const val RENDER_SUCCESS = 0
        const val RENDER_RECREATE = 1
        const val RENDER_FATAL = -1
        const val MAX_SWAPCHAIN_RETRIES = 2
        const val NO_SURFACE_GENERATION = -1L
    }
}
