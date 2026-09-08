package com.app.nosatmosphereeffect.helper

import android.app.WallpaperColors
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Implemented by renderers that can pan their wallpaper horizontally in response
 * to home-screen page swipes (launcher parallax). The base [GLWallpaperService.GLEngine]
 * forwards [WallpaperService.Engine.onOffsetsChanged] to the active renderer when
 * it implements this, so individual services need no scrolling code of their own.
 */
interface WallpaperScrollRenderer {
    /** @param xOffset launcher horizontal offset, 0.0 (first page) .. 1.0 (last page). */
    fun setWallpaperOffset(xOffset: Float)
}

abstract class GLWallpaperService : WallpaperService() {

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The prepared posture image is a convenience, never a requirement.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            WallpaperPostureCache.clear()
        }
    }

    override fun onDestroy() {
        WallpaperPostureCache.clear()
        super.onDestroy()
    }

    private data class WallpaperColorSource(
        val lastModified: Long,
        val length: Long
    )

    open inner class GLEngine : Engine() {
        private var renderHost: WallpaperRenderHost? = null
        private val rendererLifecycle = RendererLifecycleGate()
        private val pauseHandler = Handler(Looper.getMainLooper())
        private val pauseRunnable = Runnable {
            dispatchToHost("pausing the render surface") { it.onPause() }
        }
        private val systemColorHandler = Handler(Looper.getMainLooper())
        private val postureHandler = Handler(Looper.getMainLooper())
        private var settledSurfaceSize: SurfaceSize? = null
        private val posturePrewarmRunnable = Runnable {
            val current = settledSurfaceSize ?: return@Runnable
            posturePrewarmExecutor.execute { prewarmAlternatePosture(current) }
        }
        private val systemColorExecutor = Executors.newSingleThreadExecutor()
        private var cachedSystemColors: WallpaperColors? = null
        private var cachedColorSource: WallpaperColorSource? = null
        private var pendingColorSource: WallpaperColorSource? = null
        private var colorRequestVersion = 0L
        private var engineDestroyed = false
        private val wallpaperSurfaceHolder = WallpaperSurfaceHolderState()
        private var surfaceCreated = false
        private var surfaceFormat = PixelFormat.OPAQUE
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var visibilityKnown = false
        private var engineVisible = false
        private var wallpaperOffset = 0.5f
        private val publishSystemColors = Runnable {
            requestSystemColorExtraction(invalidateCache = true)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            wallpaperSurfaceHolder.remember(surfaceHolder)
            super.onCreate(surfaceHolder)
            rendererLifecycle.reset()
            surfaceHolder.setFormat(PixelFormat.OPAQUE)
            setOffsetNotificationsEnabled(true)
            requestSystemColorExtraction(invalidateCache = true)
        }

        fun setRenderer(renderer: GLSurfaceView.Renderer): WallpaperRenderHost {
            val host = OpenGlRenderHost(
                context = this@GLWallpaperService,
                wallpaperHolder = wallpaperSurfaceHolder.requireHolder(),
                renderer = renderer
            )
            installRenderHost(host)
            return host
        }

        fun installRenderHost(host: WallpaperRenderHost) {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "Render hosts must be installed on the wallpaper engine thread"
            }
            check(!engineDestroyed) {
                "The wallpaper engine has already been destroyed"
            }
            check(renderHost == null) {
                "A render host is already installed"
            }

            val replayFailure = RenderHostSwapGuard.prepare(host, ::replaySurfaceState)
            if (replayFailure != null) {
                throw IllegalStateException(
                    "The render host could not attach to the wallpaper surface",
                    replayFailure
                )
            }
            renderHost = host
            rendererLifecycle.markRendererAttached()
        }

        fun replaceRenderHost(
            expected: WallpaperRenderHost,
            replacement: WallpaperRenderHost
        ): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper()) {
                "Render hosts must be replaced on the wallpaper engine thread"
            }
            if (engineDestroyed || renderHost !== expected) {
                replacement.close()
                return false
            }

            val activeSurfaceHolder = if (surfaceCreated) {
                wallpaperSurfaceHolder.requireHolder()
            } else {
                null
            }
            val replayFailure = RenderHostSwapGuard.prepareHandoff(
                expected = expected,
                replacement = replacement,
                quiesce = { host ->
                    activeSurfaceHolder?.let(host::quiesceSurface)
                },
                replay = ::replaySurfaceState
            )
            if (replayFailure != null) {
                Log.e(
                    TAG,
                    "Unable to prepare the replacement render host",
                    replayFailure
                )
                return false
            }

            renderHost = replacement
            runCatching { expected.close() }
                .onFailure { failure ->
                    Log.e(TAG, "Unable to close the previous render host", failure)
                }
            return true
        }

        fun createOpenGlRenderHost(renderer: GLSurfaceView.Renderer): WallpaperRenderHost {
            return OpenGlRenderHost(
                context = this@GLWallpaperService,
                wallpaperHolder = wallpaperSurfaceHolder.requireHolder(),
                renderer = renderer
            )
        }

        fun requestRender() {
            dispatchToHost("requesting a frame") { it.requestRender() }
        }

        /**
         * Invalidates and re-extracts the active image palette. Calls made during
         * playlist file I/O are safely marshalled onto the engine's main thread.
         */
        protected fun notifySystemColorsChanged() {
            PaletteSyncDiagnostics.record(
                this@GLWallpaperService,
                PaletteSyncDiagnostics.STAGE_REFRESH_QUEUED,
                "${this@GLWallpaperService::class.java.simpleName} queued a palette refresh"
            )
            systemColorHandler.removeCallbacks(publishSystemColors)
            systemColorHandler.post(publishSystemColors)
        }

        final override fun onComputeColors(): WallpaperColors? {
            if (!SystemColorSyncPreferences.isEnabled(this@GLWallpaperService)) {
                PaletteSyncDiagnostics.record(
                    this@GLWallpaperService,
                    PaletteSyncDiagnostics.STAGE_DISABLED,
                    "System color sync is disabled"
                )
                clearSystemColorState()
                return null
            }

            val wallpaperFile = File(filesDir, WallpaperFitHelper.ACTIVE_WALLPAPER_FILE)
            val source = colorSourceFor(wallpaperFile)
            if (source == null) {
                PaletteSyncDiagnostics.record(
                    this@GLWallpaperService,
                    PaletteSyncDiagnostics.STAGE_MISSING_WALLPAPER,
                    "The active wallpaper file is missing or unreadable",
                    "FileNotFoundException: Active wallpaper image is missing or unreadable"
                )
                clearSystemColorState()
                return null
            }

            if (cachedColorSource == source) return cachedSystemColors

            requestSystemColorExtraction(invalidateCache = false)
            return null
        }

        private fun requestSystemColorExtraction(invalidateCache: Boolean) {
            if (engineDestroyed) return

            if (invalidateCache) {
                cachedSystemColors = null
                cachedColorSource = null
            }
            if (!SystemColorSyncPreferences.isEnabled(this@GLWallpaperService)) {
                PaletteSyncDiagnostics.record(
                    this@GLWallpaperService,
                    PaletteSyncDiagnostics.STAGE_DISABLED,
                    "System color sync is disabled"
                )
                clearSystemColorState()
                colorRequestVersion++
                return
            }

            val wallpaperFile = File(filesDir, WallpaperFitHelper.ACTIVE_WALLPAPER_FILE)
            val source = colorSourceFor(wallpaperFile) ?: run {
                PaletteSyncDiagnostics.record(
                    this@GLWallpaperService,
                    PaletteSyncDiagnostics.STAGE_MISSING_WALLPAPER,
                    "The active wallpaper file is missing or unreadable",
                    "FileNotFoundException: Active wallpaper image is missing or unreadable"
                )
                clearSystemColorState()
                colorRequestVersion++
                return
            }
            if (!invalidateCache && (cachedColorSource == source || pendingColorSource == source)) {
                return
            }

            pendingColorSource = source
            val requestVersion = ++colorRequestVersion
            PaletteSyncDiagnostics.record(
                this@GLWallpaperService,
                PaletteSyncDiagnostics.STAGE_EXTRACTING,
                "${this@GLWallpaperService::class.java.simpleName} is extracting colors"
            )
            systemColorExecutor.execute {
                val extraction = runCatching {
                    WallpaperColorExtractor.extract(wallpaperFile)
                        ?: error("Wallpaper image could not be decoded")
                }
                systemColorHandler.post {
                    if (engineDestroyed || requestVersion != colorRequestVersion) return@post
                    pendingColorSource = null

                    if (!SystemColorSyncPreferences.isEnabled(this@GLWallpaperService)) {
                        clearSystemColorState()
                        return@post
                    }

                    val latestSource = colorSourceFor(wallpaperFile)
                    if (latestSource != source) {
                        requestSystemColorExtraction(invalidateCache = true)
                        return@post
                    }

                    val colors = extraction.getOrNull()
                    cachedSystemColors = colors
                    cachedColorSource = source
                    if (colors != null) {
                        try {
                            notifyColorsChanged()
                            PaletteSyncDiagnostics.record(
                                this@GLWallpaperService,
                                PaletteSyncDiagnostics.STAGE_PUBLISHED,
                                "${this@GLWallpaperService::class.java.simpleName} completed notifyColorsChanged()",
                                clearError = true
                            )
                        } catch (failure: Throwable) {
                            PaletteSyncDiagnostics.record(
                                this@GLWallpaperService,
                                PaletteSyncDiagnostics.STAGE_PUBLISH_FAILED,
                                "Android rejected the wallpaper color callback",
                                failure.toDiagnosticText()
                            )
                        }
                    } else {
                        val failure = extraction.exceptionOrNull()
                        PaletteSyncDiagnostics.record(
                            this@GLWallpaperService,
                            PaletteSyncDiagnostics.STAGE_EXTRACTION_FAILED,
                            "The wallpaper engine could not publish colors",
                            failure?.toDiagnosticText() ?: "Unknown extraction failure"
                        )
                        runCatching { notifyColorsChanged() }.onFailure { publishFailure ->
                            PaletteSyncDiagnostics.record(
                                this@GLWallpaperService,
                                PaletteSyncDiagnostics.STAGE_PUBLISH_FAILED,
                                "Android rejected the empty wallpaper color callback",
                                publishFailure.toDiagnosticText()
                            )
                        }
                    }
                }
            }
        }

        private fun colorSourceFor(file: File): WallpaperColorSource? {
            if (!file.isFile) return null
            return WallpaperColorSource(
                lastModified = file.lastModified(),
                length = file.length()
            )
        }

        private fun clearSystemColorState() {
            cachedSystemColors = null
            cachedColorSource = null
            pendingColorSource = null
        }

        private fun Throwable.toDiagnosticText(): String {
            val readableMessage = message?.takeIf { it.isNotBlank() }
            return if (readableMessage == null) {
                javaClass.simpleName
            } else {
                "${javaClass.simpleName}: $readableMessage"
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            wallpaperOffset = xOffset.coerceIn(0f, 1f)
            dispatchToHost("updating the wallpaper offset") {
                it.setWallpaperOffset(wallpaperOffset)
            }
            requestRender()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            visibilityKnown = true
            engineVisible = visible
            if (visible) {
                pauseHandler.removeCallbacks(pauseRunnable)
                dispatchToHost("resuming the render surface") { it.onResume() }
            } else {
                requestRender()
                pauseHandler.removeCallbacks(pauseRunnable)
                pauseHandler.postDelayed(pauseRunnable, 80L)
            }
        }

        override fun onDestroy() {
            engineDestroyed = true
            colorRequestVersion++
            pauseHandler.removeCallbacks(pauseRunnable)
            systemColorHandler.removeCallbacks(publishSystemColors)
            postureHandler.removeCallbacks(posturePrewarmRunnable)
            systemColorExecutor.shutdownNow()
            val host = renderHost
            dispatchToHost("pausing the destroyed render surface") { it.onPause() }
            rendererLifecycle.markDestroyed()
            try {
                host?.close()
            } catch (failure: RuntimeException) {
                Log.e(TAG, "Unable to stop the destroyed render surface", failure)
            } finally {
                renderHost = null
                wallpaperSurfaceHolder.clear()
                super.onDestroy()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            wallpaperSurfaceHolder.remember(holder)
            super.onSurfaceChanged(holder, format, width, height)
            surfaceFormat = format
            surfaceWidth = width
            surfaceHeight = height
            dispatchToHost("handling a render surface change") {
                it.onSurfaceChanged(holder, format, width, height)
            }
            schedulePosturePrewarm(width, height)
        }

        /**
         * Foldables resize the wallpaper surface when the device is folded or
         * unfolded. Until the renderer submits a frame at the new size the
         * compositor keeps scaling the previous one, which is the squeeze users
         * report, and the renderer cannot submit that frame until it has
         * re-fitted the image — a decode plus a full-surface rasterization on
         * the render thread, with the engine thread blocked behind it.
         *
         * So once a size has settled, prepare the other one in the background.
         * The next posture change then only has to upload a texture and the
         * correct frame lands immediately. Nothing here is load-bearing: a
         * failure just means the old, slower path runs.
         */
        private fun schedulePosturePrewarm(width: Int, height: Int) {
            postureHandler.removeCallbacks(posturePrewarmRunnable)
            if (width <= 0 || height <= 0) return
            // Previews live in an activity at arbitrary sizes; they would only
            // pollute the record of what the device actually renders at.
            if (isPreview) return
            settledSurfaceSize = SurfaceSize(width, height)
            // Some devices deliver intermediate sizes while the fold animation
            // runs. Waiting for the size to hold still keeps those out of the
            // record and keeps the decode off the tail of the animation.
            postureHandler.postDelayed(posturePrewarmRunnable, POSTURE_SETTLE_MS)
        }

        private fun prewarmAlternatePosture(current: SurfaceSize) {
            // The engine can be torn down between the post and this running;
            // preparing an image nothing will ask for only wastes memory.
            if (engineDestroyed) return
            val context = this@GLWallpaperService.applicationContext
            try {
                val other = FoldPostureSizes.record(context, current) ?: return
                WallpaperFitHelper.prewarm(context, other.width, other.height)
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Could not prepare the alternate posture image", failure)
                WallpaperPostureCache.clear()
            } catch (failure: OutOfMemoryError) {
                Log.w(TAG, "Not enough memory to prepare the alternate posture image", failure)
                WallpaperPostureCache.clear()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            wallpaperSurfaceHolder.remember(holder)
            super.onSurfaceCreated(holder)
            surfaceCreated = true
            dispatchToHost("creating the render surface") { it.onSurfaceCreated(holder) }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            wallpaperSurfaceHolder.remember(holder)
            super.onSurfaceDestroyed(holder)
            dispatchToHost("destroying the render surface") { it.onSurfaceDestroyed(holder) }
            surfaceCreated = false
            surfaceWidth = 0
            surfaceHeight = 0
        }

        private fun replaySurfaceState(host: WallpaperRenderHost) {
            if (surfaceCreated) {
                val holder = wallpaperSurfaceHolder.requireHolder()
                host.onSurfaceCreated(holder)
                if (surfaceWidth > 0 && surfaceHeight > 0) {
                    host.onSurfaceChanged(
                        holder,
                        surfaceFormat,
                        surfaceWidth,
                        surfaceHeight
                    )
                }
            }
            host.setWallpaperOffset(wallpaperOffset)
            if (visibilityKnown) {
                if (engineVisible) host.onResume() else host.onPause()
            }
            host.requestRender()
        }

        private inline fun dispatchToHost(
            operation: String,
            action: (WallpaperRenderHost) -> Unit
        ) {
            if (!rendererLifecycle.canDispatchToRenderer()) return
            val host = renderHost ?: return
            try {
                action(host)
            } catch (failure: RuntimeException) {
                Log.e(TAG, "Render lifecycle failure while $operation", failure)
            }
        }
    }

    private class OpenGlRenderHost(
        context: Context,
        wallpaperHolder: SurfaceHolder,
        renderer: GLSurfaceView.Renderer
    ) : WallpaperRenderHost {
        private var closed = false
        private val view = WallpaperSurfaceHolderConstruction.withHolder(
            wallpaperHolder
        ) {
            WallpaperGLSurfaceView(context)
        }.apply {
            attachWallpaperHolder(wallpaperHolder)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        private val scrollRenderer = renderer as? WallpaperScrollRenderer

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            if (!closed) view.surfaceCreated(holder)
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            if (!closed) view.surfaceChanged(holder, format, width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            if (!closed) view.surfaceDestroyed(holder)
        }

        override fun onResume() {
            if (!closed) view.onResume()
        }

        override fun onPause() {
            if (!closed) view.onPause()
        }

        override fun requestRender() {
            if (!closed) view.requestRender()
        }

        override fun setWallpaperOffset(xOffset: Float) {
            if (!closed) scrollRenderer?.setWallpaperOffset(xOffset)
        }

        override fun close() {
            if (closed) return
            closed = true
            view.destroy()
        }

        private class WallpaperGLSurfaceView(
            context: Context
        ) : GLSurfaceView(context) {
            private var wallpaperHolder: SurfaceHolder? = null
            private var destroyStarted = false

            init {
                setEGLConfigChooser(8, 8, 8, 0, 16, 0)
                setEGLContextClientVersion(3)
                preserveEGLContextOnPause = true
            }

            override fun getHolder(): SurfaceHolder {
                return wallpaperHolder
                    ?: checkNotNull(WallpaperSurfaceHolderConstruction.holder()) {
                        "The wallpaper SurfaceHolder was unavailable during GLSurfaceView construction"
                    }
            }

            fun attachWallpaperHolder(holder: SurfaceHolder) {
                wallpaperHolder = holder
            }

            fun destroy() {
                onDetachedFromWindow()
            }

            override fun onDetachedFromWindow() {
                if (destroyStarted) return
                destroyStarted = true
                super.onDetachedFromWindow()
            }
        }
    }

    private companion object {
        const val TAG = "GLWallpaperService"

        /** How long a surface size has to hold still before it counts as a posture. */
        const val POSTURE_SETTLE_MS = 700L

        /**
         * Shared by every engine in the process (live wallpaper and the system
         * preview can both be alive at once) so posture preparation is serial
         * and never competes with itself for memory.
         */
        val posturePrewarmExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
