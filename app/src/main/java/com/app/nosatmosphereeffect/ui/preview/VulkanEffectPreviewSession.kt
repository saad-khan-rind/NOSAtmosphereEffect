package com.app.nosatmosphereeffect.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import com.app.nosatmosphereeffect.helper.WallpaperRenderHost
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanAtmosphereHost
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanColorFillHost
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanFrostedHost
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanGlassHost
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanHalftoneHost
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanNeonHost
import java.util.concurrent.atomic.AtomicBoolean

internal class VulkanEffectPreviewSession(
    context: Context,
    effectId: String,
    initialState: EffectPreviewRenderState,
    previewSource: () -> Bitmap?,
    cornerRadiusPx: Float,
    private val onFatalFailure: (VulkanEffectPreviewSession, String) -> Unit
) {
    private val failed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val host = createHost(
        context = context.applicationContext,
        effectId = effectId,
        state = initialState,
        previewSource = previewSource
    )

    private val surfaceView = VulkanPreviewSurfaceView(
        context = context,
        radius = cornerRadiusPx,
        host = host,
        onBridgeFailure = ::reportBridgeFailure
    )
    val view: View
        get() = surfaceView

    init {
        host.onPause()
    }

    fun updateState(state: EffectPreviewRenderState) {
        when {
            host is VulkanAtmosphereHost && state is EffectPreviewRenderState.Atmosphere ->
                host.updateState(state.value)
            host is VulkanFrostedHost && state is EffectPreviewRenderState.Frosted ->
                host.updateState(state.value)
            host is VulkanGlassHost && state is EffectPreviewRenderState.Glass ->
                host.updateState(state.value)
            host is VulkanHalftoneHost && state is EffectPreviewRenderState.Halftone ->
                host.updateState(state.value)
            host is VulkanColorFillHost && state is EffectPreviewRenderState.ColorFill ->
                host.updateState(state.value)
            host is VulkanNeonHost && state is EffectPreviewRenderState.Neon ->
                host.updateState(state.value)
            else -> reportBridgeFailure("The Vulkan preview received incompatible effect state")
        }
    }

    /**
     * Replays the clock's entry animation. Dispatched by host type for the
     * same reason [updateState] is: the hosts share no common clock
     * interface, and inventing one would put a decorative overlay into the
     * render-host contract every backend has to implement.
     */
    fun beginClockEntry() {
        if (closed.get()) return
        when (val target = host) {
            is VulkanAtmosphereHost -> target.beginClockEntry()
            is VulkanFrostedHost -> target.beginClockEntry()
            is VulkanGlassHost -> target.beginClockEntry()
            is VulkanHalftoneHost -> target.beginClockEntry()
            is VulkanColorFillHost -> target.beginClockEntry()
            is VulkanNeonHost -> target.beginClockEntry()
            else -> Unit
        }
    }

    fun resume() {
        if (!closed.get()) surfaceView.resumeRendering()
    }

    fun pause() {
        if (!closed.get()) surfaceView.pauseRendering()
    }

    fun requestRender() {
        if (!closed.get()) host.requestRender()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        surfaceView.close()
    }

    private fun createHost(
        context: Context,
        effectId: String,
        state: EffectPreviewRenderState,
        previewSource: () -> Bitmap?
    ): WallpaperRenderHost {
        return when (state) {
            is EffectPreviewRenderState.Atmosphere -> VulkanAtmosphereHost(
                context = context,
                reverse = effectId == "REVERSE",
                initialState = state.value,
                onFatalFailure = { _, reason -> reportHostFailure(reason) },
                onVulkanActive = { _, _ -> },
                previewSource = previewSource
            )
            is EffectPreviewRenderState.Frosted -> VulkanFrostedHost(
                context = context,
                initialState = state.value,
                onFatalFailure = { _, reason -> reportHostFailure(reason) },
                onVulkanActive = { _, _ -> },
                previewSource = previewSource
            )
            is EffectPreviewRenderState.Glass -> VulkanGlassHost(
                context = context,
                initialState = state.value,
                onFatalFailure = { _, reason -> reportHostFailure(reason) },
                onVulkanActive = { _, _ -> },
                previewSource = previewSource
            )
            is EffectPreviewRenderState.Halftone -> VulkanHalftoneHost(
                context = context,
                reverse = effectId == "HALFTONE_REVERSE",
                initialState = state.value,
                onFatalFailure = { _, reason -> reportHostFailure(reason) },
                onVulkanActive = { _, _ -> },
                previewSource = previewSource
            )
            is EffectPreviewRenderState.ColorFill -> VulkanColorFillHost(
                context = context,
                reverse = effectId == "COLORFILL_REVERSE",
                initialState = state.value,
                onFatalFailure = { _, reason -> reportHostFailure(reason) },
                onVulkanActive = { _, _ -> },
                previewSource = previewSource
            )
            is EffectPreviewRenderState.Neon -> VulkanNeonHost(
                context = context,
                reverse = effectId == "NEON_REVERSE",
                initialState = state.value,
                onFatalFailure = { _, reason -> reportHostFailure(reason) },
                onVulkanActive = { _, _ -> },
                previewSource = previewSource
            )
        }
    }

    private fun reportHostFailure(reason: String) {
        reportFailure(reason)
    }

    private fun reportBridgeFailure(reason: String) {
        reportFailure(reason)
    }

    private fun reportFailure(reason: String) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        surfaceView.post {
            if (!closed.get()) onFatalFailure(this, reason)
        }
    }
}

private class VulkanPreviewSurfaceView(
    context: Context,
    radius: Float,
    private val host: WallpaperRenderHost,
    private val onBridgeFailure: (String) -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {
    private val closed = AtomicBoolean(false)
    private var resumed = false

    init {
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
        clipToOutline = true
        outlineProvider = RoundedOutlineProvider(radius)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        forward("create") { host.onSurfaceCreated(holder) }
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        forward("resize") {
            host.onSurfaceChanged(holder, format, width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        forward("destroy") { host.onSurfaceDestroyed(holder) }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidateOutline()
    }

    fun resumeRendering() {
        if (closed.get() || resumed) return
        resumed = true
        forward("resume") {
            host.onResume()
            host.requestRender()
        }
    }

    fun pauseRendering() {
        if (closed.get() || !resumed) return
        resumed = false
        forward("pause") { host.onPause() }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        holder.removeCallback(this)
        if (resumed) {
            resumed = false
            runCatching { host.onPause() }
        }
        host.close()
    }

    private inline fun forward(operation: String, action: () -> Unit) {
        if (closed.get()) return
        runCatching(action).onFailure {
            onBridgeFailure("The Vulkan preview surface could not $operation")
        }
    }
}

internal class RoundedOutlineProvider(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}
