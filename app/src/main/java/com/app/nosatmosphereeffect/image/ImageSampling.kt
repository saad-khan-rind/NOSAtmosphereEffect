package com.app.nosatmosphereeffect.image

import kotlin.math.max

internal object ImageSampling {
    fun sampleSize(sourceWidth: Int, sourceHeight: Int, maxDimension: Int): Int {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(maxDimension > 0) { "Maximum dimension must be positive" }

        val largest = max(sourceWidth, sourceHeight).toLong()
        val required = (largest + maxDimension - 1L) / maxDimension
        var sampleSize = 1
        while (sampleSize.toLong() < required && sampleSize <= Int.MAX_VALUE / 2) {
            sampleSize *= 2
        }
        return if (sampleSize.toLong() >= required) sampleSize else Int.MAX_VALUE
    }

    fun sampleSizeForTarget(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
        require(targetWidth > 0 && targetHeight > 0) { "Target dimensions must be positive" }

        var sampleSize = 1
        while (
            sampleSize <= Int.MAX_VALUE / 2 &&
            sourceWidth / (sampleSize * 2L) >= targetWidth &&
            sourceHeight / (sampleSize * 2L) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
