package com.app.nosatmosphereeffect.renderer

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.ClockFramePump
import com.app.nosatmosphereeffect.helper.ClockOverlayState
import com.app.nosatmosphereeffect.helper.ClockPalette
import com.app.nosatmosphereeffect.helper.ClockScreen
import com.app.nosatmosphereeffect.helper.ClockScreenPolicy
import com.app.nosatmosphereeffect.helper.GLWallpaperService
import com.app.nosatmosphereeffect.helper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.backend.BackendReselectableRenderer
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackendPreference
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeSession
import com.app.nosatmosphereeffect.renderer.status.RendererRuntimeStatusRepository
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanAtmosphereHost
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendChange
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendResolution
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanBackendSelection
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanSupport
import java.util.concurrent.Executors

class AtmosphereRenderController(
    context: Context,
    private val reverse: Boolean
) : BackendReselectableRenderer {
    private val appContext = context.applicationContext
    private val effectId = if (reverse) "REVERSE" else "ORIGINAL"
    private val lock = Any()

    /**
     * Drives clock frames. One per controller, i.e. one per wallpaper engine
     * — see ClockFramePump for why this must not be service-wide.
     */
    private val clockPump = ClockFramePump(appContext) { onClockTick() }
    private val clockColorWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AtmoClockColor").apply { isDaemon = true }
    }
    @Volatile private var requestedClockColor: Int = AtmosphereClockPolicy.DEFAULT_COLOR
    @Volatile private var resolvedAutoClockColor: Int? = null

    private var state = AtmosphereRenderState()
    private var engine: GLWallpaperService.GLEngine? = null
    private var activeHost: WallpaperRenderHost? = null
    private var openGlAtmosphere: AtmosphereRenderer? = null
    private var openGlReverse: BlurToSharpRenderer? = null
    private var vulkanHost: VulkanAtmosphereHost? = null
    private var configuredGlassEnabled = false
    private var configuredGlassBackgroundOnly = false
    private var backendPreference = GraphicsBackendPreference.AUTOMATIC
    private var activeVulkanApiVersion: Int? = null
    private var runtimeSession: RendererRuntimeSession? = null
    private var closed = false

    fun attach(engine: GLWallpaperService.GLEngine) {
        synchronized(lock) {
            check(this.engine == null) {
                "The Atmosphere renderer is already attached"
            }
            check(!closed) { "The Atmosphere renderer has been released" }
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

    fun configure(
        dimLevel: Float,
        saturation: Float,
        contrast: Float,
        noiseEnabled: Boolean,
        noiseScale: Float,
        noiseStrength: Float,
        glassEnabled: Boolean,
        glassLineCount: Int,
        glassLineThickness: Float,
        glassBackgroundOnly: Boolean
    ) {
        val snapshot = synchronized(lock) {
            configuredGlassEnabled = glassEnabled
            configuredGlassBackgroundOnly = glassBackgroundOnly
            state = state.copy(
                dimLevel = dimLevel,
                saturation = saturation,
                contrast = contrast,
                noiseEnabled = noiseEnabled,
                noiseScale = noiseScale,
                noiseStrength = noiseStrength,
                glassEnabled = glassEnabled,
                glassLineCount = glassLineCount,
                glassLineThickness = glassLineThickness,
                glassBackgroundOnly = glassBackgroundOnly
            ).sanitized()
            state
        }
        applyState(snapshot)
    }

    /**
     * The clock, configured separately from the effect's own settings.
     *
     * Split out when the clock stopped being Atmosphere-only: every other
     * effect service now hands its controller the same [ClockOverlayState],
     * and having one shape of call across all of them is worth more than
     * keeping this controller's twelve extra parameters. The state is unpacked
     * into the flat fields the renderers and the Vulkan host already read,
     * rather than those being rewritten to match.
     */
    fun configureClock(clock: ClockOverlayState) {
        val safe = clock.sanitized()
        requestedClockColor = safe.requestedColor
        val resolvedClock =
            AtmosphereClockPolicy.resolveEnabled(effectId, safe.enabled)
        // Collapsed here, once, rather than in each renderer: an effect that
        // is only sharp on one side gets that side regardless of what is
        // stored, so a preference carried over from another effect cannot put
        // the clock somewhere it would be illegible.
        val resolvedScreen = ClockScreenPolicy.resolveScreen(
            effectId,
            ClockScreen.fromId(safe.screenId)
        )
        val snapshot = synchronized(lock) {
            state = state.copy(
                clockEnabled = resolvedClock,
                clockDepthEnabled = safe.depthEnabled,
                clockStyleId = safe.styleId,
                clockShowSeconds = safe.showSeconds,
                clockAnimate = safe.animate,
                clockCenterX = safe.centerX,
                clockTop = safe.top,
                clockHeight = safe.height,
                clockWidthScale = safe.widthScale,
                clockHeightScale = safe.heightScale,
                clockOpacity = safe.opacity,
                clockColor = ClockPalette.resolve(
                    requestedClockColor,
                    resolvedAutoClockColor
                ),
                clockHourFormat = safe.hourFormat,
                clockScreenId = resolvedScreen.id,
                clockLockedProgress = safe.lockedProgress,
                clockUnlockedProgress = safe.unlockedProgress
            ).sanitized()
            state
        }
        applyState(snapshot)
        clockPump.configure(resolvedClock, safe.showSeconds)
        if (resolvedClock && ClockPalette.isAuto(requestedClockColor)) {
            refreshAutoClockColor()
        }
    }

    /**
     * Forwarded from the wallpaper engine so the pump idles when hidden, and
     * so the clock plays its entry animation when the wallpaper comes back.
     *
     * Both backends hold the request until the clock would actually be on
     * screen, so becoming visible on the wrong side of the transition does
     * not spend the animation invisibly.
     */
    fun setEngineVisible(visible: Boolean) {
        clockPump.setVisible(visible)
        if (!visible) return
        val targets = synchronized(lock) {
            Triple(openGlAtmosphere, openGlReverse, vulkanHost)
        }
        targets.first?.beginClockEntry()
        targets.second?.beginClockEntry()
        targets.third?.beginClockEntry()
    }

    /**
     * Re-derives the wallpaper-tinted clock colour off the main thread, then
     * pushes it if it actually changed. Extraction decodes and runs Palette,
     * so it must not happen on the render or main thread.
     */
    private fun refreshAutoClockColor() {
        val submitted = runCatching {
            clockColorWorker.execute {
                val derived = ClockPalette.autoColorFor(appContext) ?: return@execute
                if (derived == resolvedAutoClockColor) return@execute
                resolvedAutoClockColor = derived
                if (!ClockPalette.isAuto(requestedClockColor)) return@execute
                val snapshot = synchronized(lock) {
                    if (closed) return@execute
                    state = state.copy(clockColor = derived).sanitized()
                    state
                }
                applyState(snapshot)
                synchronized(lock) { engine }?.requestRender()
            }
        }
        if (submitted.isFailure) {
            Log.w(TAG, "Could not schedule clock colour extraction")
        }
    }

    private fun onClockTick() {
        val targets = synchronized(lock) {
            Triple(openGlAtmosphere, openGlReverse, vulkanHost)
        }
        targets.first?.onTimeChanged()
        targets.second?.onClockTimeChanged()
        targets.third?.onTimeChanged()
        synchronized(lock) { engine }?.requestRender()
    }

    fun setProgress(progress: Float) {
        val snapshot = synchronized(lock) {
            state = state.copy(progress = progress).sanitized()
            state
        }
        applyState(snapshot)
    }

    fun setFixedEffectApplied(effectApplied: Boolean) {
        val snapshot = synchronized(lock) {
            state = state.copy(
                glassEnabled = effectApplied && configuredGlassEnabled,
                glassBackgroundOnly =
                    effectApplied &&
                    configuredGlassEnabled &&
                    configuredGlassBackgroundOnly
            ).sanitized()
            state
        }
        applyState(snapshot)
    }

    fun setDrawerBlurred(blurred: Boolean) {
        val snapshot = synchronized(lock) {
            state = state.copy(drawerBlur = if (blurred) 1f else 0f)
                .sanitized()
            state
        }
        applyState(snapshot)
    }

    internal fun currentStateForTesting(): AtmosphereRenderState {
        return synchronized(lock) { state }
    }

    fun reloadTexture() {
        // The image is changing, so any wallpaper-derived clock tint is stale.
        ClockPalette.invalidateAutoColor()
        if (ClockPalette.isAuto(requestedClockColor)) refreshAutoClockColor()
        val targets = synchronized(lock) {
            Triple(openGlAtmosphere, openGlReverse, vulkanHost)
        }
        targets.first?.reloadTexture()
        targets.second?.reloadTexture()
        targets.third?.reloadTexture()
    }

    fun queuePlaylistTransition(bitmap: Bitmap) {
        val targets = synchronized(lock) {
            RenderTargets(
                atmosphere = openGlAtmosphere,
                reverse = openGlReverse,
                vulkan = vulkanHost,
                closed = closed
            )
        }
        when {
            targets.closed -> bitmap.recycleSafely()
            targets.vulkan != null -> targets.vulkan.queuePlaylistTransition(bitmap)
            targets.atmosphere != null ->
                targets.atmosphere.queuePlaylistTransition(bitmap)
            targets.reverse != null -> targets.reverse.queuePlaylistTransition(bitmap)
            else -> bitmap.recycleSafely()
        }
    }

    fun release() {
        clockPump.close()
        clockColorWorker.shutdownNow()
        val targets: RenderTargets
        val session: RendererRuntimeSession?
        synchronized(lock) {
            if (closed) return
            closed = true
            targets = RenderTargets(
                atmosphere = openGlAtmosphere,
                reverse = openGlReverse,
                vulkan = vulkanHost,
                closed = true
            )
            session = runtimeSession
            openGlAtmosphere = null
            openGlReverse = null
            vulkanHost = null
            activeHost = null
            engine = null
            activeVulkanApiVersion = null
            runtimeSession = null
        }
        targets.atmosphere?.release()
        targets.reverse?.release()
        targets.vulkan?.close()
        publishStatus(session) {
            RendererRuntimeStatusRepository.recordReleased(appContext, it)
        }
    }

    private fun attachVulkan(engine: GLWallpaperService.GLEngine) {
        publishStatus {
            RendererRuntimeStatusRepository.recordVulkanInitializing(
                appContext,
                it
            )
        }
        val host = VulkanAtmosphereHost(
            context = appContext,
            reverse = reverse,
            initialState = synchronized(lock) { state },
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
        val renderer = createOpenGlRenderer(engine)
        val host = when (renderer) {
            is AtmosphereRenderer -> engine.setRenderer(renderer)
            is BlurToSharpRenderer -> engine.setRenderer(renderer)
            else -> error("Unexpected Atmosphere renderer")
        }
        synchronized(lock) {
            if (closed) {
                releaseOpenGl(renderer)
                host.close()
                return
            }
            storeOpenGl(renderer)
            activeHost = host
        }
        publishStatus {
            RendererRuntimeStatusRepository.recordOpenGlActive(appContext, it)
        }
    }

    private fun fallbackToOpenGl(
        failedHost: VulkanAtmosphereHost,
        reason: String
    ) {
        val currentEngine = synchronized(lock) {
            if (closed || activeHost !== failedHost) return
            engine ?: return
        }
        runCatching { VulkanSupport.recordFailure(appContext, effectId, reason) }
            .onFailure { failure ->
                Log.w(TAG, "Unable to persist the Vulkan fallback state", failure)
            }
        val fallback = runCatching {
            val renderer = createOpenGlRenderer(currentEngine)
            val replacement = when (renderer) {
                is AtmosphereRenderer ->
                    currentEngine.createOpenGlRenderHost(renderer)
                is BlurToSharpRenderer ->
                    currentEngine.createOpenGlRenderHost(renderer)
                else -> error("Unexpected Atmosphere renderer")
            }
            renderer to replacement
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to create the OpenGL ES Atmosphere fallback", failure)
            return
        }
        val (renderer, replacement) = fallback
        if (!currentEngine.replaceRenderHost(failedHost, replacement)) {
            releaseOpenGl(renderer)
            Log.e(TAG, "Unable to attach the OpenGL ES Atmosphere fallback")
            return
        }
        synchronized(lock) {
            if (closed) {
                releaseOpenGl(renderer)
                return
            }
            storeOpenGl(renderer)
            vulkanHost = null
            activeHost = replacement
            activeVulkanApiVersion = null
        }
        publishStatus {
            RendererRuntimeStatusRepository.recordOpenGlActive(
                context = appContext,
                session = it,
                reason = reason
            )
        }
        reloadTexture()
        currentEngine.requestRender()
        Log.w(TAG, "Atmosphere switched to OpenGL ES after Vulkan failed: $reason")
    }

    private fun swapBackend(
        snapshot: BackendSnapshot,
        change: VulkanBackendChange.Swap
    ) {
        val resolution = change.resolution
        var replacementRenderer: Any? = null
        var replacementVulkan: VulkanAtmosphereHost? = null
        val replacement = runCatching {
            when (resolution.backend) {
                GraphicsBackend.VULKAN -> {
                    VulkanAtmosphereHost(
                        context = appContext,
                        reverse = reverse,
                        initialState = synchronized(lock) { state },
                        onFatalFailure = ::fallbackToOpenGl,
                        onVulkanActive = ::onVulkanActive
                    ).also { replacementVulkan = it }
                }
                GraphicsBackend.OPENGL_ES -> {
                    createOpenGlRenderer(snapshot.engine).also { renderer ->
                        replacementRenderer = renderer
                    }.let { renderer ->
                        when (renderer) {
                            is AtmosphereRenderer ->
                                snapshot.engine.createOpenGlRenderHost(renderer)
                            is BlurToSharpRenderer ->
                                snapshot.engine.createOpenGlRenderHost(renderer)
                            else -> error("Unexpected Atmosphere renderer")
                        }
                    }
                }
            }
        }.getOrElse { failure ->
            Log.e(TAG, "Unable to prepare the requested Atmosphere renderer", failure)
            replacementRenderer?.let(::releaseOpenGl)
            return
        }
        if (!snapshot.engine.replaceRenderHost(snapshot.host, replacement)) {
            replacementRenderer?.let(::releaseOpenGl)
            Log.e(TAG, "Unable to switch the Atmosphere renderer backend")
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
            val previousRenderer: Any? = openGlAtmosphere ?: openGlReverse
            val result = previousRenderer to runtimeSession
            openGlAtmosphere = null
            openGlReverse = null
            replacementRenderer?.let(::storeOpenGl)
            vulkanHost = replacementVulkan
            activeHost = replacement
            backendPreference = selection.preference
            activeVulkanApiVersion = null
            runtimeSession = selection.runtimeSession
            result
        }
        previous.first?.let(::releaseOpenGl)
        publishStatus(previous.second) {
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
        publishStatus(previousSession) {
            RendererRuntimeStatusRepository.recordReleased(appContext, it)
        }
    }

    private fun onVulkanActive(
        host: VulkanAtmosphereHost,
        packedVersion: Int
    ) {
        if (synchronized(lock) {
                (!closed && activeHost === host).also { isCurrent ->
                    if (isCurrent) activeVulkanApiVersion = packedVersion
                }
            }
        ) {
            publishStatus {
                RendererRuntimeStatusRepository.recordVulkanActive(
                    context = appContext,
                    session = it,
                    packedVersion = packedVersion
                )
            }
        }
    }

    private fun createOpenGlRenderer(
        engine: GLWallpaperService.GLEngine
    ): Any {
        val snapshot = synchronized(lock) { state }
        return if (reverse) {
            BlurToSharpRenderer(appContext).apply {
                applyState(snapshot)
                onRenderRetryRequested = engine::requestRender
                onSubjectMaskUpdated = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }
        } else {
            AtmosphereRenderer(appContext).apply {
                applyState(snapshot)
                onRenderRetryRequested = engine::requestRender
                onSubjectMaskUpdated = engine::requestRender
                onAnimationFrameRequested = engine::requestRender
            }
        }
    }

    /** Kept for callers outside the pump (config updates, wallpaper swaps). */
    fun onSystemTimeChanged() = onClockTick()

    private fun applyState(snapshot: AtmosphereRenderState) {
        val targets = synchronized(lock) {
            Triple(openGlAtmosphere, openGlReverse, vulkanHost)
        }
        targets.first?.applyState(snapshot)
        targets.second?.applyState(snapshot)
        targets.third?.updateState(snapshot)
    }

    private fun AtmosphereRenderer.applyState(state: AtmosphereRenderState) {
        blurStrength = state.progress
        dimLevel = state.dimLevel
        enableNoise = state.noiseEnabled
        noiseScale = state.noiseScale
        noiseStrength = state.noiseStrength
        blobSaturation = state.saturation
        blobContrast = state.contrast
        atmosphereGlassEnabled = state.glassEnabled
        glassLineCount = state.glassLineCount
        glassLineThickness = state.glassLineThickness
        glassBackgroundOnly = state.glassBackgroundOnly
        configureSubjectIsolation(state.needsSubjectMask())
        clockEnabled = state.clockEnabled
        clockDepthEnabled = state.clockDepthEnabled
        clockStyle = state.clockStyle
        clockShowSeconds = state.clockShowSeconds
        clockAnimate = state.clockAnimate
        clockColor = state.clockColor
        clockHourFormat = state.clockHourFormat
        clockCenterX = state.clockCenterX
        clockTop = state.clockTop
        clockHeight = state.clockHeight
        clockOpacity = state.clockOpacity
        clockScreen = state.clockScreen
        clockLockedProgress = state.clockLockedProgress
        clockUnlockedProgress = state.clockUnlockedProgress
    }

    private fun BlurToSharpRenderer.applyState(state: AtmosphereRenderState) {
        blurStrength = state.progress
        dimLevel = state.dimLevel
        enableNoise = state.noiseEnabled
        noiseScale = state.noiseScale
        noiseStrength = state.noiseStrength
        blobSaturation = state.saturation
        blobContrast = state.contrast
        atmosphereGlassEnabled = state.glassEnabled
        glassLineCount = state.glassLineCount
        glassLineThickness = state.glassLineThickness
        configureGlassBackgroundOnly(state.glassBackgroundOnly)
        // Reverse Atmosphere gets the clock through the shared overlay rather
        // than the flat fields the forward renderer uses.
        applyClockState(state.clockOverlay())
        setDrawerBlurred(state.drawerBlur > 0.5f)
    }

    private fun storeOpenGl(renderer: Any) {
        when (renderer) {
            is AtmosphereRenderer -> openGlAtmosphere = renderer
            is BlurToSharpRenderer -> openGlReverse = renderer
        }
    }

    private fun releaseOpenGl(renderer: Any) {
        when (renderer) {
            is AtmosphereRenderer -> renderer.release()
            is BlurToSharpRenderer -> renderer.release()
        }
    }

    private inline fun publishStatus(
        session: RendererRuntimeSession? = synchronized(lock) { runtimeSession },
        block: (RendererRuntimeSession) -> Unit
    ) {
        if (session == null) return
        runCatching { block(session) }.onFailure { failure ->
            Log.w(TAG, "Unable to publish the Atmosphere renderer status", failure)
        }
    }

    private fun releaseSelection(selection: VulkanBackendSelection) {
        publishStatus(selection.runtimeSession) {
            RendererRuntimeStatusRepository.recordReleased(appContext, it)
        }
    }

    private fun Bitmap.recycleSafely() {
        if (!isRecycled) recycle()
    }

    private data class RenderTargets(
        val atmosphere: AtmosphereRenderer?,
        val reverse: BlurToSharpRenderer?,
        val vulkan: VulkanAtmosphereHost?,
        val closed: Boolean
    )

    private data class BackendSnapshot(
        val engine: GLWallpaperService.GLEngine,
        val host: WallpaperRenderHost,
        val preference: GraphicsBackendPreference,
        val backend: GraphicsBackend
    )

    private companion object {
        const val TAG = "AtmosphereRenderController"
    }
}
