package com.app.nosatmosphereeffect.effect

import android.content.Context
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import com.app.nosatmosphereeffect.ui.model.EffectFamily
import kotlin.random.Random

/** Which way round Shuffle's effects run. */
enum class ShuffleDirection {
    /** The lock screen shows the photo, and the effect comes as the phone unlocks. */
    PHOTO_FIRST,
    /** The lock screen shows the effect, which clears to the photo. */
    EFFECT_FIRST,
    /** Either, picked with the effect. */
    RANDOM;

    companion object {
        const val PREFERENCE_KEY = "shuffle_direction"
        val DEFAULT = RANDOM
    }
}

/**
 * Shuffle: the wallpaper that shows a different effect each time the phone is
 * locked. Which effects it picks from (whole families, all of them until the
 * user says otherwise), which way round they run, and which one is showing
 * now. Duo is not one of them: it needs a second photo of its own.
 */
object ShuffleEffects {
    const val EFFECT_ID = "SHUFFLE"
    /** Shown when nothing else can be: every family switched off. */
    const val FALLBACK_EFFECT_ID = "ATMOSPHERE"

    private const val SETTINGS = "app_prefs"
    /** The families left out, so a family added by an update is in until switched off. */
    private const val EXCLUDED_KEY = "shuffle_excluded_families"
    private const val STATE = "wallpaper_prefs"
    private const val CURRENT_KEY = "shuffle_current_effect"
    /** Never shuffled in: itself, and Duo. */
    private val LEFT_OUT = setOf(EFFECT_ID, "DUO")

    /** The families Shuffle can pick from, in the catalog's order. */
    fun families(): List<EffectFamily> = EffectCatalog.families().filter { it.key !in LEFT_OUT }

    fun excludedFamilies(context: Context): Set<String> =
        runCatching { settings(context).getStringSet(EXCLUDED_KEY, emptySet()) }.getOrNull().orEmpty()

    fun setFamilyIncluded(context: Context, family: String, included: Boolean) {
        val excluded = excludedFamilies(context).toMutableSet()
        if (included) excluded -= family else excluded += family
        settings(context).edit().putStringSet(EXCLUDED_KEY, excluded).apply()
    }

    fun direction(context: Context): ShuffleDirection {
        val saved = runCatching { settings(context).getString(ShuffleDirection.PREFERENCE_KEY, null) }.getOrNull()
        return ShuffleDirection.entries.firstOrNull { it.name == saved } ?: ShuffleDirection.DEFAULT
    }

    fun setDirection(context: Context, direction: ShuffleDirection) {
        settings(context).edit().putString(ShuffleDirection.PREFERENCE_KEY, direction.name).apply()
    }

    /** The effects Shuffle picks from now. */
    fun candidates(context: Context): List<String> = candidates(
        effectIds = EffectCatalog.items.map { it.id },
        excludedFamilies = excludedFamilies(context),
        direction = direction(context)
    )

    internal fun candidates(
        effectIds: List<String>,
        excludedFamilies: Set<String>,
        direction: ShuffleDirection,
        startsFromPhoto: (String) -> Boolean = EffectCatalog::startsFromOriginalWallpaper
    ): List<String> = effectIds.filter { id ->
        id !in LEFT_OUT && EffectCatalog.family(id) !in excludedFamilies && when (direction) {
            ShuffleDirection.PHOTO_FIRST -> startsFromPhoto(id)
            ShuffleDirection.EFFECT_FIRST -> !startsFromPhoto(id)
            ShuffleDirection.RANDOM -> true
        }
    }.ifEmpty { listOf(FALLBACK_EFFECT_ID) }

    /** The effect showing now: the one saved, while it is still picked from; else a first pick. */
    fun current(context: Context): String {
        val candidates = candidates(context)
        val saved = state(context).getString(CURRENT_KEY, null)
        return saved?.takeIf { it in candidates } ?: candidates.random().also { save(context, it) }
    }

    /** Moves on to another effect and returns it; the same one when it is the only one. */
    fun next(context: Context): String =
        pick(candidates(context), current(context), Random).also { save(context, it) }

    /** Another of [candidates] than [current], when there is one. */
    internal fun pick(candidates: List<String>, current: String?, random: Random): String {
        val others = candidates.filter { it != current }
        return (others.ifEmpty { candidates }).random(random)
    }

    private fun save(context: Context, effectId: String) {
        state(context).edit().putString(CURRENT_KEY, effectId).apply()
    }

    private fun settings(context: Context) = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    private fun state(context: Context) = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
}
