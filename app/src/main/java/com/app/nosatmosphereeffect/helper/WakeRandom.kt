package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.random.Random

/**
 * Random's pick for the next wake: a style, and for Stretch and Shake a
 * direction, each drawn like a playlist shuffle (PlaylistRotationPolicy):
 * everything once, in random order, before any repeats, never the same twice
 * in a row, and the end of one round kept out of the start of the next.
 */
internal object WakeRandom {

    fun next(context: Context, wake: WakeTransitionSettings): WakeTransitionSettings {
        val prefs = context.getSharedPreferences(WallpaperBehaviorPreferences.PREFS_NAME, Context.MODE_PRIVATE)
        val style = WakeTransitionStyle.fromStoredValue(
            prefs.draw(STYLE_KEY, WakeTransitionSettings.RANDOM_POOL.map { it.storedValue })
        )
        val chosen = wake.randomFor(style).copy(style = style, enabled = true)
        return when (style) {
            WakeTransitionStyle.STRETCH ->
                chosen.copy(stretchInward = prefs.draw(STRETCH_KEY, listOf(OUTWARD, INWARD)) == INWARD)
            WakeTransitionStyle.SHAKE -> chosen.copy(
                shakeDirection = WakeShakeDirection.fromStoredValue(
                    prefs.draw(SHAKE_KEY, WakeShakeDirection.entries.map { it.storedValue })
                )
            )
            else -> chosen
        }
    }

    /** The next of [options] and the shuffle left after it: (pick, queue, played). */
    fun draw(
        queue: List<String>,
        played: List<String>,
        options: List<String>,
        last: String?,
        random: Random = Random.Default
    ): Triple<String, List<String>, List<String>> {
        val shuffle = PlaylistRotationPolicy.shuffleQueue(queue, played, options, last, random)
        val pick = shuffle.queue.first()
        return Triple(pick, shuffle.queue.drop(1), shuffle.played + pick)
    }

    private fun SharedPreferences.draw(key: String, options: List<String>): String {
        val (pick, queue, played) = draw(
            queue = names(key + QUEUE),
            played = names(key + PLAYED),
            options = options,
            last = getString(key + LAST, null)
        )
        edit {
            putString(key + QUEUE, queue.joinToString(SEPARATOR))
            putString(key + PLAYED, played.joinToString(SEPARATOR))
            putString(key + LAST, pick)
        }
        return pick
    }

    private fun SharedPreferences.names(key: String): List<String> =
        runCatching { getString(key, null) }.getOrNull()
            ?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    private const val STYLE_KEY = "wake_shuffle_style"
    private const val STRETCH_KEY = "wake_shuffle_stretch"
    private const val SHAKE_KEY = "wake_shuffle_shake"
    private const val QUEUE = "_queue"
    private const val PLAYED = "_played"
    private const val LAST = "_last"
    private const val SEPARATOR = ","
    private const val OUTWARD = "outward"
    private const val INWARD = "inward"
}
