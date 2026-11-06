package com.app.nosatmosphereeffect.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFitPolicyTest {
    @Test
    fun `fill center-crops overflow on the long axis`() {
        val transform = ImageFitPolicy.transform(
            sourceWidth = 400,
            sourceHeight = 200,
            targetWidth = 100,
            targetHeight = 100,
            mode = ImageFitMode.FILL
        )

        assertEquals(0.5f, transform.scaleX, TOLERANCE)
        assertEquals(0.5f, transform.scaleY, TOLERANCE)
        assertEquals(-50f, transform.translateX, TOLERANCE)
        assertEquals(0f, transform.translateY, TOLERANCE)
    }

    @Test
    fun `fit letterboxes and centers the entire image`() {
        val transform = ImageFitPolicy.transform(
            sourceWidth = 400,
            sourceHeight = 200,
            targetWidth = 100,
            targetHeight = 100,
            mode = ImageFitMode.FIT
        )

        assertEquals(0.25f, transform.scaleX, TOLERANCE)
        assertEquals(0.25f, transform.scaleY, TOLERANCE)
        assertEquals(0f, transform.translateX, TOLERANCE)
        assertEquals(25f, transform.translateY, TOLERANCE)
    }

    @Test
    fun `stretch scales each axis independently without translation`() {
        val transform = ImageFitPolicy.transform(
            sourceWidth = 400,
            sourceHeight = 200,
            targetWidth = 100,
            targetHeight = 100,
            mode = ImageFitMode.STRETCH
        )

        assertEquals(0.25f, transform.scaleX, TOLERANCE)
        assertEquals(0.5f, transform.scaleY, TOLERANCE)
        assertEquals(0f, transform.translateX, TOLERANCE)
        assertEquals(0f, transform.translateY, TOLERANCE)
    }

    @Test
    fun `rotation is requested only when source and target orientations differ`() {
        assertTrue(ImageFitPolicy.shouldRotate(1920, 1080, 1080, 1920))
        assertTrue(ImageFitPolicy.shouldRotate(1920, 1080, 1000, 1000))
        assertFalse(ImageFitPolicy.shouldRotate(1080, 1920, 1000, 1000))
        assertFalse(ImageFitPolicy.shouldRotate(1920, 1080, 2400, 1080))
    }

    @Test
    fun `scroll layout caps wide textures and reports the visible fraction`() {
        val layout = requireNotNull(
            ImageFitPolicy.scrollLayout(
                sourceWidth = 4000,
                sourceHeight = 1000,
                surfaceWidth = 1000,
                surfaceHeight = 2000,
                maxWidthFactor = 2f
            )
        )

        assertEquals(2f, layout.scale, TOLERANCE)
        assertEquals(8000f, layout.scaledWidth, TOLERANCE)
        assertEquals(2000, layout.canvasWidth)
        assertEquals(-3000f, layout.translateX, TOLERANCE)
        assertEquals(0.5f, layout.visibleWidthFraction, TOLERANCE)
    }

    @Test
    fun `scroll layout declines images with no horizontal overflow`() {
        assertNull(
            ImageFitPolicy.scrollLayout(
                sourceWidth = 1000,
                sourceHeight = 2000,
                surfaceWidth = 1080,
                surfaceHeight = 1920,
                maxWidthFactor = 2f
            )
        )
    }

    @Test
    fun `geometry rejects invalid dimensions and width factors`() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageFitPolicy.transform(0, 100, 100, 100, ImageFitMode.FILL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageFitPolicy.shouldRotate(100, 100, -1, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageFitPolicy.scrollLayout(100, 100, 100, 100, 0.9f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageFitPolicy.scrollLayout(100, 100, 100, 100, Float.NaN)
        }
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
