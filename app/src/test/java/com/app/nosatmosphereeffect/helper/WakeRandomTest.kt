package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class WakeRandomTest {

    private fun picks(options: List<String>, count: Int, seed: Int): List<String> {
        val random = Random(seed)
        var queue = emptyList<String>()
        var played = emptyList<String>()
        var last: String? = null
        return List(count) {
            val (pick, nextQueue, nextPlayed) = WakeRandom.draw(queue, played, options, last, random)
            queue = nextQueue
            played = nextPlayed
            last = pick
            pick
        }
    }

    @Test
    fun `every style plays once a round and never twice in a row`() {
        val styles = WakeTransitionSettings.RANDOM_POOL.map { it.storedValue }
        repeat(20) { seed ->
            val run = picks(styles, styles.size * 10, seed)
            run.chunked(styles.size).forEach { round -> assertEquals(styles.toSet(), round.toSet()) }
            run.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        }
    }

    @Test
    fun `two directions alternate and three never repeat`() {
        repeat(20) { seed ->
            picks(listOf("outward", "inward"), 40, seed).zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
            val shakes = WakeShakeDirection.entries.map { it.storedValue }
            val run = picks(shakes, 60, seed)
            run.chunked(3).forEach { round -> assertEquals(shakes.toSet(), round.toSet()) }
            run.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        }
    }

    @Test
    fun `random's own settings start as each style's defaults`() {
        val wake = WakeTransitionSettings(style = WakeTransitionStyle.RANDOM)
        assertEquals(WakeTransitionStyle.entries - WakeTransitionStyle.RANDOM, WakeTransitionSettings.RANDOM_POOL)
        WakeTransitionSettings.RANDOM_POOL.forEach { style ->
            assertEquals(WakeTransitionSettings(style = style), wake.randomFor(style))
        }
        val zoom = WakeTransitionSettings(style = WakeTransitionStyle.ZOOM, zoomPercent = 80)
        assertEquals(80, wake.copy(random = mapOf(WakeTransitionStyle.ZOOM to zoom)).randomFor(WakeTransitionStyle.ZOOM).zoomPercent)
    }
}
