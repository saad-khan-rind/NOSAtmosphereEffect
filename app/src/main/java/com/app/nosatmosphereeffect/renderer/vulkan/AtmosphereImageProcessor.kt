package com.app.nosatmosphereeffect.renderer.vulkan

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale

internal object AtmosphereImageProcessor {
    const val BLUR_RADIUS = 200

    /**
     * A blur this wide carries no detail finer than the radius, so running it
     * over every pixel of a full-resolution wallpaper is wasted work — and it
     * is CPU work on the render thread, repeated every time the wallpaper is
     * re-fitted. On a foldable that is every fold, where it is the difference
     * between the correct frame arriving promptly and the compositor stretching
     * the old one while the blur grinds. Blurring a downscaled copy and scaling
     * the result back is visually indistinguishable at these radii and costs
     * the square of the factor less.
     */
    private const val MAX_DOWNSCALE = 4

    /** Below this the reduced-resolution blur starts to show its steps. */
    private const val MIN_EFFECTIVE_RADIUS = 16

    /** Smallest dimension worth blurring; below it the scaling dominates. */
    private const val MIN_WORKING_DIMENSION = 64

    /**
     * The factor to shrink by before blurring: the largest power of two that
     * leaves the blur wide enough, in its own pixels, to still be smooth.
     */
    internal fun blurDownscale(
        radius: Int,
        width: Int,
        height: Int,
        maxFactor: Int = MAX_DOWNSCALE
    ): Int {
        require(width > 0 && height > 0) { "The blur source is empty" }
        var factor = 1
        while (factor * 2 <= maxFactor &&
            radius / (factor * 2) >= MIN_EFFECTIVE_RADIUS &&
            width / (factor * 2) >= MIN_WORKING_DIMENSION &&
            height / (factor * 2) >= MIN_WORKING_DIMENSION
        ) {
            factor *= 2
        }
        return factor
    }

    fun createBlurredBitmap(
        source: Bitmap,
        radius: Int = BLUR_RADIUS
    ): Bitmap {
        require(!source.isRecycled) { "The Atmosphere source bitmap was recycled" }
        require(source.width > 0 && source.height > 0) {
            "The Atmosphere source bitmap is empty"
        }

        val safeRadius = radius.coerceAtLeast(1)
        val factor = blurDownscale(safeRadius, source.width, source.height)
        if (factor == 1) return blurPixels(source, safeRadius)

        val reduced = source.scale(
            (source.width / factor).coerceAtLeast(1),
            (source.height / factor).coerceAtLeast(1)
        )
        val blurred = try {
            blurPixels(reduced, (safeRadius / factor).coerceAtLeast(1))
        } finally {
            if (reduced !== source) reduced.recycle()
        }
        return try {
            blurred.scale(source.width, source.height)
        } finally {
            blurred.recycle()
        }
    }

    private fun blurPixels(source: Bitmap, radius: Int): Bitmap {
        val width = source.width
        val height = source.height
        val sourcePixels = IntArray(width * height)
        val horizontal = IntArray(sourcePixels.size)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        triangularBlurPixels(
            source = sourcePixels,
            destination = horizontal,
            width = width,
            height = height,
            radius = radius,
            horizontal = true
        )
        triangularBlurPixels(
            source = horizontal,
            destination = sourcePixels,
            width = width,
            height = height,
            radius = radius,
            horizontal = false
        )

        return createBitmap(width, height).apply {
            setPixels(sourcePixels, 0, width, 0, 0, width, height)
        }
    }

    internal fun triangularBlurPixels(
        source: IntArray,
        destination: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ) {
        require(width > 0 && height > 0)
        require(source.size == width * height)
        require(destination.size == source.size)

        val safeRadius = radius.coerceAtLeast(1)
        val lineLength = if (horizontal) width else height
        val prefix = LongArray(lineLength + 1)
        val weightedPrefix = LongArray(lineLength + 1)
        destination.fill(OPAQUE_ALPHA)
        if (horizontal) {
            repeat(height) { row ->
                repeatColorChannel { shift ->
                    blurLine(
                        length = width,
                        radius = safeRadius,
                        read = { column ->
                            source[row * width + column].channel(shift)
                        },
                        prefix = prefix,
                        weightedPrefix = weightedPrefix,
                        write = { column, value ->
                            val index = row * width + column
                            destination[index] =
                                destination[index] or (value shl shift)
                        }
                    )
                }
            }
        } else {
            repeat(width) { column ->
                repeatColorChannel { shift ->
                    blurLine(
                        length = height,
                        radius = safeRadius,
                        read = { row ->
                            source[row * width + column].channel(shift)
                        },
                        prefix = prefix,
                        weightedPrefix = weightedPrefix,
                        write = { row, value ->
                            val index = row * width + column
                            destination[index] =
                                destination[index] or (value shl shift)
                        }
                    )
                }
            }
        }
    }

    private inline fun blurLine(
        length: Int,
        radius: Int,
        read: (Int) -> Int,
        prefix: LongArray,
        weightedPrefix: LongArray,
        write: (Int, Int) -> Unit
    ) {
        prefix[0] = 0L
        weightedPrefix[0] = 0L
        for (index in 0 until length) {
            val value = read(index).toLong()
            prefix[index + 1] = prefix[index] + value
            weightedPrefix[index + 1] =
                weightedPrefix[index] + value * index
        }

        val normalization = radius.toLong() * radius
        for (center in 0 until length) {
            val leftStart = center - radius + 1
            val leftInBoundsStart = leftStart.coerceAtLeast(0)
            val leftSum = range(prefix, leftInBoundsStart, center)
            val leftWeighted = range(
                weightedPrefix,
                leftInBoundsStart,
                center
            )
            var weightedSum =
                leftWeighted +
                (radius - center).toLong() * leftSum

            if (leftStart < 0) {
                val clampedSamples = -leftStart
                weightedSum +=
                    read(0).toLong() *
                    triangularNumber(clampedSamples)
            }

            val rightEnd = center + radius - 1
            val rightInBoundsEnd = rightEnd.coerceAtMost(length - 1)
            if (center + 1 <= rightInBoundsEnd) {
                val rightSum = range(
                    prefix,
                    center + 1,
                    rightInBoundsEnd
                )
                val rightWeighted = range(
                    weightedPrefix,
                    center + 1,
                    rightInBoundsEnd
                )
                weightedSum +=
                    (center + radius).toLong() * rightSum -
                    rightWeighted
            }

            if (rightEnd >= length) {
                val clampedSamples = rightEnd - length + 1
                weightedSum +=
                    read(length - 1).toLong() *
                    triangularNumber(clampedSamples)
            }

            val rounded = ((weightedSum + normalization / 2L) / normalization)
                .toInt()
                .coerceIn(0, 255)
            write(center, rounded)
        }
    }

    private fun range(prefix: LongArray, start: Int, endInclusive: Int): Long {
        return if (start > endInclusive) {
            0L
        } else {
            prefix[endInclusive + 1] - prefix[start]
        }
    }

    private fun triangularNumber(value: Int): Long {
        return value.toLong() * (value + 1L) / 2L
    }

    private inline fun repeatColorChannel(block: (shift: Int) -> Unit) {
        block(RED_SHIFT)
        block(GREEN_SHIFT)
        block(BLUE_SHIFT)
    }

    private fun Int.channel(shift: Int): Int = ushr(shift) and 0xFF

    private const val OPAQUE_ALPHA = -0x1000000
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BLUE_SHIFT = 0
}
