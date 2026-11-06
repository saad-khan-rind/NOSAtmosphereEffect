package com.app.nosatmosphereeffect.image

import kotlin.math.max
import kotlin.math.min

internal enum class ImageFitMode {
    FILL,
    FIT,
    STRETCH
}

internal data class ImageFitTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float
)

internal data class ScrollLayout(
    val scale: Float,
    val scaledWidth: Float,
    val canvasWidth: Int,
    val translateX: Float,
    val visibleWidthFraction: Float
)

internal object ImageFitPolicy {
    fun transform(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        mode: ImageFitMode
    ): ImageFitTransform {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "Target dimensions must be positive" }

        val widthScale = targetWidth.toFloat() / sourceWidth
        val heightScale = targetHeight.toFloat() / sourceHeight
        return when (mode) {
            ImageFitMode.STRETCH -> ImageFitTransform(
                scaleX = widthScale,
                scaleY = heightScale,
                translateX = 0f,
                translateY = 0f
            )

            ImageFitMode.FILL -> centeredTransform(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                scale = max(widthScale, heightScale)
            )

            ImageFitMode.FIT -> centeredTransform(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                scale = min(widthScale, heightScale)
            )
        }
    }

    fun shouldRotate(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Boolean {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "Target dimensions must be positive" }
        return (sourceWidth > sourceHeight) != (targetWidth > targetHeight)
    }

    fun scrollLayout(
        sourceWidth: Int,
        sourceHeight: Int,
        surfaceWidth: Int,
        surfaceHeight: Int,
        maxWidthFactor: Float
    ): ScrollLayout? {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(surfaceWidth > 0 && surfaceHeight > 0) { "Surface dimensions must be positive" }
        require(maxWidthFactor >= 1f && maxWidthFactor.isFinite()) {
            "Maximum width factor must be finite and at least 1"
        }

        val scale = surfaceHeight.toFloat() / sourceHeight
        val scaledWidth = sourceWidth * scale
        if (scaledWidth <= surfaceWidth + 0.5f) return null

        val canvasWidth = min(scaledWidth, surfaceWidth * maxWidthFactor)
            .toInt()
            .coerceAtLeast(surfaceWidth)
        return ScrollLayout(
            scale = scale,
            scaledWidth = scaledWidth,
            canvasWidth = canvasWidth,
            translateX = (canvasWidth - scaledWidth) / 2f,
            visibleWidthFraction = (surfaceWidth.toFloat() / canvasWidth).coerceIn(0.05f, 1f)
        )
    }

    private fun centeredTransform(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        scale: Float
    ) = ImageFitTransform(
        scaleX = scale,
        scaleY = scale,
        translateX = (targetWidth - sourceWidth * scale) / 2f,
        translateY = (targetHeight - sourceHeight * scale) / 2f
    )
}
