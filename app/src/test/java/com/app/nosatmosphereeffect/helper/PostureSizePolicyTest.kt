package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostureSizePolicyTest {

    private val cover = SurfaceSize(1080, 2316)
    private val inner = SurfaceSize(1812, 2176)

    @Test
    fun `a phone that only ever reports one size has no alternate posture`() {
        val known = PostureSizePolicy.remember(emptyList(), cover)

        assertEquals(listOf(cover), known)
        assertNull(PostureSizePolicy.alternate(known, cover))
    }

    @Test
    fun `unfolding records the new size and reports the folded one as the alternate`() {
        val folded = PostureSizePolicy.remember(emptyList(), cover)
        val unfolded = PostureSizePolicy.remember(folded, inner)

        assertEquals(listOf(inner, cover), unfolded)
        assertEquals(cover, PostureSizePolicy.alternate(unfolded, inner))
        assertEquals(inner, PostureSizePolicy.alternate(unfolded, cover))
    }

    @Test
    fun `repeated folding does not grow or duplicate the record`() {
        var known = PostureSizePolicy.remember(emptyList(), cover)
        repeat(6) {
            known = PostureSizePolicy.remember(known, inner)
            known = PostureSizePolicy.remember(known, cover)
        }

        assertEquals(2, known.size)
        assertTrue(known.containsAll(listOf(cover, inner)))
    }

    @Test
    fun `a third size evicts the least recently used one`() {
        val desktopMode = SurfaceSize(2560, 1440)
        var known = PostureSizePolicy.remember(emptyList(), cover)
        known = PostureSizePolicy.remember(known, inner)
        known = PostureSizePolicy.remember(known, desktopMode)

        assertEquals(listOf(desktopMode, inner), known)
    }

    @Test
    fun `unusable sizes are never recorded`() {
        val known = PostureSizePolicy.remember(listOf(cover), SurfaceSize(0, 2316))

        assertEquals(listOf(cover), known)
    }

    @Test
    fun `sizes survive a round trip through storage`() {
        val known = listOf(inner, cover)

        assertEquals(known, PostureSizePolicy.decode(PostureSizePolicy.encode(known)))
    }

    @Test
    fun `malformed storage degrades to what can still be read`() {
        assertEquals(emptyList<SurfaceSize>(), PostureSizePolicy.decode(null))
        assertEquals(emptyList<SurfaceSize>(), PostureSizePolicy.decode(""))
        assertEquals(listOf(cover), PostureSizePolicy.decode("1080x2316,junk,0x0"))
        assertEquals(emptyList<SurfaceSize>(), PostureSizePolicy.decode("1080,2316"))
    }
}
