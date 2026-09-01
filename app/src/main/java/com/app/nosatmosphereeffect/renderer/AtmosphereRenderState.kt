package com.app.nosatmosphereeffect.renderer

import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy
import com.app.nosatmosphereeffect.helper.GlassEffectPolicy

data class AtmosphereBlobFrame(
    val colors: FloatArray = FloatArray(MAX_BLOBS * 3),
    val positions: FloatArray = FloatArray(MAX_BLOBS * 2),
    val sizes: FloatArray = FloatArray(MAX_BLOBS),
    val count: Int = 0
) {
    fun sanitized(): AtmosphereBlobFrame {
        val safeCount = count.coerceIn(0, MAX_BLOBS)
        val alreadySafe =
            safeCount == count &&
            colors.size == MAX_BLOBS * 3 &&
            positions.size == MAX_BLOBS * 2 &&
            sizes.size == MAX_BLOBS &&
            colors.all { it.isFinite() && it in 0f..1f } &&
            positions.all { it.isFinite() } &&
            sizes.all { it.isFinite() && it >= 0f }
        if (alreadySafe) return this

        return AtmosphereBlobFrame(
            colors = FloatArray(MAX_BLOBS * 3) { index ->
                colors.getOrNull(index).finiteOr(0f).coerceIn(0f, 1f)
            },
            positions = FloatArray(MAX_BLOBS * 2) { index ->
                positions.getOrNull(index).finiteOr(0f)
            },
            sizes = FloatArray(MAX_BLOBS) { index ->
                sizes.getOrNull(index).finiteOr(0f).coerceAtLeast(0f)
            },
            count = safeCount
        )
    }

    private fun Float?.finiteOr(fallback: Float): Float {
        return if (this != null && isFinite()) this else fallback
    }

    companion object {
        const val MAX_BLOBS = 16
    }
}

data class AtmosphereRenderState(
    val progress: Float = 0f,
    val dimLevel: Float = 0.2f,
    val noiseEnabled: Boolean = false,
    val noiseScale: Float = 2_000f,
    val noiseStrength: Float = 0.06f,
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val glassEnabled: Boolean = false,
    val glassLineCount: Int = GlassEffectPolicy.DEFAULT_LINE_COUNT,
    val glassLineThickness: Float = GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
    val glassBackgroundOnly: Boolean = false,
    val hasSubject: Boolean = false,
    val drawerBlur: Float = 0f,
    val scrollOffsetX: Float = 0.5f,
    val scrollWindowX: Float = 1f,
    val clockEnabled: Boolean = false,
    val clockCenterX: Float = AtmosphereClockPolicy.DEFAULT_CENTER_X,
    val clockTop: Float = AtmosphereClockPolicy.DEFAULT_TOP,
    val clockHeight: Float = AtmosphereClockPolicy.DEFAULT_HEIGHT,
    val clockOpacity: Float = AtmosphereClockPolicy.DEFAULT_OPACITY,
    // Vulkan-only, dynamic (like hasSubject/blobs below): the clock
    // bitmap's width/height ratio, refreshed whenever a fresh minute is
    // rendered, so the shader can size the clock quad without a native
    // round-trip. Unused on the GLES path (AtmosphereRenderer computes this
    // itself from ClockTextureProvider directly).
    val clockTextureAspect: Float = 1f,
    val blobs: AtmosphereBlobFrame = AtmosphereBlobFrame()
) {
    fun sanitized(): AtmosphereRenderState {
        return copy(
            progress = progress.finiteOr(0f).coerceIn(0f, 1f),
            dimLevel = dimLevel.finiteOr(0.2f).coerceIn(0f, 1f),
            noiseScale = noiseScale.finiteOr(2_000f).coerceAtLeast(0f),
            noiseStrength = noiseStrength.finiteOr(0.06f).coerceAtLeast(0f),
            saturation = saturation.finiteOr(1f).coerceAtLeast(0f),
            contrast = contrast.finiteOr(1f).coerceAtLeast(0f),
            glassLineCount = GlassEffectPolicy.sanitizeLineCount(glassLineCount),
            glassLineThickness = GlassEffectPolicy.sanitizeLineThickness(
                glassLineThickness
            ),
            glassBackgroundOnly = glassEnabled && glassBackgroundOnly,
            drawerBlur = drawerBlur.finiteOr(0f).coerceIn(0f, 1f),
            scrollOffsetX = scrollOffsetX.finiteOr(0.5f).coerceIn(0f, 1f),
            scrollWindowX = scrollWindowX.finiteOr(1f).coerceIn(MIN_SCROLL_WINDOW, 1f),
            clockCenterX = AtmosphereClockPolicy.sanitizeCenterX(clockCenterX),
            clockTop = AtmosphereClockPolicy.sanitizeTop(clockTop),
            clockHeight = AtmosphereClockPolicy.sanitizeHeight(clockHeight),
            clockOpacity = AtmosphereClockPolicy.sanitizeOpacity(clockOpacity),
            clockTextureAspect = clockTextureAspect.finiteOr(1f).coerceIn(0.05f, 20f),
            blobs = blobs.sanitized()
        )
    }

    private fun Float.finiteOr(fallback: Float): Float {
        return if (isFinite()) this else fallback
    }

    private companion object {
        const val MIN_SCROLL_WINDOW = 0.001f
    }
}
