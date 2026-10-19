package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRotationPolicyTest {
    @Test
    fun `single mode never rotates`() {
        val decision = decide(mode = PlaylistModeManager.MODE_SINGLE, playlistSize = 5)

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.SINGLE_MODE, decision.skipReason)
    }

    @Test
    fun `standard playlists ignore theme-change events`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            isThemeChange = true,
            playlistSize = 5
        )

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.NON_THEME_EVENT, decision.skipReason)
    }

    @Test
    fun `theme event rotates a single image when the active theme changed`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_THEME,
            isThemeChange = true,
            isNightMode = true,
            activeThemeState = 0,
            playlistSize = 1,
            intervalMinutes = Long.MAX_VALUE,
            lastRotationMillis = NOW
        )

        assertTrue(decision.shouldRotate)
        assertTrue(decision.themeChanged)
        assertNull(decision.skipReason)
    }

    @Test
    fun `duplicate theme event is ignored`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_THEME,
            isThemeChange = true,
            isNightMode = true,
            activeThemeState = 1,
            playlistSize = 3
        )

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.THEME_UNCHANGED, decision.skipReason)
    }

    @Test
    fun `periodic rotation needs at least two images`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 1
        )

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.ONLY_PLAYLIST_ITEM, decision.skipReason)
    }

    @Test
    fun `rotation occurs exactly at the configured interval boundary`() {
        val beforeBoundary = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 2,
            intervalMinutes = 10,
            lastRotationMillis = NOW - 10 * MINUTE + 1
        )
        val atBoundary = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 2,
            intervalMinutes = 10,
            lastRotationMillis = NOW - 10 * MINUTE
        )

        assertEquals(RotationSkipReason.INTERVAL_NOT_ELAPSED, beforeBoundary.skipReason)
        assertTrue(atBoundary.shouldRotate)
    }

    @Test
    fun `future timestamps and overflowing intervals do not trigger early rotation`() {
        val futureTimestamp = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 2,
            intervalMinutes = 1,
            lastRotationMillis = NOW + MINUTE
        )
        val overflowingInterval = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 2,
            intervalMinutes = Long.MAX_VALUE,
            lastRotationMillis = 1
        )

        assertEquals(RotationSkipReason.INTERVAL_NOT_ELAPSED, futureTimestamp.skipReason)
        assertEquals(RotationSkipReason.INTERVAL_NOT_ELAPSED, overflowingInterval.skipReason)
    }

    @Test
    fun `zero and negative intervals disable throttling`() {
        assertTrue(
            decide(
                mode = PlaylistModeManager.MODE_STANDARD,
                playlistSize = 2,
                intervalMinutes = 0,
                lastRotationMillis = NOW
            ).shouldRotate
        )
        assertTrue(
            decide(
                mode = PlaylistModeManager.MODE_STANDARD,
                playlistSize = 2,
                intervalMinutes = -5,
                lastRotationMillis = NOW
            ).shouldRotate
        )
    }

    @Test
    fun `shuffle shows every image once per round, never the last one twice in a row`() {
        val names = (1..500).map { "wallpaper_$it.jpg" }
        val random = kotlin.random.Random(7)
        var queue = emptyList<String>()
        var played = emptyList<String>()
        var last: String? = null
        var previousRound = emptyList<String>()
        repeat(3) {
            val shown = mutableListOf<String>()
            repeat(names.size) {
                val next = PlaylistRotationPolicy.shuffleQueue(queue, played, names, last, random)
                val pick = next.queue.first()
                assertTrue(pick != last)
                shown += pick
                queue = next.queue.drop(1)
                played = next.played + pick
                last = pick
            }
            assertEquals(names.toSet(), shown.toSet())
            assertEquals(names.size, shown.size)
            // The gap: the last quarter of a round stays out of the next one's first quarter.
            val gap = names.size / 4
            assertTrue(previousRound.takeLast(gap).none { it in shown.take(gap) })
            previousRound = shown
        }
    }

    @Test
    fun `new images join the round and removed ones drop out`() {
        val next = PlaylistRotationPolicy.shuffleQueue(
            queue = listOf("a.jpg", "gone.jpg"),
            played = listOf("b.jpg"),
            available = listOf("a.jpg", "b.jpg", "new.jpg"),
            lastUsedName = "b.jpg"
        )
        assertEquals(setOf("a.jpg", "new.jpg"), next.queue.toSet())
        assertEquals(listOf("b.jpg"), next.played)
    }

    @Test
    fun `a single image is still shown`() {
        val next = PlaylistRotationPolicy.shuffleQueue(emptyList(), listOf("a.jpg"), listOf("a.jpg"), "a.jpg")
        assertEquals(listOf("a.jpg"), next.queue)
    }

    // ---- Shuffle edge cases: whole rounds at every size that behaves differently ----

    @Test
    fun `every playlist size shows each image once per round with no back to back repeat`() {
        // 2 and 3 have no gap (a quarter rounds to 0); 4 is the first with one;
        // 500 is the size users reported; 1001 checks an odd size past it.
        listOf(2, 3, 4, 5, 7, 8, 10, 50, 500, 1001).forEach { size ->
            val names = names(size)
            repeat(SEEDS) { seed ->
                val rounds = simulate(names, rounds = 4, random = kotlin.random.Random(seed))
                rounds.forEachIndexed { index, round ->
                    assertEquals("size $size seed $seed round $index", names.size, round.size)
                    assertEquals("size $size seed $seed round $index", names.toSet(), round.toSet())
                }
                val all = rounds.flatten()
                all.zipWithNext().forEach { (a, b) ->
                    assertTrue("size $size seed $seed repeated $a", a != b)
                }
            }
        }
    }

    @Test
    fun `the gap keeps the end of a round out of the start of the next`() {
        listOf(4, 5, 8, 9, 13, 100, 500, 1001).forEach { size ->
            val gap = size / 4
            repeat(SEEDS) { seed ->
                val rounds = simulate(names(size), rounds = 4, random = kotlin.random.Random(seed))
                rounds.zipWithNext().forEach { (before, after) ->
                    val recent = before.takeLast(gap).toSet()
                    assertTrue("size $size seed $seed", after.take(gap).none { it in recent })
                }
                // So no image comes back within the gap anywhere in the sequence.
                val all = rounds.flatten()
                all.forEachIndexed { index, name ->
                    val window = all.subList(index + 1, minOf(all.size, index + 1 + gap))
                    assertTrue("size $size seed $seed $name back too soon", name !in window)
                }
            }
        }
    }

    @Test
    fun `two images alternate forever`() {
        val all = simulate(names(2), rounds = 10, random = kotlin.random.Random(3)).flatten()
        all.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
    }

    @Test
    fun `one image keeps being shown`() {
        val all = simulate(listOf("only.jpg"), rounds = 5, random = kotlin.random.Random(1)).flatten()
        assertEquals(List(5) { "only.jpg" }, all)
    }

    @Test
    fun `an empty playlist gives an empty queue`() {
        val next = PlaylistRotationPolicy.shuffleQueue(listOf("gone.jpg"), listOf("old.jpg"), emptyList(), "old.jpg")
        assertTrue(next.queue.isEmpty())
    }

    @Test
    fun `rounds are random, not the same order every time`() {
        val names = names(20)
        val orders = (0 until 10).map { seed -> simulate(names, rounds = 1, random = kotlin.random.Random(seed)).first() }
        assertTrue(orders.toSet().size > 1)
        // And not just the playlist's own order.
        assertTrue(orders.any { it != names })
    }

    @Test
    fun `images removed mid round are never picked and the round still ends`() {
        val names = names(10)
        var next = PlaylistRotationPolicy.shuffleQueue(emptyList(), emptyList(), names, null, kotlin.random.Random(5))
        val first = next.queue.first()
        val deleted = next.queue[1]
        val left = names.filterNot { it == deleted } // first was shown, deleted is gone
        next = PlaylistRotationPolicy.shuffleQueue(next.queue.drop(1), listOf(first), left, first)
        assertEquals(left.toSet() - first, next.queue.toSet())
        assertTrue(deleted !in next.queue)
        assertEquals(listOf(first), next.played)
    }

    @Test
    fun `when every queued image is deleted a fresh round starts from what is left`() {
        val next = PlaylistRotationPolicy.shuffleQueue(
            queue = listOf("a.jpg", "b.jpg"),
            played = listOf("c.jpg"),
            available = listOf("c.jpg", "d.jpg"),
            lastUsedName = "c.jpg"
        )
        // d.jpg is new, so it joins this round; c.jpg was already shown in it.
        assertEquals(listOf("d.jpg"), next.queue)
    }

    @Test
    fun `a whole new playlist starts a new round, not the old one`() {
        val next = PlaylistRotationPolicy.shuffleQueue(
            queue = listOf("old_1.jpg", "old_2.jpg"),
            played = listOf("old_3.jpg"),
            available = listOf("new_1.jpg", "new_2.jpg", "new_3.jpg"),
            lastUsedName = "old_3.jpg",
            random = kotlin.random.Random(2)
        )
        assertEquals(setOf("new_1.jpg", "new_2.jpg", "new_3.jpg"), next.queue.toSet())
        assertTrue(next.played.isEmpty())
    }

    @Test
    fun `stale and duplicated names are cleaned up`() {
        val next = PlaylistRotationPolicy.shuffleQueue(
            queue = listOf("a.jpg", "a.jpg", "ghost.jpg", "b.jpg"),
            played = listOf("c.jpg", "c.jpg", "gone.jpg"),
            available = listOf("a.jpg", "b.jpg", "c.jpg", "b.jpg"),
            lastUsedName = "c.jpg"
        )
        assertEquals(listOf("a.jpg", "b.jpg"), next.queue)
        assertEquals(listOf("c.jpg"), next.played)
    }

    @Test
    fun `the image showing now is never next, even when it is first in the saved queue`() {
        val next = PlaylistRotationPolicy.shuffleQueue(
            queue = listOf("a.jpg", "b.jpg", "c.jpg"),
            played = emptyList(),
            available = listOf("a.jpg", "b.jpg", "c.jpg"),
            lastUsedName = "a.jpg"
        )
        assertEquals("b.jpg", next.queue.first())
        assertEquals(setOf("a.jpg", "b.jpg", "c.jpg"), next.queue.toSet())
    }

    @Test
    fun `an image showing that is not in the playlist does not break the round`() {
        val next = PlaylistRotationPolicy.shuffleQueue(emptyList(), emptyList(), names(4), "not_in_playlist.jpg")
        assertEquals(names(4).toSet(), next.queue.toSet())
    }

    @Test
    fun `images added mid round all show before the round ends`() {
        val random = kotlin.random.Random(9)
        var available = names(10)
        var next = PlaylistRotationPolicy.shuffleQueue(emptyList(), emptyList(), available, null, random)
        val shown = mutableListOf<String>()
        var last: String? = null
        repeat(3) {
            val pick = next.queue.first(); shown += pick; last = pick
            next = PlaylistRotationPolicy.shuffleQueue(next.queue.drop(1), next.played + pick, available, last, random)
        }
        available = available + listOf("new_a.jpg", "new_b.jpg")
        next = PlaylistRotationPolicy.shuffleQueue(next.queue, next.played, available, last, random)
        while (next.queue.isNotEmpty() && shown.size < available.size) {
            val pick = next.queue.first(); shown += pick; last = pick
            next = PlaylistRotationPolicy.shuffleQueue(next.queue.drop(1), next.played + pick, available, last, random)
        }
        assertEquals(available.toSet(), shown.toSet())
        assertEquals(available.size, shown.size)
    }

    /** Plays [rounds] whole rounds the way PlaylistRotationController does, one image per rotation. */
    private fun simulate(names: List<String>, rounds: Int, random: kotlin.random.Random): List<List<String>> {
        var queue = emptyList<String>()
        var played = emptyList<String>()
        var last: String? = null
        return List(rounds) {
            List(names.size) {
                val next = PlaylistRotationPolicy.shuffleQueue(queue, played, names, last, random)
                val pick = next.queue.first()
                queue = next.queue.drop(1)
                played = next.played + pick
                last = pick
                pick
            }
        }
    }

    private fun names(count: Int) = (1..count).map { "wallpaper_$it.jpg" }

    @Test
    fun `invalid policy inputs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            decide(mode = "corrupt", playlistSize = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decide(mode = PlaylistModeManager.MODE_STANDARD, playlistSize = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decide(
                mode = PlaylistModeManager.MODE_STANDARD,
                playlistSize = 2,
                lastRotationMillis = -1
            )
        }
    }

    @Test
    fun `a deleted displayed image forces rotation before the interval elapses`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 1,
            intervalMinutes = 60,
            lastRotationMillis = NOW - MINUTE,
            forceRotate = true
        )

        assertTrue(decision.shouldRotate)
    }

    @Test
    fun `forced rotation still needs an image to rotate to`() {
        val decision = decide(
            mode = PlaylistModeManager.MODE_STANDARD,
            playlistSize = 0,
            forceRotate = true
        )

        assertFalse(decision.shouldRotate)
        assertEquals(RotationSkipReason.EMPTY_PLAYLIST, decision.skipReason)
    }

    private fun decide(
        mode: String,
        isThemeChange: Boolean = false,
        isNightMode: Boolean = false,
        activeThemeState: Int = -1,
        playlistSize: Int,
        intervalMinutes: Long = 0,
        lastRotationMillis: Long = 0,
        forceRotate: Boolean = false
    ): RotationDecision {
        return PlaylistRotationPolicy.decide(
            mode = mode,
            isThemeChange = isThemeChange,
            isNightMode = isNightMode,
            activeThemeState = activeThemeState,
            playlistSize = playlistSize,
            intervalMinutes = intervalMinutes,
            lastRotationMillis = lastRotationMillis,
            nowMillis = NOW,
            forceRotate = forceRotate
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val NOW = 10_000_000L
        const val SEEDS = 20
    }
}
