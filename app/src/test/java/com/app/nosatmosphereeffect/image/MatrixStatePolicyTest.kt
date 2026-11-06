package com.app.nosatmosphereeffect.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixStatePolicyTest {
    @Test
    fun `accepts a finite nine-value Android matrix`() {
        assertTrue(
            MatrixStatePolicy.isValid(
                floatArrayOf(1f, 0f, 12f, 0f, 1f, -4f, 0f, 0f, 1f)
            )
        )
    }

    @Test
    fun `rejects missing and incorrectly sized matrices`() {
        assertFalse(MatrixStatePolicy.isValid(null))
        assertFalse(MatrixStatePolicy.isValid(FloatArray(8)))
        assertFalse(MatrixStatePolicy.isValid(FloatArray(10)))
    }

    @Test
    fun `rejects non-finite matrix values`() {
        assertFalse(
            MatrixStatePolicy.isValid(
                floatArrayOf(1f, 0f, Float.NaN, 0f, 1f, 0f, 0f, 0f, 1f)
            )
        )
        assertFalse(
            MatrixStatePolicy.isValid(
                floatArrayOf(1f, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 0f, 0f, 0f, 1f)
            )
        )
    }

    @Test
    fun `valid copy is independent from caller storage`() {
        val source = floatArrayOf(1f, 0f, 12f, 0f, 1f, -4f, 0f, 0f, 1f)
        val copy = requireNotNull(MatrixStatePolicy.copyIfValid(source))

        assertNotSame(source, copy)
        assertArrayEquals(source, copy, 0f)
    }

    @Test
    fun `invalid matrix has no normalized copy`() {
        assertNull(MatrixStatePolicy.copyIfValid(floatArrayOf(Float.NaN)))
    }
}
