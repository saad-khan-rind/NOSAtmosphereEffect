package com.app.nosatmosphereeffect.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSyncPolicyTest {
    @Test
    fun `entries whose source image was deleted are removed`() {
        val removable = FolderSyncPolicy.removableIndices(
            mediaIds = listOf(1L, 2L, 3L, 4L),
            present = setOf(1L, 3L)
        )

        assertEquals(setOf(1, 3), removable)
    }

    @Test
    fun `images added by hand are never removed`() {
        val removable = FolderSyncPolicy.removableIndices(
            mediaIds = listOf(null, 2L, null),
            present = emptySet()
        )

        assertEquals(setOf(1), removable)
    }

    @Test
    fun `a sync never empties the playlist`() {
        val removable = FolderSyncPolicy.removableIndices(
            mediaIds = listOf(1L, 2L),
            present = emptySet()
        )

        assertTrue(removable.isEmpty())
    }

    @Test
    fun `known ids keep surviving images and drop deleted ones`() {
        val known = FolderSyncPolicy.knownAfterSync(
            known = setOf(1L, 2L, 3L),
            present = setOf(1L, 3L, 9L),
            offered = listOf(9L)
        )

        assertEquals(setOf(1L, 3L, 9L), known)
    }
}
