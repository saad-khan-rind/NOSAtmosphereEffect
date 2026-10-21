package com.app.nosatmosphereeffect.helper

import kotlin.random.Random

internal enum class RotationSkipReason {
    SINGLE_MODE,
    NON_THEME_EVENT,
    THEME_UNCHANGED,
    EMPTY_PLAYLIST,
    ONLY_PLAYLIST_ITEM,
    INTERVAL_NOT_ELAPSED
}

internal data class ShuffleQueue(val queue: List<String>, val played: List<String>)

internal data class RotationDecision(
    val shouldRotate: Boolean,
    val themeChanged: Boolean,
    val skipReason: RotationSkipReason? = null
)

internal object PlaylistRotationPolicy {
    private const val MILLIS_PER_MINUTE = 60_000L

    fun decide(
        mode: String,
        isThemeChange: Boolean,
        isNightMode: Boolean,
        activeThemeState: Int,
        playlistSize: Int,
        intervalMinutes: Long,
        lastRotationMillis: Long,
        nowMillis: Long,
        forceRotate: Boolean = false
    ): RotationDecision {
        require(
            mode == PlaylistModeManager.MODE_SINGLE ||
                mode == PlaylistModeManager.MODE_STANDARD ||
                mode == PlaylistModeManager.MODE_THEME
        ) { "Unsupported playlist mode: $mode" }
        require(playlistSize >= 0) { "Playlist size cannot be negative" }
        require(lastRotationMillis >= 0L && nowMillis >= 0L) {
            "Rotation timestamps cannot be negative"
        }

        if (mode == PlaylistModeManager.MODE_SINGLE) {
            return skipped(RotationSkipReason.SINGLE_MODE)
        }
        if (isThemeChange && mode != PlaylistModeManager.MODE_THEME) {
            return skipped(RotationSkipReason.NON_THEME_EVENT)
        }

        val nextThemeState = if (isNightMode) 1 else 0
        val themeChanged = mode == PlaylistModeManager.MODE_THEME &&
            activeThemeState != nextThemeState
        if (isThemeChange && !themeChanged) {
            return skipped(RotationSkipReason.THEME_UNCHANGED)
        }
        if (playlistSize == 0) {
            return skipped(RotationSkipReason.EMPTY_PLAYLIST, themeChanged)
        }
        // The displayed image left the playlist (deleted from a followed
        // folder): replace it now, even with one image left or mid-interval.
        if (forceRotate && !isThemeChange) {
            return RotationDecision(shouldRotate = true, themeChanged = themeChanged)
        }
        if (!themeChanged && playlistSize == 1) {
            return skipped(RotationSkipReason.ONLY_PLAYLIST_ITEM)
        }
        if (!themeChanged && !intervalElapsed(
                intervalMinutes = intervalMinutes,
                lastRotationMillis = lastRotationMillis,
                nowMillis = nowMillis
            )
        ) {
            return skipped(RotationSkipReason.INTERVAL_NOT_ELAPSED)
        }
        return RotationDecision(shouldRotate = true, themeChanged = themeChanged)
    }

    /**
     * The playlist's shuffle queue, ready to take the next image from the
     * front: every image once, in random order, before any repeats.
     *
     * [queue] is what is left of this round and [played] what it has shown,
     * oldest first. Images no longer in [available] drop out; images new to
     * the playlist join this round at random places; an empty queue starts a
     * new round, a fresh Fisher-Yates shuffle in which the last quarter of
     * the round before stays out of the new round's first quarter, so no
     * image comes back soon after it was shown. The image just shown
     * ([lastUsedName]) is never next, unless it is the only one.
     */
    fun shuffleQueue(
        queue: List<String>,
        played: List<String>,
        available: List<String>,
        lastUsedName: String?,
        random: Random = Random.Default
    ): ShuffleQueue {
        val present = available.toSet()
        val remaining = queue.filter { it in present }.distinct().toMutableList()
        val known = remaining.toSet() + played
        available.distinct().filterNot { it in known }.forEach { name ->
            remaining.add(random.nextInt(remaining.size + 1), name)
        }
        val newRound = remaining.isEmpty()
        if (newRound) remaining.addAll(newRound(available.distinct(), played, lastUsedName, random))
        if (remaining.size > 1 && remaining.first() == lastUsedName) {
            remaining.add(remaining.removeAt(0))
        }
        return ShuffleQueue(remaining, if (newRound) emptyList() else played.filter { it in present }.distinct())
    }

    private fun newRound(
        images: List<String>,
        previous: List<String>,
        lastUsedName: String?,
        random: Random
    ): List<String> {
        val gap = images.size / 4
        val recent = (previous.takeLast(gap) + listOfNotNull(lastUsedName)).toSet()
        val (recentImages, others) = images.shuffled(random).partition { it in recent }
        val opening = others.take(gap)
        return opening + (others.drop(opening.size) + recentImages).shuffled(random)
    }

    private fun intervalElapsed(
        intervalMinutes: Long,
        lastRotationMillis: Long,
        nowMillis: Long
    ): Boolean {
        if (intervalMinutes <= 0L) return true
        if (nowMillis <= lastRotationMillis) return false

        val requiredMillis = if (intervalMinutes > Long.MAX_VALUE / MILLIS_PER_MINUTE) {
            Long.MAX_VALUE
        } else {
            intervalMinutes * MILLIS_PER_MINUTE
        }
        return nowMillis - lastRotationMillis >= requiredMillis
    }

    private fun skipped(
        reason: RotationSkipReason,
        themeChanged: Boolean = false
    ) = RotationDecision(
        shouldRotate = false,
        themeChanged = themeChanged,
        skipReason = reason
    )
}
