package com.app.nosatmosphereeffect.renderer.backend

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.Log
import com.app.nosatmosphereeffect.clock.ClockOverlayState
import com.app.nosatmosphereeffect.clock.ClockRuntime
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeSession
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatusRepository
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendChange
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendResolution
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendSelection
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanSupport
import com.app.nosatmosphereeffect.wallpaper.GLWallpaperService
import com.app.nosatmosphereeffect.wallpaper.WallpaperRenderHost

/** What an effect's controller needs from its OpenGL ES renderer. */
internal interface EffectOpenGlRenderer : GLSurfaceView.Renderer {
    fun reloadTexture()
    fun queuePlaylistTransition(bitmap: Bitmap)
    fun release()
    fun beginClockEntry()
    fun onClockTimeChanged()
}

/** What an effect's controller needs from its Vulkan host. */
internal interface EffectVulkanHost : WallpaperRenderHost {
    fun reloadTexture()
    fun queuePlaylistTransition(bitmap: Bitmap)
    fun beginClockEntry()
    fun onTimeChanged()
}

/** What every effect's controller offers its wallpaper service. */
internal interface EffectController : BackendReselectableRenderer {
    fun attach(engine: GLWallpaperService.GLEngine)
    fun configureClock(clock: ClockOverlayState)
    fun setEngineVisible(visible: Boolean)
    fun onSystemTimeChanged()
    fun reloadTexture()
    fun queuePlaylistTransition(bitmap: Bitmap)
    fun release()
}

/**
 * Runs one effect on whichever backend suits the phone: Vulkan where it
 * works, OpenGL ES otherwise or after Vulkan fails, switching live when the
 * user changes the preference, and keeping the renderer status and the clock
 * runtime in step. Each effect's controller owns one and supplies the parts
 * that are its own through [Effect]: how its renderers are made and how its
 * state reaches them.
 */
internal class EffectBackends<S : Any, GL : EffectOpenGlRenderer, VK : EffectVulkanHost>(
    context: Context,
    private val effectId: String,
    /** For log messages and the clock worker's name. */
    private val label: String,
    initialState: S,
    private val effect: Effect<S, GL, VK>
) : EffectController {

    /** An effect's own part: its renderers and its state. */
    interface Effect<S : Any, GL, VK> {
        fun createOpenGl(context: Context, state: S, engine: GLWallpaperService.GLEngine): GL
        fun createVulkan(
            context: Context,
            state: S,
            onFatalFailure: (WallpaperRenderHost, String) -> Unit,
            onVulkanActive: (WallpaperRenderHost, Int) -> Unit
        ): VK
        fun applyToOpenGl(renderer: GL, state: S)
        fun applyToVulkan(host: VK, state: S)
        fun sanitize(state: S): S
        fun clockOf(state: S): ClockOverlayState
        fun withClock(state: S, clock: ClockOverlayState): S
    }

    private val appContext = context.applicationContext
    private val lock = Any()

    private var state = effect.sanitize(initialState)
    private var engine: GLWallpaperService.GLEngine? = null
    private var activeHost: WallpaperRenderHost? = null
    private var openGlRenderer: GL? = null
    private var vulkanHost: VK? = null
    private var backendPreference = GraphicsBackendPreference.AUTOMATIC
    private var activeVulkanApiVersion: Int? = null
    private var runtimeSession: RendererRuntimeSession? = null
    private var closed = false

    /**
     * Clock frame pump and wallpaper-tint resolution. One per controller, i.e.
     * one per wallpaper engine: anything service-wide would be torn down by
     * whichever engine died first, leaving the survivor's clock stopped.
     */
    private val clockRuntime = ClockRuntime(
        context = appContext,
        workerName = "AtmoClock" + label.filter(Char::isLetterOrDigit),
        onTick = ::onClockTick,
        onColorResolved = ::onClockColorResolved
    )

    /** The state now. */
    val current: S get() = synchronized(lock) { state }

    /** Changes the state and hands it to the active renderer; returns it. */
    fun update(change: (S) -> S): S {
        val snapshot = synchronized(lock) {
            state = effect.sanitize(change(state))
            state
        }
        applyState(snapshot)
        return snapshot
    }

    /** Runs [block] on the OpenGL ES renderer, if that is the one drawing. */
    fun withOpenGl(block: (GL) -> Unit) {
        synchronized(lock) { openGlRenderer }?.let(block)
    }

    override fun attach(engine: GLWallpaperService.GLEngine) {
        synchronized(lock) {
            check(this.engine == null) { "The $label renderer is already attached" }
            check(!closed) { "The $label renderer has been released" }
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
                backend = if (vulkanHost === currentHost) GraphicsBackend.VULKAN else GraphicsBackend.OPENGL_ES
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
            is VulkanBackendChange.PreferenceOnly -> refreshActiveSession(snapshot, change.resolution)
            is VulkanBackendChange.Swap -> swapBackend(snapshot, change)
        }
    }

    override fun configureClock(clock: ClockOverlayState) {
        // Resolved outside the lock: configuring the pump can fire an
        // immediate tick, which comes straight back through onClockTick.
        val resolved = clockRuntime.configure(clock)
        update { effect.withClock(it, resolved) }
    }

    /**
     * Forwarded from the wallpaper engine so the pump idles when hidden, and
     * so the clock plays its entry animation when the wallpaper comes back.
     * Both backends hold the request until the clock would actually be on
     * screen, so becoming visible on the wrong side of the transition does not
     * spend the animation invisibly.
     */
    override fun setEngineVisible(visible: Boolean) {
        clockRuntime.setEngineVisible(visible)
        if (!visible) return
        val (gl, vk) = targets()
        gl?.beginClockEntry()
        vk?.beginClockEntry()
    }

    /** Kept for callers outside the pump (config updates, wallpaper swaps). */
    override fun onSystemTimeChanged() = onClockTick()

    private fun onClockTick() {
        val (gl, vk) = targets()
        gl?.onClockTimeChanged()
        vk?.onTimeChanged()
        requestRender()
    }

    private fun onClockColorResolved(color: Int) {
        if (synchronized(lock) { closed }) return
        update { effect.withClock(it, effect.clockOf(it).copy(color = color)) }
        requestRender()
    }

    private fun requestRender() {
        synchronized(lock) { engine }?.requestRender()
    }

    override fun reloadTexture() {
        val (gl, vk) = targets()
        // The image is changing, so any wallpaper-derived clock tint is stale.
        clockRuntime.invalidateWallpaperColor()
        gl?.reloadTexture()
        vk?.reloadTexture()
    }

    override fun queuePlaylistTransition(bitmap: Bitmap) {
        val gl: GL?
        val vk: VK?
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

    override fun release() {
        clockRuntime.close()
        val vk: VK?
        val gl: GL?
        val session: RendererRuntimeSession?
        synchronized(lock) {
            if (closed) return
            closed = true
            vk = vulkanHost
            gl = openGlRenderer
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
        publishRendererStatus("releasing the renderer session", session) {
            RendererRuntimeStatusRepository.recordReleased(appContext, it)
        }
    }

    private fun targets(): Pair<GL?, VK?> = synchronized(lock) { openGlRenderer to vulkanHost }

    private fun applyState(snapshot: S) {
        val (gl, vk) = targets()
        gl?.let { effect.applyToOpenGl(it, snapshot) }
        vk?.let { effect.applyToVulkan(it, snapshot) }
    }

    private fun createOpenGl(engine: GLWallpaperService.GLEngine): GL =
        effect.createOpenGl(appContext, current, engine)

    private fun createVulkan(): VK =
        effect.createVulkan(appContext, current, ::fallbackToOpenGl, ::onVulkanActive)

    private fun attachVulkan(engine: GLWallpaperService.GLEngine) {
        publishRendererStatus("marking Vulkan as initializing") {
            RendererRuntimeStatusRepository.recordVulkanInitializing(appContext, it)
        }
        val host = createVulkan()
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
        val renderer = createOpenGl(engine)
        val host = engine.setRenderer(renderer)
        synchronized(lock) {
            if (closed) {
                host.close()
                renderer.release()
                return
            }
            openGlRenderer = renderer
            activeHost = host
        }
        publishRendererStatus("marking OpenGL ES as active") {
            RendererRuntimeStatusRepository.recordOpenGlActive(appContext, it)
        }
    }

    private fun fallbackToOpenGl(failedHost: WallpaperRenderHost, reason: String) {
        val currentEngine: GLWallpaperService.GLEngine
        synchronized(lock) {
            if (closed || activeHost !== failedHost) return
            currentEngine = engine ?: return
        }
        runCatching { VulkanSupport.recordFailure(appContext, effectId, reason) }
            .onFailure { failure -> Log.w(TAG, "Unable to persist the Vulkan fallback state", failure) }
        val fallback = runCatching {
            val renderer = createOpenGl(currentEngine)
            renderer to currentEngine.createOpenGlRenderHost(renderer)
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to create the OpenGL ES $label fallback", failure)
            return
        }
        val (renderer, replacement) = fallback
        if (!currentEngine.replaceRenderHost(failedHost, replacement)) {
            renderer.release()
            Log.e(TAG, "Unable to attach the OpenGL ES $label fallback")
            return
        }
        synchronized(lock) {
            if (closed) {
                renderer.release()
                return
            }
            openGlRenderer = renderer
            vulkanHost = null
            activeHost = replacement
            activeVulkanApiVersion = null
        }
        publishRendererStatus("publishing the OpenGL ES fallback") {
            RendererRuntimeStatusRepository.recordOpenGlActive(context = appContext, session = it, reason = reason)
        }
        renderer.reloadTexture()
        currentEngine.requestRender()
        Log.w(TAG, "$label switched to OpenGL ES after Vulkan failed: $reason")
    }

    private fun swapBackend(snapshot: BackendSnapshot, change: VulkanBackendChange.Swap) {
        val resolution = change.resolution
        var replacementRenderer: GL? = null
        var replacementVulkan: VK? = null
        val replacement = runCatching {
            when (resolution.backend) {
                GraphicsBackend.VULKAN -> createVulkan().also { replacementVulkan = it }
                GraphicsBackend.OPENGL_ES -> createOpenGl(snapshot.engine)
                    .also { replacementRenderer = it }
                    .let(snapshot.engine::createOpenGlRenderHost)
            }
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to prepare the requested $label renderer", failure)
            replacementRenderer?.release()
            return
        }
        if (!snapshot.engine.replaceRenderHost(snapshot.host, replacement)) {
            replacementRenderer?.release()
            Log.e(TAG, "Unable to switch the $label renderer backend")
            return
        }
        val selection = VulkanSupport.publishActiveSelection(
            context = appContext,
            effectId = effectId,
            resolution = resolution,
            activeVulkanApiVersion = null
        )
        val previous = synchronized(lock) {
            if (closed) {
                releaseSelection(selection)
                return
            }
            val result = openGlRenderer to runtimeSession
            openGlRenderer = replacementRenderer
            vulkanHost = replacementVulkan
            activeHost = replacement
            backendPreference = selection.preference
            activeVulkanApiVersion = null
            runtimeSession = selection.runtimeSession
            result
        }
        previous.first?.release()
        publishRendererStatus("releasing the previous renderer session", previous.second) {
            RendererRuntimeStatusRepository.recordReleased(appContext, it)
        }
        snapshot.engine.requestRender()
    }

    private fun refreshActiveSession(snapshot: BackendSnapshot, resolution: VulkanBackendResolution) {
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

    private fun onVulkanActive(host: WallpaperRenderHost, packedVersion: Int) {
        val isCurrentHost = synchronized(lock) {
            (!closed && activeHost === host).also { isCurrent ->
                if (isCurrent) activeVulkanApiVersion = packedVersion
            }
        }
        if (!isCurrentHost) return
        publishRendererStatus("marking Vulkan as active") {
            RendererRuntimeStatusRepository.recordVulkanActive(context = appContext, session = it, packedVersion = packedVersion)
        }
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
            .onFailure { failure -> Log.w(TAG, "Unable to update renderer status while $operation", failure) }
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
        const val TAG = "EffectBackends"
    }
}
