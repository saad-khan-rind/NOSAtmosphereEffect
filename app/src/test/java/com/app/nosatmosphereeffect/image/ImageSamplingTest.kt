package com.app.nosatmosphereeffect.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageSamplingTest {
    @Test
    fun `images already inside the bound are not downsampled`() {
        assertEquals(1, ImageSampling.sampleSize(4096, 2048, 4096))
        assertEquals(1, ImageSampling.sampleSize(1, 1, 4096))
    }

    @Test
    fun `sample size rounds up to keep the decoded image inside the bound`() {
        assertEquals(2, ImageSampling.sampleSize(4097, 1000, 4096))
        assertEquals(4, ImageSampling.sampleSize(8193, 1000, 4096))
        assertEquals(8, ImageSampling.sampleSize(16_385, 1000, 4096))
    }

    @Test
    fun `extreme dimensions cannot overflow the sample-size loop`() {
        assertEquals(Int.MAX_VALUE, ImageSampling.sampleSize(Int.MAX_VALUE, 1, 1))
    }

    @Test
    fun `invalid dimensions fail with a useful contract`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageSampling.sampleSize(0, 100, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageSampling.sampleSize(100, -1, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageSampling.sampleSize(100, 100, 0)
        }
    }

    @Test
    fun `target sampling keeps both decoded dimensions large enough`() {
        assertEquals(
            2,
            ImageSampling.sampleSizeForTarget(
                sourceWidth = 8000,
                sourceHeight = 6000,
                targetWidth = 2400,
                targetHeight = 1080
            )
        )
        assertEquals(
            1,
            ImageSampling.sampleSizeForTarget(
                sourceWidth = 8000,
                sourceHeight = 1000,
                targetWidth = 1080,
                targetHeight = 2400
            )
        )
    }

    @Test
    fun `target sampling returns the largest safe power of two`() {
        assertEquals(4, ImageSampling.sampleSizeForTarget(4096, 4096, 1000, 1000))
        assertEquals(1, ImageSampling.sampleSizeForTarget(1000, 1000, 1000, 1000))
    }

    @Test
    fun `target sampling rejects invalid target dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageSampling.sampleSizeForTarget(100, 100, 0, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageSampling.sampleSizeForTarget(100, 100, 100, -1)
        }
    }
}
