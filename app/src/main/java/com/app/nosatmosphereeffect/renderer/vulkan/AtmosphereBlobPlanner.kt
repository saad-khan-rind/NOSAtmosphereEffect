package com.app.nosatmosphereeffect.renderer.vulkan

import android.graphics.Bitmap
import android.graphics.Color
import com.app.nosatmosphereeffect.renderer.atmosphere.AtmosphereBlobFrame
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

internal class AtmosphereBlobPlanner(
    private val random: Random = Random()
) {
    private data class ColorPoint(
        val color: Int,
        val x: Int,
        val y: Int
    )

    private data class ColorCluster(
        val color: Int,
        val centerX: Float,
        val centerY: Float
    )

    private data class MutableCluster(
        var red: Int,
        var green: Int,
        var blue: Int,
        var x: Float,
        var y: Float,
        var count: Int
    )

    private data class Blob(
        val color: FloatArray,
        val startX: Float,
        val startY: Float,
        val massScale: Float,
        var controlX: Float = 0f,
        var controlY: Float = 0f,
        var endX: Float = 0f,
        var endY: Float = 0f,
        var startSize: Float = 0f,
        var endSize: Float = 0f
    )

    private val blobs = mutableListOf<Blob>()

    fun replaceImage(blurred: Bitmap) {
        require(!blurred.isRecycled) { "The blurred Atmosphere bitmap was recycled" }
        val clusters = extractColors(blurred, MAX_BLOBS)
            .map { cluster ->
                MutableCluster(
                    red = Color.red(cluster.color),
                    green = Color.green(cluster.color),
                    blue = Color.blue(cluster.color),
                    x = cluster.centerX,
                    y = cluster.centerY,
                    count = 1
                )
            }
        val merged = mergeNearbyClusters(clusters)

        blobs.clear()
        merged.forEach { cluster ->
            blobs += Blob(
                color = floatArrayOf(
                    cluster.red / 255f,
                    cluster.green / 255f,
                    cluster.blue / 255f
                ),
                startX = cluster.x,
                startY = cluster.y,
                massScale = min(1.4f, 1f + cluster.count * 0.05f)
            )
        }
        rerollTargets()
    }

    fun rerollTargets() {
        blobs.forEach { blob ->
            blob.endX = 0.05f + random.nextFloat() * 0.9f
            blob.endY = 0.05f + random.nextFloat() * 0.9f
            val midpointX = (blob.startX + blob.endX) / 2f
            val midpointY = (blob.startY + blob.endY) / 2f
            blob.controlX = midpointX + (random.nextFloat() - 0.5f) * 0.5f
            blob.controlY = midpointY + (random.nextFloat() - 0.5f) * 0.5f
            val baseSize = 0.12f + random.nextFloat() * 0.08f
            blob.startSize = 0.05f
            blob.endSize = baseSize * blob.massScale
        }
    }

    fun frame(globalProgress: Float): AtmosphereBlobFrame {
        val progress = physicsProgress(globalProgress)
        val inverse = 1f - progress
        val progressSquared = progress * progress
        val inverseSquared = inverse * inverse
        val cross = 2f * inverse * progress
        val count = blobs.size.coerceAtMost(MAX_BLOBS)
        val colors = FloatArray(MAX_BLOBS * 3)
        val positions = FloatArray(MAX_BLOBS * 2)
        val sizes = FloatArray(MAX_BLOBS)

        repeat(count) { index ->
            val blob = blobs[index]
            positions[index * 2] =
                inverseSquared * blob.startX +
                cross * blob.controlX +
                progressSquared * blob.endX
            positions[index * 2 + 1] =
                inverseSquared * blob.startY +
                cross * blob.controlY +
                progressSquared * blob.endY
            sizes[index] =
                blob.startSize +
                (blob.endSize - blob.startSize) * progress
            colors[index * 3] = blob.color[0]
            colors[index * 3 + 1] = blob.color[1]
            colors[index * 3 + 2] = blob.color[2]
        }
        return AtmosphereBlobFrame(colors, positions, sizes, count)
    }

    private fun extractColors(
        blurred: Bitmap,
        targetColors: Int
    ): List<ColorCluster> {
        val width = blurred.width
        val height = blurred.height
        val samples = mutableListOf<ColorPoint>()
        for (y in 0 until height step SAMPLE_STEP) {
            for (x in 0 until width step SAMPLE_STEP) {
                samples += ColorPoint(blurred.getPixel(x, y), x, y)
            }
        }
        return medianCut(samples, targetColors).mapNotNull { bucket ->
            if (bucket.isEmpty()) return@mapNotNull null
            var red = 0L
            var green = 0L
            var blue = 0L
            var x = 0f
            var y = 0f
            bucket.forEach { point ->
                red += Color.red(point.color)
                green += Color.green(point.color)
                blue += Color.blue(point.color)
                x += point.x
                y += point.y
            }
            val count = bucket.size
            ColorCluster(
                color = Color.rgb(
                    (red / count).toInt(),
                    (green / count).toInt(),
                    (blue / count).toInt()
                ),
                centerX = x / count / width,
                centerY = y / count / height
            )
        }
    }

    private fun mergeNearbyClusters(
        clusters: List<MutableCluster>
    ): List<MutableCluster> {
        val processed = BooleanArray(clusters.size)
        val merged = mutableListOf<MutableCluster>()
        clusters.indices.forEach { index ->
            if (processed[index]) return@forEach
            val main = clusters[index]
            processed[index] = true
            for (otherIndex in index + 1 until clusters.size) {
                if (processed[otherIndex]) continue
                val other = clusters[otherIndex]
                val colorDistance = hypot(
                    (main.red - other.red).toFloat(),
                    (main.green - other.green).toFloat()
                ) + abs(main.blue - other.blue)
                val spatialDistance = hypot(main.x - other.x, main.y - other.y)
                if (colorDistance < 90f && spatialDistance < 0.25f) {
                    val total = main.count + other.count
                    main.x = (main.x * main.count + other.x * other.count) / total
                    main.y = (main.y * main.count + other.y * other.count) / total
                    main.red =
                        (main.red * main.count + other.red * other.count) / total
                    main.green =
                        (main.green * main.count + other.green * other.count) / total
                    main.blue =
                        (main.blue * main.count + other.blue * other.count) / total
                    main.count = total
                    processed[otherIndex] = true
                }
            }
            merged += main
        }
        return merged
    }

    private fun medianCut(
        pixels: List<ColorPoint>,
        targetBuckets: Int
    ): List<List<ColorPoint>> {
        val buckets = mutableListOf(pixels.toMutableList())
        while (buckets.size < targetBuckets) {
            var largest: MutableList<ColorPoint>? = null
            var largestRange = 0
            var splitChannel = 0
            buckets.forEach { bucket ->
                if (bucket.size <= 1) return@forEach
                val redRange = bucket.channelRange(Color::red)
                val greenRange = bucket.channelRange(Color::green)
                val blueRange = bucket.channelRange(Color::blue)
                val range = maxOf(redRange, greenRange, blueRange)
                if (range > largestRange) {
                    largestRange = range
                    largest = bucket
                    splitChannel = when (range) {
                        redRange -> 0
                        greenRange -> 1
                        else -> 2
                    }
                }
            }
            val selected = largest ?: break
            val sorted = when (splitChannel) {
                0 -> selected.sortedBy { Color.red(it.color) }
                1 -> selected.sortedBy { Color.green(it.color) }
                else -> selected.sortedBy { Color.blue(it.color) }
            }
            val median = sorted.size / 2
            buckets.remove(selected)
            buckets += sorted.subList(0, median).toMutableList()
            buckets += sorted.subList(median, sorted.size).toMutableList()
        }
        return buckets
    }

    private inline fun List<ColorPoint>.channelRange(
        channel: (Int) -> Int
    ): Int {
        var minimum = 255
        var maximum = 0
        forEach { point ->
            val value = channel(point.color)
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
        }
        return maximum - minimum
    }

    internal companion object {
        const val MAX_BLOBS = AtmosphereBlobFrame.MAX_BLOBS
        private const val SAMPLE_STEP = 10

        fun physicsProgress(globalProgress: Float): Float {
            val safe = if (globalProgress.isFinite()) {
                globalProgress.coerceIn(0f, 1f)
            } else {
                0f
            }
            val physics = ((safe - 0.1f) / 0.9f).coerceIn(0f, 1f)
            return 1f - (1f - physics).pow(3)
        }
    }
}
