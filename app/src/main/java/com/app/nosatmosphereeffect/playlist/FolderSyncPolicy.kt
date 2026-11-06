package com.app.nosatmosphereeffect.playlist

/** Decides how a folder playlist follows its folders. Pure, so it is unit tested. */
internal object FolderSyncPolicy {

    /**
     * Positions of entries whose source image no longer exists in the watched
     * folders. Entries without a media id (added by hand) are never removed.
     * Returns nothing when removal would leave the playlist empty: a live
     * wallpaper needs at least one image, and a folder that suddenly reads as
     * empty is more often unmounted storage than a deliberate wipe.
     */
    fun removableIndices(mediaIds: List<Long?>, present: Set<Long>): Set<Int> {
        val removable = mediaIds.indices.filterTo(HashSet()) { index ->
            val id = mediaIds[index]
            id != null && id !in present
        }
        return if (removable.size == mediaIds.size) emptySet() else removable
    }

    /**
     * Known ids after a sync: ids that still exist (so images the user removed
     * from the playlist stay removed) plus the ones just offered. Deleted ids
     * are dropped so the set cannot grow forever.
     */
    fun knownAfterSync(known: Set<Long>, present: Set<Long>, offered: Collection<Long>): Set<Long> {
        return known.filterTo(HashSet()) { it in present } + offered
    }
}
