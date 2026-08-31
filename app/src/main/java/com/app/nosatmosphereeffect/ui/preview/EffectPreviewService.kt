package com.app.nosatmosphereeffect.ui.preview

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.helper.CanvasSubjectSettings
import com.app.nosatmosphereeffect.helper.EffectStatePolicy
import com.app.nosatmosphereeffect.helper.GlassEffectPreferences
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy
import com.app.nosatmosphereeffect.helper.SubjectIsolationPolicy
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderer
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderState
import com.app.nosatmosphereeffect.renderer.BlurToSharpRenderer
import com.app.nosatmosphereeffect.renderer.ColorFillRenderState
import com.app.nosatmosphereeffect.renderer.ColorFillRenderer
import com.app.nosatmosphereeffect.renderer.FrostedRenderState
import com.app.nosatmosphereeffect.renderer.FrostedRenderer
import com.app.nosatmosphereeffect.renderer.GlassRenderState
import com.app.nosatmosphereeffect.renderer.GlassRenderer
import com.app.nosatmosphereeffect.renderer.HalftoneRenderState
import com.app.nosatmosphereeffect.renderer.HalftoneRenderer
import com.app.nosatmosphereeffect.renderer.NeonRenderState
import com.app.nosatmosphereeffect.renderer.NeonRenderer
import com.app.nosatmosphereeffect.renderer.backend.GraphicsBackend
import com.app.nosatmosphereeffect.renderer.vulkan.VulkanSupport
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class EffectPreviewSettingsMode {
    SAVED_ACTIVE,
    EFFECT_DEFAULTS
}

class EffectPreviewService(
    context: Context,
    private val effectId: String,
    source: Bitmap?,
    cornerRadiusPx: Float,
    private val settingsMode: EffectPreviewSettingsMode =
        EffectPreviewSettingsMode.SAVED_ACTIVE,
    private val atmosphereGlassEnabledOverride: Boolean? = null,
    private val forceOpenGlEs: Boolean = false
) {
    private val appContext = context.applicationContext
    private val released = AtomicBoolean(false)
    private val sourceLock = Any()
    private val sourceBitmap = source?.copy(Bitmap.Config.ARGB_8888, false)
        ?: createDemoWallpaper()
    private val sourceProvider: () -> Bitmap? = {
        synchronized(sourceLock) {
            if (released.get() || sourceBitmap.isRecycled) {
                null
            } else {
                sourceBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
        }
    }
    private var configuredAtmosphereGlassEnabled = false
    private var configuredAtmosphereGlassBackgroundOnly = false
    private val renderState = AtomicReference(createInitialState())
    private val previewContainer = PreviewContainerView(context, cornerRadiusPx)

    private var activeBackend = GraphicsBackend.OPENGL_ES
    private var openGlSurface: PreviewSurfaceView? = null
    private var openGlRenderer: GLSurfaceView.Renderer? = null
    private var vulkanSession: VulkanEffectPreviewSession? = null
    private var resumed = false

    val view: View
        get() = previewContainer

    init {
        val initialProgress = EffectStatePolicy.transitionProgress(effectId, 0f)
        renderState.updateAndGet {
            EffectPreviewStatePolicy.withProgress(
                state = it,
                progress = initialProgress,
                atmosphereGlassEnabled = configuredAtmosphereGlassEnabled,
                atmosphereGlassBackgroundOnly =
                    configuredAtmosphereGlassBackgroundOnly
            )
        }
        val selectedBackend = if (forceOpenGlEs) {
            GraphicsBackend.OPENGL_ES
        } else {
            runCatching {
                VulkanSupport.selectPreviewBackend(appContext, effectId)
            }.getOrElse { failure ->
                Log.w(TAG, "Unable to select the preview graphics backend", failure)
                GraphicsBackend.OPENGL_ES
            }
        }
        if (selectedBackend == GraphicsBackend.VULKAN) {
            attachVulkan()
        } else {
            attachOpenGl()
        }
    }

    private fun requestRenderRetry() {
        if (!released.get()) {
            previewContainer.postDelayed(
                { if (!released.get()) requestActiveRender() },
                RENDER_RETRY_DELAY_MS
            )
        }
    }

    fun setProgress(lockToHomeProgress: Float) {
        if (released.get()) return
        setRendererProgress(
            rendererProgress = EffectStatePolicy.transitionProgress(
                effectId,
                lockToHomeProgress
            ),
            atmosphereGlassEnabled = configuredAtmosphereGlassEnabled
        )
    }

    fun setAppliedState(effectApplied: Boolean) {
        if (released.get()) return
        val endpoints = EffectStatePolicy.endpoints(effectId)
        setRendererProgress(
            rendererProgress = if (effectApplied) {
                endpoints.appliedProgress
            } else {
                endpoints.originalProgress
            },
            atmosphereGlassEnabled = configuredAtmosphereGlassEnabled && effectApplied
        )
    }

    /**
     * Pushes clock geometry straight to the live GLES renderer, bypassing
     * the state/prefs machinery entirely — for interactive dragging in
     * ClockAdjustActivity, where recreating the whole preview per pointer
     * event would be too janky. Call [setRendererProgress]'s normal path
     * (or just rely on the next full state rebuild) for anything else.
     */
    fun setAtmosphereClockGeometry(centerX: Float, top: Float, height: Float) {
        if (released.get() || activeBackend != GraphicsBackend.OPENGL_ES) return
        val surface = openGlSurface
        val renderer = openGlRenderer
        if (surface != null && renderer is AtmosphereRenderer) {
            surface.queueEvent {
                if (!released.get() && openGlRenderer === renderer) {
                    renderer.clockCenterX = centerX
                    renderer.clockTop = top
                    renderer.clockHeight = height
                }
            }
            requestActiveRender()
        }
    }

    private fun setRendererProgress(
        rendererProgress: Float,
        atmosphereGlassEnabled: Boolean
    ) {
        val snapshot = renderState.updateAndGet {
            EffectPreviewStatePolicy.withProgress(
                state = it,
                progress = rendererProgress,
                atmosphereGlassEnabled = atmosphereGlassEnabled,
                atmosphereGlassBackgroundOnly =
                    configuredAtmosphereGlassBackgroundOnly
            )
        }
        when (activeBackend) {
            GraphicsBackend.VULKAN -> vulkanSession?.updateState(snapshot)
            GraphicsBackend.OPENGL_ES -> {
                val surface = openGlSurface
                val renderer = openGlRenderer
                val frameUpdate =
                    EffectPreviewStatePolicy.openGlFrameUpdate(snapshot)
                if (surface != null && renderer != null) {
                    surface.queueEvent {
                        if (!released.get() && openGlRenderer === renderer) {
                            applyFrameToOpenGl(renderer, frameUpdate)
                        }
                    }
                }
            }
        }
        requestActiveRender()
    }

    fun resume() {
        if (released.get() || resumed) return
        resumed = true
        when (activeBackend) {
            GraphicsBackend.VULKAN -> vulkanSession?.resume()
            GraphicsBackend.OPENGL_ES -> openGlSurface?.onResume()
        }
        requestActiveRender()
    }

    fun pause() {
        if (released.get() || !resumed) return
        resumed = false
        when (activeBackend) {
            GraphicsBackend.VULKAN -> vulkanSession?.pause()
            GraphicsBackend.OPENGL_ES -> openGlSurface?.onPause()
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        val wasResumed = resumed
        resumed = false
        vulkanSession?.close()
        vulkanSession = null
        releaseOpenGl(pauseFirst = wasResumed)
        previewContainer.removeAllViews()
        synchronized(sourceLock) {
            if (!sourceBitmap.isRecycled) sourceBitmap.recycle()
        }
    }

    private fun createInitialState(): EffectPreviewRenderState {
        val prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return when (effectId) {
            "ORIGINAL", "REVERSE" -> {
                val glassEnabled = previewAtmosphereGlassEnabled(prefs)
                configuredAtmosphereGlassEnabled = glassEnabled
                val glassSettings = previewGlassSettings(prefs)
                configuredAtmosphereGlassBackgroundOnly =
                    glassEnabled && glassSettings.backgroundOnly
                val clockEnabled = AtmosphereClockPolicy.resolveEnabled(
                    effectId,
                    previewBoolean(prefs, AtmosphereClockPolicy.ENABLED_KEY, false)
                )
                EffectPreviewRenderState.Atmosphere(
                    AtmosphereRenderState(
                        dimLevel = previewFloat(prefs, "dim_level", 0.2f),
                        noiseEnabled = previewBoolean(prefs, "enable_noise", false),
                        noiseScale = previewFloat(prefs, "noise_scale", 2000f),
                        noiseStrength = previewFloat(prefs, "noise_strength", 0.06f),
                        saturation = previewFloat(prefs, "blob_saturation", 1f),
                        contrast = previewFloat(prefs, "blob_contrast", 1f),
                        glassEnabled = glassEnabled,
                        glassLineCount = glassSettings.lineCount,
                        glassLineThickness = glassSettings.lineThickness,
                        glassBackgroundOnly =
                            configuredAtmosphereGlassBackgroundOnly,
                        clockEnabled = clockEnabled,
                        clockCenterX = previewFloat(
                            prefs,
                            AtmosphereClockPolicy.CENTER_X_KEY,
                            AtmosphereClockPolicy.DEFAULT_CENTER_X
                        ),
                        clockTop = previewFloat(
                            prefs,
                            AtmosphereClockPolicy.TOP_KEY,
                            AtmosphereClockPolicy.DEFAULT_TOP
                        ),
                        clockHeight = previewFloat(
                            prefs,
                            AtmosphereClockPolicy.HEIGHT_KEY,
                            AtmosphereClockPolicy.DEFAULT_HEIGHT
                        ),
                        clockOpacity = previewFloat(
                            prefs,
                            AtmosphereClockPolicy.OPACITY_KEY,
                            AtmosphereClockPolicy.DEFAULT_OPACITY
                        )
                    ).sanitized()
                )
            }

            "FROSTED", "FROSTED_REVERSE" -> EffectPreviewRenderState.Frosted(
                FrostedRenderState(
                    dimLevel = previewFloat(prefs, "dim_level", 0.2f),
                    enableNoise = previewBoolean(prefs, "enable_noise", false),
                    noiseScale = previewFloat(prefs, "noise_scale", 2000f),
                    noiseStrength = previewFloat(prefs, "noise_strength", 0.06f),
                    blurRadius = previewFloat(prefs, "frosted_blur_radius", 200f)
                ).sanitized()
            )

            "GLASS", "GLASS_REVERSE" -> {
                val glassSettings = previewGlassSettings(prefs)
                EffectPreviewRenderState.Glass(
                    GlassRenderState(
                        dimLevel = previewFloat(prefs, "dim_level", 0f),
                        lineCount = glassSettings.lineCount,
                        lineThickness = glassSettings.lineThickness,
                        transitionStyle = glassSettings.transitionStyle,
                        backgroundOnly = glassSettings.backgroundOnly
                    ).sanitized()
                )
            }

            "HALFTONE", "HALFTONE_REVERSE" -> EffectPreviewRenderState.Halftone(
                HalftoneRenderState(
                    dimLevel = previewFloat(prefs, "dim_level", 0f),
                    dotSize = previewFloat(prefs, "halftone_dot_size", 12f),
                    grayscale = previewBoolean(prefs, "halftone_grayscale", false),
                    backgroundOnly = previewBoolean(
                        prefs,
                        SubjectIsolationPolicy.HALFTONE_BACKGROUND_ONLY_KEY,
                        false
                    )
                ).sanitized()
            )

            "COLORFILL", "COLORFILL_REVERSE" -> EffectPreviewRenderState.ColorFill(
                ColorFillRenderState(
                    dimLevel = previewFloat(prefs, "dim_level", 0f),
                    originX = previewFloat(prefs, "origin_x", 0.5f),
                    originY = previewFloat(prefs, "origin_y", 0.8f)
                ).sanitized()
            )

            "NEON", "NEON_REVERSE" -> EffectPreviewRenderState.Neon(
                NeonRenderState(
                    dimLevel = previewFloat(prefs, "dim_level", 0f),
                    lineWidth = previewFloat(prefs, "neon_line_width", 1.5f),
                    sensitivity = previewFloat(prefs, "neon_sensitivity", 0.5f),
                    subjectSegmentationEnabled = previewBoolean(
                        prefs,
                        CanvasSubjectSettings.ENABLED_KEY,
                        false
                    )
                ).sanitized()
            )

            else -> EffectPreviewRenderState.Atmosphere(AtmosphereRenderState())
        }
    }

    private fun attachVulkan() {
        val session = runCatching {
            VulkanEffectPreviewSession(
                context = previewContainer.context,
                effectId = effectId,
                initialState = renderState.get(),
                previewSource = sourceProvider,
                cornerRadiusPx = previewContainer.cornerRadius,
                onFatalFailure = ::fallbackToOpenGl
            )
        }.getOrElse { failure ->
            Log.w(TAG, "Unable to create the Vulkan effect preview", failure)
            attachOpenGl()
            return
        }
        activeBackend = GraphicsBackend.VULKAN
        vulkanSession = session
        previewContainer.showSurface(session.view)
        session.updateState(renderState.get())
        if (resumed) session.resume()
        session.requestRender()
        Log.d(TAG, "Using Vulkan for the $effectId in-app preview")
    }

    private fun attachOpenGl() {
        val snapshot = renderState.get()
        val renderer = createOpenGlRenderer(snapshot)
        applyStateToOpenGl(renderer, snapshot)
        wireOpenGlCallbacks(renderer)
        val surface = PreviewSurfaceView(
            context = previewContainer.context,
            radius = previewContainer.cornerRadius
        ).apply {
            setEGLConfigChooser(8, 8, 8, 0, 16, 0)
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            holder.setFormat(PixelFormat.OPAQUE)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        activeBackend = GraphicsBackend.OPENGL_ES
        openGlRenderer = renderer
        openGlSurface = surface
        previewContainer.showSurface(surface)
        if (resumed) {
            surface.onResume()
        } else {
            surface.onPause()
        }
        surface.requestRender()
        Log.d(TAG, "Using OpenGL ES for the $effectId in-app preview")
    }

    private fun fallbackToOpenGl(
        failedSession: VulkanEffectPreviewSession,
        reason: String
    ) {
        if (
            released.get() ||
            activeBackend != GraphicsBackend.VULKAN ||
            vulkanSession !== failedSession
        ) {
            return
        }
        Log.w(TAG, "Vulkan preview fell back to OpenGL ES: $reason")
        failedSession.close()
        vulkanSession = null
        runCatching(::attachOpenGl).onFailure { failure ->
            Log.e(TAG, "Unable to start the OpenGL ES preview fallback", failure)
        }
    }

    private fun createOpenGlRenderer(
        state: EffectPreviewRenderState
    ): GLSurfaceView.Renderer {
        return when (state) {
            is EffectPreviewRenderState.Atmosphere -> {
                if (effectId == "REVERSE") {
                    BlurToSharpRenderer(appContext, sourceProvider)
                } else {
                    AtmosphereRenderer(appContext, sourceProvider)
                }
            }
            is EffectPreviewRenderState.Frosted ->
                FrostedRenderer(appContext, sourceProvider)
            is EffectPreviewRenderState.Glass ->
                GlassRenderer(appContext, sourceProvider)
            is EffectPreviewRenderState.Halftone -> HalftoneRenderer(
                appContext,
                isReverse = effectId == "HALFTONE_REVERSE",
                previewSource = sourceProvider
            )
            is EffectPreviewRenderState.ColorFill -> ColorFillRenderer(
                appContext,
                isReverse = effectId == "COLORFILL_REVERSE",
                previewSource = sourceProvider
            )
            is EffectPreviewRenderState.Neon -> NeonRenderer(
                appContext,
                isReverse = effectId == "NEON_REVERSE",
                previewSource = sourceProvider
            )
        }
    }

    private fun applyFrameToOpenGl(
        renderer: GLSurfaceView.Renderer,
        update: EffectPreviewOpenGlFrameUpdate
    ) {
        when {
            renderer is AtmosphereRenderer &&
                update is EffectPreviewOpenGlFrameUpdate.Atmosphere ->
                renderer.blurStrength = update.progress
            renderer is BlurToSharpRenderer &&
                update is EffectPreviewOpenGlFrameUpdate.Atmosphere ->
                renderer.blurStrength = update.progress
            renderer is FrostedRenderer &&
                update is EffectPreviewOpenGlFrameUpdate.Frosted ->
                renderer.blurStrength = update.progress
            renderer is GlassRenderer &&
                update is EffectPreviewOpenGlFrameUpdate.Glass ->
                renderer.progress = update.progress
            renderer is HalftoneRenderer &&
                update is EffectPreviewOpenGlFrameUpdate.Halftone ->
                renderer.blurStrength = update.progress
            renderer is ColorFillRenderer &&
                update is EffectPreviewOpenGlFrameUpdate.ColorFill ->
                renderer.blurStrength = update.progress
            renderer is NeonRenderer &&
                update is EffectPreviewOpenGlFrameUpdate.Neon ->
                renderer.blurStrength = update.progress
            else -> error("The preview renderer and frame update do not match")
        }
    }

    private fun applyStateToOpenGl(
        renderer: GLSurfaceView.Renderer,
        state: EffectPreviewRenderState
    ) {
        when {
            renderer is AtmosphereRenderer &&
                state is EffectPreviewRenderState.Atmosphere -> {
                val value = state.value
                renderer.blurStrength = value.progress
                renderer.dimLevel = value.dimLevel
                renderer.enableNoise = value.noiseEnabled
                renderer.noiseScale = value.noiseScale
                renderer.noiseStrength = value.noiseStrength
                renderer.blobSaturation = value.saturation
                renderer.blobContrast = value.contrast
                renderer.atmosphereGlassEnabled = value.glassEnabled
                renderer.glassLineCount = value.glassLineCount
                renderer.glassLineThickness = value.glassLineThickness
                renderer.configureGlassBackgroundOnly(value.glassBackgroundOnly)
                renderer.clockEnabled = value.clockEnabled
                renderer.clockCenterX = value.clockCenterX
                renderer.clockTop = value.clockTop
                renderer.clockHeight = value.clockHeight
                renderer.clockOpacity = value.clockOpacity
            }
            renderer is BlurToSharpRenderer &&
                state is EffectPreviewRenderState.Atmosphere -> {
                val value = state.value
                renderer.blurStrength = value.progress
                renderer.dimLevel = value.dimLevel
                renderer.enableNoise = value.noiseEnabled
                renderer.noiseScale = value.noiseScale
                renderer.noiseStrength = value.noiseStrength
                renderer.blobSaturation = value.saturation
                renderer.blobContrast = value.contrast
                renderer.atmosphereGlassEnabled = value.glassEnabled
                renderer.glassLineCount = value.glassLineCount
                renderer.glassLineThickness = value.glassLineThickness
                renderer.configureGlassBackgroundOnly(value.glassBackgroundOnly)
                renderer.setDrawerBlurred(value.drawerBlur > 0.5f)
            }
            renderer is FrostedRenderer &&
                state is EffectPreviewRenderState.Frosted -> {
                val value = state.value
                renderer.blurStrength = value.progress
                renderer.dimLevel = value.dimLevel
                renderer.enableNoise = value.enableNoise
                renderer.noiseScale = value.noiseScale
                renderer.noiseStrength = value.noiseStrength
                renderer.blurRadius = value.blurRadius
                renderer.setDrawerBlurred(value.drawerBlur > 0.5f)
            }
            renderer is GlassRenderer &&
                state is EffectPreviewRenderState.Glass -> {
                val value = state.value
                renderer.progress = value.progress
                renderer.dimLevel = value.dimLevel
                renderer.lineCount = value.lineCount
                renderer.lineThickness = value.lineThickness
                renderer.transitionStyle = value.transitionStyle
                renderer.configureBackgroundOnly(value.backgroundOnly)
            }
            renderer is HalftoneRenderer &&
                state is EffectPreviewRenderState.Halftone -> {
                val value = state.value
                renderer.blurStrength = value.progress
                renderer.dimLevel = value.dimLevel
                renderer.dotSize = value.dotSize
                renderer.grayscale = value.grayscale
                renderer.configureBackgroundOnly(value.backgroundOnly)
            }
            renderer is ColorFillRenderer &&
                state is EffectPreviewRenderState.ColorFill -> {
                val value = state.value
                renderer.blurStrength = value.progress
                renderer.dimLevel = value.dimLevel
                renderer.originX = value.originX
                renderer.originY = value.originY
            }
            renderer is NeonRenderer &&
                state is EffectPreviewRenderState.Neon -> {
                val value = state.value
                renderer.blurStrength = value.progress
                renderer.dimLevel = value.dimLevel
                renderer.lineWidth = value.lineWidth
                renderer.sensitivity = value.sensitivity
                renderer.configureSubjectSegmentation(
                    value.subjectSegmentationEnabled
                )
            }
            else -> error("The preview renderer and effect state do not match")
        }
    }

    private fun wireOpenGlCallbacks(renderer: GLSurfaceView.Renderer) {
        val render = {
            if (!released.get()) previewContainer.post(::requestActiveRender)
        }
        when (renderer) {
            is NeonRenderer -> renderer.onSketchUpdated = render
            is AtmosphereRenderer -> {
                renderer.onSubjectMaskUpdated = render
                renderer.onRenderRetryRequested = ::requestRenderRetry
            }
            is BlurToSharpRenderer -> {
                renderer.onSubjectMaskUpdated = render
                renderer.onRenderRetryRequested = ::requestRenderRetry
            }
            is GlassRenderer -> {
                renderer.onSubjectMaskUpdated = render
                renderer.onRenderRetryRequested = ::requestRenderRetry
            }
            is HalftoneRenderer -> {
                renderer.onSubjectMaskUpdated = render
                renderer.onRenderRetryRequested = ::requestRenderRetry
            }
        }
    }

    private fun requestActiveRender() {
        if (released.get()) return
        when (activeBackend) {
            GraphicsBackend.VULKAN -> vulkanSession?.requestRender()
            GraphicsBackend.OPENGL_ES -> openGlSurface?.requestRender()
        }
    }

    private fun releaseOpenGl(pauseFirst: Boolean) {
        val surface = openGlSurface
        val renderer = openGlRenderer
        openGlSurface = null
        openGlRenderer = null
        if (surface != null) {
            runCatching {
                if (pauseFirst) surface.onPause()
                surface.destroy()
            }.onFailure { failure ->
                Log.w(TAG, "Unable to stop the effect preview GL thread", failure)
            }
        }
        when (renderer) {
            is AtmosphereRenderer -> renderer.release()
            is BlurToSharpRenderer -> renderer.release()
            is NeonRenderer -> renderer.release()
            is GlassRenderer -> renderer.release()
            is HalftoneRenderer -> renderer.release()
        }
    }

    private fun previewGlassSettings(preferences: SharedPreferences) =
        if (settingsMode == EffectPreviewSettingsMode.SAVED_ACTIVE) {
            GlassEffectPreferences.readAndMigrate(preferences)
        } else {
            GlassEffectPolicy.resolveStoredSettings(
                lineCount = GlassEffectPolicy.DEFAULT_LINE_COUNT,
                lineThickness = GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
                presetVersion = GlassEffectPolicy.CURRENT_PRESET_VERSION
            )
        }

    private fun previewAtmosphereGlassEnabled(preferences: SharedPreferences): Boolean {
        return atmosphereGlassEnabledOverride ?: previewBoolean(
            preferences,
            AtmosphereGlassPolicy.ENABLED_KEY,
            false
        )
    }

    private fun previewFloat(
        preferences: SharedPreferences,
        key: String,
        defaultValue: Float
    ): Float {
        if (settingsMode != EffectPreviewSettingsMode.SAVED_ACTIVE) return defaultValue
        return try {
            preferences.getFloat(key, defaultValue)
        } catch (_: ClassCastException) {
            defaultValue
        }
    }

    private fun previewBoolean(
        preferences: SharedPreferences,
        key: String,
        defaultValue: Boolean
    ): Boolean {
        if (settingsMode != EffectPreviewSettingsMode.SAVED_ACTIVE) return defaultValue
        return try {
            preferences.getBoolean(key, defaultValue)
        } catch (_: ClassCastException) {
            defaultValue
        }
    }

    companion object {
        private const val TAG = "EffectPreviewService"
        private const val RENDER_RETRY_DELAY_MS = 80L

        fun durationMillis(
            context: Context,
            effectId: String,
            settingsMode: EffectPreviewSettingsMode = EffectPreviewSettingsMode.SAVED_ACTIVE
        ): Int {
            val fallback = EffectCatalog.recommendedDurationMillis(effectId)
            if (settingsMode == EffectPreviewSettingsMode.EFFECT_DEFAULTS) {
                return fallback.coerceIn(150L, 10_000L).toInt()
            }
            val preferences = context.getSharedPreferences(
                "app_prefs",
                Context.MODE_PRIVATE
            )
            val saved = try {
                preferences.getLong("anim_duration", -1L)
            } catch (_: ClassCastException) {
                -1L
            }
            return (if (saved > 0L) saved else fallback).coerceIn(150L, 10_000L).toInt()
        }

        private fun createDemoWallpaper(): Bitmap {
            val width = 540
            val height = 960
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            canvas.drawColor(Color.rgb(30, 73, 82))
            paint.color = Color.rgb(239, 194, 102)
            canvas.drawCircle(width * 0.76f, height * 0.18f, width * 0.11f, paint)

            paint.color = Color.rgb(116, 100, 157)
            canvas.drawPath(Path().apply {
                moveTo(0f, height * 0.70f)
                lineTo(width * 0.34f, height * 0.38f)
                lineTo(width * 0.62f, height * 0.70f)
                close()
            }, paint)

            paint.color = Color.rgb(35, 70, 62)
            canvas.drawPath(Path().apply {
                moveTo(width * 0.28f, height * 0.72f)
                lineTo(width * 0.69f, height * 0.28f)
                lineTo(width.toFloat(), height * 0.72f)
                close()
            }, paint)

            paint.color = Color.rgb(204, 88, 79)
            canvas.drawRect(width * 0.09f, height * 0.50f, width * 0.36f, height * 0.78f, paint)
            paint.color = Color.rgb(249, 219, 157)
            repeat(3) { row ->
                repeat(3) { column ->
                    val left = width * (0.13f + column * 0.075f)
                    val top = height * (0.55f + row * 0.065f)
                    canvas.drawRect(left, top, left + width * 0.035f, top + height * 0.032f, paint)
                }
            }

            paint.color = Color.rgb(15, 38, 34)
            canvas.drawRect(0f, height * 0.72f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.rgb(106, 173, 140)
            paint.strokeWidth = width * 0.012f
            paint.style = Paint.Style.STROKE
            canvas.drawPath(Path().apply {
                moveTo(width * 0.52f, height * 0.91f)
                cubicTo(
                    width * 0.54f,
                    height * 0.78f,
                    width * 0.70f,
                    height * 0.80f,
                    width * 0.75f,
                    height * 0.68f
                )
            }, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(width * 0.77f, height * 0.65f, width * 0.045f, paint)
            return bitmap
        }
    }

    private class PreviewContainerView(
        context: Context,
        val cornerRadius: Float
    ) : FrameLayout(context) {
        init {
            clipChildren = true
            clipToOutline = true
            outlineProvider = RoundedOutlineProvider(cornerRadius)
        }

        fun showSurface(surface: View) {
            removeAllViews()
            addView(
                surface,
                LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            invalidateOutline()
        }
    }

    private class PreviewSurfaceView(context: Context, radius: Float) :
        GLSurfaceView(context) {
        private var destroyStarted = false

        init {
            clipToOutline = true
            outlineProvider = RoundedOutlineProvider(radius)
        }

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            super.onSizeChanged(width, height, oldWidth, oldHeight)
            invalidateOutline()
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
