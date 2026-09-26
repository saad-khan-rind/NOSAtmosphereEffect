package com.app.nosatmosphereeffect.renderer.vulkan.common

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
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanApiVersion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal abstract class VulkanSingleImageHost<State : Any>(
    context: Context,
    threadName: String,
    initialState: State,
    private val bridge: VulkanSingleImageBridge<State>,
    private val onFatalFailure: (WallpaperRenderHost, String) -> Unit,
    private val onVulkanActive: (WallpaperRenderHost, Int) -> Unit,
    private val previewSource: (() -> Bitmap?)? = null
) : WallpaperRenderHost {
    protected val appContext: Context = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread(threadName).apply { start() }
    private val worker = Handler(renderThread.looper)
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val renderQueued = AtomicBoolean(false)
    private val activeReported = AtomicBoolean(false)
    private val nativeStartRequested = AtomicBoolean(false)
    private val effectResourcesReleased = AtomicBoolean(false)
    private val latestState = AtomicReference(initialState)
    private val recreationBudget = SwapchainRecreationBudget()

    @Volatile
    private var surfaceGeneration = 0L

    @Volatile
    private var latestSurface: Surface? = null

    @Volatile
    private var latestWidth = 0

    @Volatile
    private var latestHeight = 0

    @Volatile
    private var scrollOffsetX = DEFAULT_SCROLL_OFFSET

    @Volatile
    private var nativeHandle = 0L
    private var ready = false
    private var readyGeneration = NO_GENERATION
    private var initializedApiVersion: VulkanApiVersion? = null
    private var paused = false
    private var needsReload = true
    private var scrollWindowX = 1f
    private var textureGeneration = 0L
    private var recreationQueued = false

    @Volatile
    private var pendingPlaylistBitmap: Bitmap? = null

    protected fun startNativeEngine() {
        check(nativeStartRequested.compareAndSet(false, true)) {
            "The Vulkan ${bridge.effectLabel} engine has already been started"
        }
        worker.post {
            if (closed.get()) {
                renderThread.quitSafely()
                return@post
            }
            nativeHandle = runCatching {
                bridge.create(appContext.assets)
            }.getOrElse { failure ->
                Log.e(TAG, "Unable to create the native ${bridge.effectLabel} engine", failure)
                0L
            }
            if (nativeHandle == 0L) {
                failOnWorker("The Vulkan ${bridge.effectLabel} engine could not be created")
            }
        }
    }

    protected fun updateEffectState(transform: (State) -> State) {
        latestState.updateAndGet(transform)
        postIfActive {
            applyStateOnWorker()
        }
    }

    protected fun currentEffectState(): State = latestState.get()

    /** The launcher's page offset, for anything drawn against the screen. */
    protected val wallpaperScrollOffsetX: Float
        get() = scrollOffsetX

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
        val accepted = worker.post {
            if (closed.get() || failed.get()) {
                bitmap.recycleSafely()
                return@post
            }
            val generation = surfaceGeneration
            if (!isReadyForGeneration(generation)) {
                pendingPlaylistBitmap?.recycleSafely()
                pendingPlaylistBitmap = bitmap
                return@post
            }
            if (uploadPlaylistBitmapOnWorker(bitmap, generation)) {
                drawOnWorker(generation)
            }
        }
        if (!accepted) bitmap.recycleSafely()
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
            if (generation == surfaceGeneration) {
                initializeSurfaceOnWorker(surface, width, height, generation)
            }
        }
    }

    override fun onSurfaceDestroyed(holder: SurfaceHolder) {
        clearSurfaceReference()
        // SurfaceHolder.Callback requires the render thread to stop touching
        // the surface before this callback returns; the framework tears the
        // BufferQueue down right after. Posting asynchronously let an
        // in-flight setSurface() build a swapchain on an abandoned window.
        val completed = runSynchronouslyIfActive(SURFACE_DESTROY_TIMEOUT_MS) {
            destroySurfaceOnWorker(propagateFailure = false)
        }
        if (!completed) {
            Log.w(TAG, "Timed out waiting for the ${bridge.effectLabel} surface release")
        }
    }

    override fun quiesceSurface(holder: SurfaceHolder) {
        clearSurfaceReference()
        runSynchronouslyIfActive {
            destroySurfaceOnWorker(propagateFailure = true)
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
        val accepted = worker.post {
            renderQueued.set(false)
            if (!closed.get() && !failed.get()) {
                drawOnWorker()
            }
        }
        if (!accepted) renderQueued.set(false)
    }

    override fun setWallpaperOffset(xOffset: Float) {
        scrollOffsetX = xOffset.finiteOr(DEFAULT_SCROLL_OFFSET).coerceIn(0f, 1f)
        postIfActive {
            applyStateOnWorker()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (failed.get()) return
        val accepted = worker.post {
            releaseNativeOnWorker()
            renderThread.quitSafely()
        }
        if (!accepted) {
            releaseNativeOnWorker()
            renderThread.quitSafely()
        }
    }

    protected open fun onWallpaperUploadedOnWorker(
        handle: Long,
        bitmap: Bitmap,
        textureGeneration: Long
    ): Boolean = true

    protected open fun prepareFrameOnWorker(
        handle: Long,
        textureGeneration: Long
    ): Boolean = true

    protected open fun onSurfaceResetOnWorker() = Unit

    protected open fun onEffectResourcesReleased() = Unit

    private fun initializeSurfaceOnWorker(
        surface: Surface,
        width: Int,
        height: Int,
        generation: Long,
        isSwapchainRetry: Boolean = false
    ) {
        val handle = nativeHandle
        if (handle == 0L) {
            failOnWorker("The Vulkan ${bridge.effectLabel} engine is unavailable")
            return
        }
        if (!isCurrentSurface(surface, generation)) {
            discardStaleSurfaceOnWorker()
            return
        }

        recreationQueued = false
        if (!isSwapchainRetry) {
            recreationBudget.reset()
        }
        resetSurfaceStateOnWorker()
        val initialized = runCatching {
            bridge.setSurface(handle, surface, width, height)
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to initialize the Vulkan ${bridge.effectLabel} surface", failure)
            false
        }
        if (!isCurrentSurface(surface, generation)) {
            discardStaleSurfaceOnWorker()
            return
        }
        if (!initialized) {
            failOnWorker("The Vulkan ${bridge.effectLabel} swapchain could not be initialized")
            return
        }

        val apiVersion = runCatching {
            VulkanApiVersion.fromEncoded(bridge.getApiVersion(handle))
        }.getOrNull()
        if (apiVersion == null) {
            failOnWorker("The initialized Vulkan API version is unavailable")
            return
        }
        initializedApiVersion = apiVersion
        ready = true
        readyGeneration = generation

        val pending = pendingPlaylistBitmap
        pendingPlaylistBitmap = null
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
                failOnWorker(
                    "The active wallpaper could not be prepared for " +
                        "${bridge.effectLabel}: ${failure.message}"
                )
            }
            return false
        }
        return uploadRenderImageOnWorker(renderImage, generation)
    }

    private fun uploadPlaylistBitmapOnWorker(
        bitmap: Bitmap,
        generation: Long
    ): Boolean {
        val width = latestWidth
        val height = latestHeight
        if (!isReadyForGeneration(generation) || width <= 0 || height <= 0) {
            pendingPlaylistBitmap?.recycleSafely()
            pendingPlaylistBitmap = bitmap
            return false
        }
        val renderImage = runCatching {
            WallpaperFitHelper.fitForRender(appContext, bitmap, width, height)
        }.getOrElse { failure ->
            bitmap.recycleSafely()
            if (isCurrentGeneration(generation)) {
                failOnWorker(
                    "The playlist wallpaper could not be prepared for " +
                        "${bridge.effectLabel}: ${failure.message}"
                )
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
        val handle = nativeHandle
        val uploaded = try {
            handle != 0L && bridge.uploadWallpaper(handle, bitmap)
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to upload the ${bridge.effectLabel} wallpaper texture", failure)
            false
        }
        if (!uploaded) {
            bitmap.recycleSafely()
            failOnWorker("The wallpaper texture could not be uploaded to Vulkan")
            return false
        }
        if (!isCurrentGeneration(generation)) {
            bitmap.recycleSafely()
            discardStaleSurfaceOnWorker()
            return false
        }

        scrollWindowX = renderImage.windowX
            .finiteOr(1f)
            .coerceIn(MIN_SCROLL_WINDOW, 1f)
        textureGeneration++
        val resourcesReady = try {
            onWallpaperUploadedOnWorker(handle, bitmap, textureGeneration)
        } catch (failure: Throwable) {
            Log.e(TAG, "Unable to prepare ${bridge.effectLabel} effect resources", failure)
            false
        } finally {
            bitmap.recycleSafely()
        }
        if (!resourcesReady) {
            failOnWorker("The ${bridge.effectLabel} resources could not be prepared")
            return false
        }
        needsReload = false
        return applyStateOnWorker()
    }

    private fun applyStateOnWorker(): Boolean {
        val handle = nativeHandle
        if (handle == 0L || failed.get()) return false
        return runCatching {
            bridge.setState(
                handle = handle,
                state = latestState.get(),
                scrollOffsetX = scrollOffsetX,
                scrollWindowX = scrollWindowX
            )
            true
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to update the Vulkan ${bridge.effectLabel} state", failure)
            failOnWorker("The Vulkan ${bridge.effectLabel} state could not be updated")
            false
        }
    }

    private fun drawOnWorker(generation: Long = surfaceGeneration) {
        val handle = nativeHandle
        if (paused || failed.get() || handle == 0L) return
        if (recreationQueued) return
        if (!isReadyForGeneration(generation)) {
            discardStaleSurfaceOnWorker()
            return
        }
        if (needsReload && !loadActiveTextureOnWorker(generation)) return

        val resourcesReady = runCatching {
            prepareFrameOnWorker(handle, textureGeneration)
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to prepare a ${bridge.effectLabel} frame", failure)
            false
        }
        if (!resourcesReady) {
            failOnWorker("The Vulkan ${bridge.effectLabel} frame resources failed")
            return
        }
        if (!applyStateOnWorker()) return

        val result = runCatching {
            bridge.render(handle)
        }.getOrDefault(RENDER_FATAL)
        if (!isCurrentGeneration(generation)) {
            discardStaleSurfaceOnWorker()
            return
        }
        when (result) {
            RENDER_SUCCESS -> {
                recreationBudget.reset()
                reportVulkanActive(initializedApiVersion)
            }
            RENDER_RECREATE -> scheduleSwapchainRecreationOnWorker(generation)
            else -> failOnWorker(
                "The Vulkan driver failed while presenting ${bridge.effectLabel}"
            )
        }
    }

    private fun scheduleSwapchainRecreationOnWorker(generation: Long) {
        if (recreationQueued || failed.get() || closed.get()) return
        if (!recreationBudget.tryAcquire()) {
            failOnWorker(
                "The Vulkan ${bridge.effectLabel} swapchain stayed out of date " +
                    "after ${recreationBudget.maxAttempts} recreation attempts"
            )
            return
        }

        val surface = latestSurface
        val width = latestWidth
        val height = latestHeight
        if (
            surface == null ||
            width <= 0 ||
            height <= 0 ||
            !isCurrentSurface(surface, generation)
        ) {
            discardStaleSurfaceOnWorker()
            return
        }

        recreationQueued = true
        ready = false
        readyGeneration = NO_GENERATION
        initializedApiVersion = null
        needsReload = true
        val accepted = worker.post {
            recreationQueued = false
            if (closed.get() || failed.get()) return@post
            if (!isCurrentSurface(surface, generation)) {
                discardStaleSurfaceOnWorker()
                return@post
            }
            initializeSurfaceOnWorker(
                surface = surface,
                width = width,
                height = height,
                generation = generation,
                isSwapchainRetry = true
            )
        }
        if (!accepted) {
            recreationQueued = false
            failOnWorker(
                "The Vulkan ${bridge.effectLabel} swapchain recreation could not be queued"
            )
        }
    }

    private fun reportVulkanActive(apiVersion: VulkanApiVersion?) {
        if (apiVersion == null || !activeReported.compareAndSet(false, true)) return
        mainHandler.post {
            onVulkanActive(this, apiVersion.encoded)
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean {
        return generation == surfaceGeneration &&
            latestSurface?.isValid == true &&
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
        readyGeneration = NO_GENERATION
        initializedApiVersion = null
        needsReload = true
        scrollWindowX = 1f
        onSurfaceResetOnWorker()
    }

    private fun discardStaleSurfaceOnWorker() {
        recreationQueued = false
        recreationBudget.reset()
        resetSurfaceStateOnWorker()
        if (nativeHandle != 0L) {
            runCatching { bridge.destroySurface(nativeHandle) }
                .onFailure { failure ->
                    Log.w(TAG, "Unable to discard a stale Vulkan surface", failure)
                }
        }
    }

    private fun clearSurfaceReference() {
        ++surfaceGeneration
        latestSurface = null
        latestWidth = 0
        latestHeight = 0
    }

    private fun destroySurfaceOnWorker(propagateFailure: Boolean) {
        recreationQueued = false
        recreationBudget.reset()
        resetSurfaceStateOnWorker()
        if (nativeHandle == 0L) return
        if (propagateFailure) {
            bridge.destroySurface(nativeHandle)
        } else {
            runCatching { bridge.destroySurface(nativeHandle) }
                .onFailure { failure ->
                    Log.w(
                        TAG,
                        "Unable to destroy the Vulkan ${bridge.effectLabel} surface",
                        failure
                    )
                }
        }
    }

    /**
     * Runs [action] on the worker and waits at most [timeoutMs] for it. Returns
     * false only when the wait timed out; worker failures are logged, not thrown.
     */
    private fun runSynchronouslyIfActive(timeoutMs: Long, action: () -> Unit): Boolean {
        if (closed.get() || failed.get()) return true
        if (Looper.myLooper() == renderThread.looper) {
            runCatching(action).onFailure { failure ->
                Log.w(TAG, "Unable to release the ${bridge.effectLabel} surface", failure)
            }
            return true
        }
        val completion = CountDownLatch(1)
        val accepted = worker.post {
            try {
                if (!closed.get() && !failed.get()) action()
            } catch (failure: Throwable) {
                Log.w(TAG, "Unable to release the ${bridge.effectLabel} surface", failure)
            } finally {
                completion.countDown()
            }
        }
        if (!accepted) return true
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var completed = false
        while (true) {
            try {
                val remaining = deadline - System.nanoTime()
                completed = remaining > 0 &&
                    completion.await(remaining, TimeUnit.NANOSECONDS)
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return completed
    }

    private fun runSynchronouslyIfActive(action: () -> Unit) {
        if (closed.get() || failed.get()) return
        if (Looper.myLooper() == renderThread.looper) {
            action()
            return
        }

        val completion = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val accepted = worker.post {
            try {
                if (!closed.get() && !failed.get()) action()
            } catch (workerFailure: Throwable) {
                failure.set(workerFailure)
            } finally {
                completion.countDown()
            }
        }
        check(accepted) {
            "The Vulkan ${bridge.effectLabel} worker could not quiesce its surface"
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
                "The Vulkan ${bridge.effectLabel} surface could not be quiesced",
                workerFailure
            )
        }
    }

    private fun postIfActive(action: () -> Unit) {
        if (closed.get() || failed.get()) return
        worker.post {
            if (closed.get() || failed.get()) return@post
            try {
                action()
            } catch (failure: Throwable) {
                Log.e(
                    TAG,
                    "Unexpected Vulkan ${bridge.effectLabel} worker failure",
                    failure
                )
                failOnWorker(
                    "The Vulkan ${bridge.effectLabel} worker failed: " +
                        failure.describe()
                )
            }
        }
    }

    private fun failOnWorker(reason: String) {
        if (!failed.compareAndSet(false, true)) return
        Log.e(TAG, reason)
        releaseNativeOnWorker()
        renderThread.quitSafely()
        mainHandler.post {
            onFatalFailure(this, reason)
        }
    }

    private fun releaseNativeOnWorker() {
        recreationQueued = false
        recreationBudget.reset()
        runCatching(::resetSurfaceStateOnWorker)
            .onFailure { failure ->
                Log.e(TAG, "Unable to reset Vulkan ${bridge.effectLabel} resources", failure)
            }
        runCatching { pendingPlaylistBitmap?.recycleSafely() }
            .onFailure { failure ->
                Log.e(TAG, "Unable to recycle a pending playlist bitmap", failure)
            }
        pendingPlaylistBitmap = null
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            runCatching { bridge.destroy(handle) }
                .onFailure { failure ->
                    Log.e(TAG, "Unable to destroy the Vulkan ${bridge.effectLabel} engine", failure)
                }
        }
        releaseEffectResourcesOnce()
    }

    private fun releaseEffectResourcesOnce() {
        if (!effectResourcesReleased.compareAndSet(false, true)) return
        runCatching(::onEffectResourcesReleased)
            .onFailure { failure ->
                Log.e(TAG, "Unable to release ${bridge.effectLabel} resources", failure)
            }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }

    private fun Throwable.describe(): String {
        return message?.takeIf { it.isNotBlank() }
            ?: javaClass.simpleName.takeIf { it.isNotBlank() }
            ?: "unknown failure"
    }

    private companion object {
        const val TAG = "VulkanEffectHost"
        const val RENDER_SUCCESS = 0
        const val RENDER_RECREATE = 1
        const val RENDER_FATAL = -1
        const val NO_GENERATION = -1L
        const val DEFAULT_SCROLL_OFFSET = 0.5f
        const val MIN_SCROLL_WINDOW = 0.001f
        const val SURFACE_DESTROY_TIMEOUT_MS = 3_000L
    }
}
