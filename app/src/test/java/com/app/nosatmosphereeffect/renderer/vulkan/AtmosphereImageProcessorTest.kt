package com.app.nosatmosphereeffect.renderer.vulkan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AtmosphereImageProcessorTest {
    @Test
    fun `prefix-sum blur matches the OpenGL triangular kernel horizontally`() {
        val source = intArrayOf(
            rgb(10, 30, 50),
            rgb(30, 50, 70),
            rgb(90, 110, 130),
            rgb(170, 190, 210),
            rgb(250, 230, 210)
        )
        val actual = IntArray(source.size)

        AtmosphereImageProcessor.triangularBlurPixels(
            source = source,
            destination = actual,
            width = source.size,
            height = 1,
            radius = 3,
            horizontal = true
        )

        assertArrayEquals(
            bruteForce(
                source = source,
                width = source.size,
                height = 1,
                radius = 3,
                horizontal = true
            ),
            actual
        )
    }

    @Test
    fun `prefix-sum blur matches the OpenGL triangular kernel vertically`() {
        val source = intArrayOf(
            rgb(0, 10, 20),
            rgb(40, 50, 60),
            rgb(80, 90, 100),
            rgb(120, 130, 140)
        )
        val actual = IntArray(source.size)

        AtmosphereImageProcessor.triangularBlurPixels(
            source = source,
            destination = actual,
            width = 1,
            height = source.size,
            radius = 4,
            horizontal = false
        )

        assertArrayEquals(
            bruteForce(
                source = source,
                width = 1,
                height = source.size,
                radius = 4,
                horizontal = false
            ),
            actual
        )
    }

    @Test
    fun `constant edge-clamped image remains constant at radius 200`() {
        val source = IntArray(12) { rgb(31, 97, 211) }
        val actual = IntArray(source.size)

        AtmosphereImageProcessor.triangularBlurPixels(
            source = source,
            destination = actual,
            width = 4,
            height = 3,
            radius = AtmosphereImageProcessor.BLUR_RADIUS,
            horizontal = true
        )

        assertArrayEquals(source, actual)
    }

    @Test
    fun `blob physics timing matches the OpenGL easing endpoints`() {
        assertEquals(0f, AtmosphereBlobPlanner.physicsProgress(0f), 0f)
        assertEquals(0f, AtmosphereBlobPlanner.physicsProgress(0.1f), 0f)
        assertEquals(1f, AtmosphereBlobPlanner.physicsProgress(1f), 0f)
        assertEquals(0f, AtmosphereBlobPlanner.physicsProgress(Float.NaN), 0f)
    }

    private fun bruteForce(
        source: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean
    ): IntArray {
        val output = IntArray(source.size)
        val normalizer = radius * radius
        repeat(height) { y ->
            repeat(width) { x ->
                val channels = IntArray(3)
                for (offset in -radius + 1 until radius) {
                    val weight = radius - kotlin.math.abs(offset)
                    val sampleX = if (horizontal) {
                        (x + offset).coerceIn(0, width - 1)
                    } else {
                        x
                    }
                    val sampleY = if (horizontal) {
                        y
                    } else {
                        (y + offset).coerceIn(0, height - 1)
                    }
                    val pixel = source[sampleY * width + sampleX]
                    channels[0] += ((pixel ushr 16) and 0xFF) * weight
                    channels[1] += ((pixel ushr 8) and 0xFF) * weight
                    channels[2] += (pixel and 0xFF) * weight
                }
                output[y * width + x] = rgb(
                    (channels[0] + normalizer / 2) / normalizer,
                    (channels[1] + normalizer / 2) / normalizer,
                    (channels[2] + normalizer / 2) / normalizer
                )
            }
        }
        return output
    }

    @Test
    fun `a wide blur is computed at reduced resolution`() {
        assertEquals(
            4,
            AtmosphereImageProcessor.blurDownscale(
                radius = AtmosphereImageProcessor.BLUR_RADIUS,
                width = 1812,
                height = 2176
            )
        )
    }

    @Test
    fun `a narrow blur keeps full resolution so its steps stay invisible`() {
        assertEquals(
            1,
            AtmosphereImageProcessor.blurDownscale(radius = 20, width = 1080, height = 2316)
        )
        assertEquals(
            2,
            AtmosphereImageProcessor.blurDownscale(radius = 40, width = 1080, height = 2316)
        )
    }

    @Test
    fun `small images are never shrunk below a useful working size`() {
        assertEquals(
            1,
            AtmosphereImageProcessor.blurDownscale(radius = 200, width = 96, height = 96)
        )
    }

    @Test
    fun `the reduction never exceeds its ceiling`() {
        assertEquals(
            4,
            AtmosphereImageProcessor.blurDownscale(radius = 4096, width = 4096, height = 4096)
        )
    }

    @Test
    fun `an empty source is rejected rather than silently scaled`() {
        assertThrows(IllegalArgumentException::class.java) {
            AtmosphereImageProcessor.blurDownscale(radius = 200, width = 0, height = 100)
        }
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int {
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
