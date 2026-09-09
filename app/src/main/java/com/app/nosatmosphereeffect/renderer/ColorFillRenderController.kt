package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.helper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.backend.BackendReselectableRenderer
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreference
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeSession
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatusRepository
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendChange
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendResolution
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendSelection
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanColorFillHost
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanSupport

class ColorFillRenderController(
    context: Context,
    private val isReverse: Boolean
) : BackendReselectableRenderer {
    private val appContext = context.applicationContext
    private val effectId = if (isReverse) "COLORFILL_REVERSE" else "COLORFILL"
    private val lock = Any()

    private var state = ColorFillRenderState()
    private var engine: GLWallpaperService.GLEngine? = null
    private var activeHost: WallpaperRenderHost? = null
    private var openGlRenderer: ColorFillRenderer? = null
    private var vulkanHost: VulkanColorFillHost? = null
    private var backendPreference = GraphicsBackendPreference.AUTOMATIC
    private var activeVulkanApiVersion: Int? = null
    private var runtimeSession: RendererRuntimeSession? = null
    private var closed = false

    fun attach(engine: GLWallpaperService.GLEngine) {
        synchronized(lock) {
            check(this.engine == null) { "The Color Fill renderer is already attached" }
            check(!closed) { "The Color Fill renderer has been released" }
            this.engine = engine
        }

        val selection = VulkanSupport.selectBackend(appContext, effectId)
        synchronized(lock) {
            backendPreference = selection.preference
            runtimeSession = selection.runtimeSession
        }
        when (selection.backend) {
            GraphicsBackend.VULKAN -> attachVulkan(engine)
            GraphicsBackend.OPENGL_ES -> attachOpenGl(engine)
        }
    }

    override fun reselectBackend() {
        val snapshot = synchronized(lock) {
            val currentEngine = engine
            val currentHost = activeHost
            if (closed || currentEngine == null || currentHost == null) return
            BackendSnapshot(
                engine = currentEngine,
                host = currentHost,
                preference = backendPreference,
                backend = if (vulkanHost === currentHost) {
                    GraphicsBackend.VULKAN
                } else {
                    GraphicsBackend.OPENGL_ES
                }
            )
        }
        when (
            val change = VulkanSupport.resolveBackendChange(
                context = appContext,
                effectId = effectId,
                appliedPreference = snapshot.preference,
                activeBackend = snapshot.backend
            )
        ) {
            VulkanBackendChange.None -> Unit
            is VulkanBackendChange.PreferenceOnly ->
                refreshActiveSession(snapshot, change.resolution)
            is VulkanBackendChange.Swap -> swapBackend(snapshot, change)
        }
    }

    fun configure(dimLevel: Float, originX: Float, originY: Float) {
        val snapshot = synchronized(lock) {
            state = state.copy(
                dimLevel = dimLevel,
                originX = originX,
                originY = originY
            ).sanitized()
            state
        }
        applyState(snapshot)
    }

    /**
     * Clock frame pump and wallpaper-tint resolution.
     *
     * One per controller, i.e. one per wallpaper engine. Anything service-wide
     * would be torn down by whichever engine died first, silently leaving the
     * survivor with a clock that never advanced.
     */
    private val clockRuntime = ClockRuntime(
        context = appContext,
        workerName = "AtmoClockColorFill",
        onTick = ::onClockTick,
        onColorResolved = ::onClockColorResolved
    )

    fun configureClock(clock: ClockOverlayState) {
        // Resolved outside the lock: configuring the pump can fire an
        // immediate tick, which comes straight back through onClockTick.
        val resolved = clockRuntime.configure(clock)
        val snapshot = synchronized(lock) {
            state = state.copy(clock = resolved).sanitized()
            state
        }
        applyState(snapshot)
    }

    /**
     * Forwarded from the wallpaper engine so the pump idles when hidden, and
     * so the clock plays its entry animation when the wallpaper comes back.
     * Both backends hold the request until the clock would actually be on
     * screen, so becoming visible on the wrong side of the transition does not
     * spend the animation invisibly.
     */
    fun setEngineVisible(visible: Boolean) {
        clockRuntime.setEngineVisible(visible)
        if (!visible) return
        forEachClockTarget { gl, vk ->
            gl?.beginClockEntry()
            vk?.beginClockEntry()
        }
    }

    /** Kept for callers outside the pump (config updates, wallpaper swaps). */
    fun onSystemTimeChanged() = onClockTick()

    private fun onClockTick() {
        forEachClockTarget { gl, vk ->
            gl?.onClockTimeChanged()
            vk?.onTimeChanged()
        }
        requestRenderForClock()
    }

    private fun onClockColorResolved(color: Int) {
        val snapshot = synchronized(lock) {
            if (closed) return
            state = state.copy(clock = state.clock.copy(color = color)).sanitized()
            state
        }
        applyState(snapshot)
        requestRenderForClock()
    }

    private fun forEachClockTarget(
        block: (ColorFillRenderer?, VulkanColorFillHost?) -> Unit
    ) {
        val gl: ColorFillRenderer?
        val vk: VulkanColorFillHost?
        synchronized(lock) {
            gl = openGlRenderer
            vk = vulkanHost
        }
        block(gl, vk)
    }

    private fun requestRenderForClock() {
        synchronized(lock) { engine }?.requestRender()
    }

    fun setProgress(progress: Float) {
        val snapshot = synchronized(lock) {
            state = state.copy(progress = progress).sanitized()
            state
        }
        applyState(snapshot)
    }

    fun reloadTexture() {
        val gl: ColorFillRenderer?
        val vk: VulkanColorFillHost?
        synchronized(lock) {
            gl = openGlRenderer
            vk = vulkanHost
        }
        // The image is changing, so any wallpaper-derived clock tint is stale.
        clockRuntime.invalidateWallpaperColor()
        gl?.reloadTexture()
        vk?.reloadTexture()
    }

    fun queuePlaylistTransition(bitmap: Bitmap) {
        val gl: ColorFillRenderer?
        val vk: VulkanColorFillHost?
        val isClosed: Boolean
        synchronized(lock) {
            gl = openGlRenderer
            vk = vulkanHost
            isClosed = closed
        }
        when {
            isClosed -> bitmap.recycleSafely()
            vk != null -> vk.queuePlaylistTransition(bitmap)
            gl != null -> gl.queuePlaylistTransition(bitmap)
            else -> bitmap.recycleSafely()
        }
    }

    fun release() {
        clockRuntime.close()
        val gl: ColorFillRenderer?
        val vk: VulkanColorFillHost?
        val session: RendererRuntimeSession?
        synchronized(lock) {
            if (closed) return
            closed = true
            gl = openGlRenderer
            vk = vulkanHost
            session = runtimeSession
            vulkanHost = null
            openGlRenderer = null
            activeHost = null
            engine = null
            activeVulkanApiVersion = null
            runtimeSession = null
        }
        vk?.close()
        gl?.release()
        session?.let {
            publishRendererStatus("releasing the renderer session", it) { runtimeSession ->
                RendererRuntimeStatusRepository.recordReleased(
                    context = appContext,
                    session = runtimeSession
                )
            }
        }
    }

    private fun attachVulkan(engine: GLWallpaperService.GLEngine) {
        publishRendererStatus("marking Vulkan as initializing") { session ->
            RendererRuntimeStatusRepository.recordVulkanInitializing(appContext, session)
        }
        val snapshot = synchronized(lock) { state }
        val host = VulkanColorFillHost(
            context = appContext,
            reverse = isReverse,
            initialState = snapshot,
            onFatalFailure = ::fallbackToOpenGl,
            onVulkanActive = ::onVulkanActive
        )
        synchronized(lock) {
            if (closed) {
                host.close()
                return
            }
            vulkanHost = host
            activeHost = host
        }
        engine.installRenderHost(host)
    }

    private fun attachOpenGl(engine: GLWallpaperService.GLEngine) {
        val renderer = createOpenGlRenderer()
        val host = engine.setRenderer(renderer)
        synchronized(lock) {
            if (closed) {
                host.close()
                return
            }
            openGlRenderer = renderer
            activeHost = host
        }
        publishRendererStatus("marking OpenGL ES as active") { session ->
            RendererRuntimeStatusRepository.recordOpenGlActive(appContext, session)
        }
    }

    private fun fallbackToOpenGl(failedHost: VulkanColorFillHost, reason: String) {
        val currentEngine: GLWallpaperService.GLEngine
        synchronized(lock) {
            if (closed || activeHost !== failedHost) return
            currentEngine = engine ?: return
        }

        runCatching { VulkanSupport.recordFailure(appContext, effectId, reason) }
            .onFailure { failure ->
                Log.w(TAG, "Unable to persist the Vulkan fallback state", failure)
            }
        val fallback = runCatching {
            val renderer = createOpenGlRenderer()
            renderer to currentEngine.createOpenGlRenderHost(renderer)
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to create the OpenGL ES fallback renderer", failure)
            return
        }
        val (renderer, replacement) = fallback
        if (!currentEngine.replaceRenderHost(failedHost, replacement)) {
            Log.e(TAG, "Unable to attach the OpenGL ES fallback renderer")
            return
        }

        synchronized(lock) {
            if (closed) return
            openGlRenderer = renderer
            vulkanHost = null
            activeHost = replacement
            activeVulkanApiVersion = null
        }
        publishRendererStatus("publishing the OpenGL ES fallback") { session ->
            RendererRuntimeStatusRepository.recordOpenGlActive(
                context = appContext,
                session = session,
                reason = reason
            )
        }
        renderer.reloadTexture()
        currentEngine.requestRender()
        Log.w(TAG, "Color Fill switched to OpenGL ES after Vulkan failed: $reason")
    }

    private fun swapBackend(
        snapshot: BackendSnapshot,
        change: VulkanBackendChange.Swap
    ) {
        val resolution = change.resolution
        var replacementRenderer: ColorFillRenderer? = null
        var replacementVulkan: VulkanColorFillHost? = null
        val replacement = runCatching {
            when (resolution.backend) {
                GraphicsBackend.VULKAN -> {
                    VulkanColorFillHost(
                        context = appContext,
                        reverse = isReverse,
                        initialState = synchronized(lock) { state },
                        onFatalFailure = ::fallbackToOpenGl,
                        onVulkanActive = ::onVulkanActive
                    ).also { replacementVulkan = it }
                }
                GraphicsBackend.OPENGL_ES -> {
                    createOpenGlRenderer().also { renderer ->
                        replacementRenderer = renderer
                    }.let(snapshot.engine::createOpenGlRenderHost)
                }
            }
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to prepare the requested Color Fill renderer", failure)
            return
        }
        if (!snapshot.engine.replaceRenderHost(snapshot.host, replacement)) {
            Log.e(TAG, "Unable to switch the Color Fill renderer backend")
            return
        }

        val selection = VulkanSupport.publishActiveSelection(
            context = appContext,
            effectId = effectId,
            resolution = resolution,
            activeVulkanApiVersion = null
        )
        val previousSession = synchronized(lock) {
            if (closed) {
                releaseSelection(selection)
                return
            }
            val previous = runtimeSession
            openGlRenderer = replacementRenderer
            vulkanHost = replacementVulkan
            activeHost = replacement
            backendPreference = selection.preference
            activeVulkanApiVersion = null
            runtimeSession = selection.runtimeSession
            previous
        }
        publishRendererStatus("releasing the previous renderer session", previousSession) {
            RendererRuntimeStatusRepository.recordReleased(appContext, it)
        }
        snapshot.engine.requestRender()
    }

    private fun refreshActiveSession(
        snapshot: BackendSnapshot,
        resolution: VulkanBackendResolution
    ) {
        val activeVersion = synchronized(lock) {
            if (closed || activeHost !== snapshot.host) return
            activeVulkanApiVersion
        }
        val selection = VulkanSupport.publishActiveSelection(
            context = appContext,
            effectId = effectId,
            resolution = resolution,
            activeVulkanApiVersion = activeVersion
        )
        val previousSession = synchronized(lock) {
            if (closed || activeHost !== snapshot.host) {
                releaseSelection(selection)
                return
            }
            val previous = runtimeSession
            backendPreference = resolution.preference
            runtimeSession = selection.runtimeSession
            previous
        }
        publishRendererStatus("releasing the previous renderer session", previousSession) {
            RendererRuntimeStatusRepository.recordReleased(appContext, it)
        }
    }

    private fun onVulkanActive(host: VulkanColorFillHost, packedVersion: Int) {
        val isCurrentHost = synchronized(lock) {
            (!closed && activeHost === host).also { isCurrent ->
                if (isCurrent) activeVulkanApiVersion = packedVersion
            }
        }
        if (!isCurrentHost) return
        publishRendererStatus("marking Vulkan as active") { session ->
            RendererRuntimeStatusRepository.recordVulkanActive(
                context = appContext,
                session = session,
                packedVersion = packedVersion
            )
        }
    }

    private fun createOpenGlRenderer(): ColorFillRenderer {
        val snapshot = synchronized(lock) { state }
        return ColorFillRenderer(appContext, isReverse = isReverse).apply {
            blurStrength = snapshot.progress
            dimLevel = snapshot.dimLevel
            originX = snapshot.originX
            originY = snapshot.originY
            applyClockState(snapshot.clock)
            onAnimationFrameRequested = ::requestRenderForClock
        }
    }

    private fun applyState(snapshot: ColorFillRenderState) {
        val gl: ColorFillRenderer?
        val vk: VulkanColorFillHost?
        synchronized(lock) {
            gl = openGlRenderer
            vk = vulkanHost
        }
        gl?.apply {
            blurStrength = snapshot.progress
            dimLevel = snapshot.dimLevel
            originX = snapshot.originX
            originY = snapshot.originY
            applyClockState(snapshot.clock)
        }
        vk?.updateState(snapshot)
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private inline fun publishRendererStatus(
        operation: String,
        session: RendererRuntimeSession? = synchronized(lock) { runtimeSession },
        publish: (RendererRuntimeSession) -> Unit
    ) {
        if (session == null) return
        runCatching { publish(session) }
            .onFailure { failure ->
                Log.w(TAG, "Unable to update renderer status while $operation", failure)
            }
    }

    private fun releaseSelection(selection: VulkanBackendSelection) {
        publishRendererStatus("releasing an unused renderer selection", selection.runtimeSession) {
            RendererRuntimeStatusRepository.recordReleased(appContext, it)
        }
    }

    private data class BackendSnapshot(
        val engine: GLWallpaperService.GLEngine,
        val host: WallpaperRenderHost,
        val preference: GraphicsBackendPreference,
        val backend: GraphicsBackend
    )

    private companion object {
        const val TAG = "ColorFillController"
    }
}
