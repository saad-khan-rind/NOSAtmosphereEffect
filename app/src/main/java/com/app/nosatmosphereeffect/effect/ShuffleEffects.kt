package com.app.nosatmosphereeffect.effect

import android.content.Context
import com.app.nosatmosphereeffect.renderer.duo.DuoHomeImage
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import kotlin.random.Random

/**
 * Shuffle: the wallpaper that shows a different effect each time the phone is
 * locked. Which effects it picks from (whole families, both directions; all
 * of them until the user says otherwise), and which one is showing now.
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

    /** The families Shuffle can pick from, in the catalog's order: every one but itself. */
    fun families(): List<String> = EffectCatalog.families().map { it.key }.filter { it != EFFECT_ID }

    fun excludedFamilies(context: Context): Set<String> =
        runCatching {
            context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getStringSet(EXCLUDED_KEY, emptySet())
        }.getOrNull().orEmpty()

    fun setFamilyIncluded(context: Context, family: String, included: Boolean) {
        val excluded = excludedFamilies(context).toMutableSet()
        if (included) excluded -= family else excluded += family
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putStringSet(EXCLUDED_KEY, excluded).apply()
    }

    /** The effects Shuffle picks from now. Duo only once it has its second photo. */
    fun candidates(context: Context): List<String> = candidates(
        effectIds = EffectCatalog.items.map { it.id },
        excludedFamilies = excludedFamilies(context),
        duoReady = DuoHomeImage.exists(context)
    )

    internal fun candidates(effectIds: List<String>, excludedFamilies: Set<String>, duoReady: Boolean): List<String> =
        effectIds.filter { id ->
            id != EFFECT_ID && EffectCatalog.family(id) !in excludedFamilies && (id != "DUO" || duoReady)
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

    private fun state(context: Context) = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
}
