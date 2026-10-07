package com.app.nosatmosphereeffect.ui.model

import androidx.annotation.StringRes
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.helper.AtmosphereGlassPolicy

/** [title], [transition] and [description] are string resources, so they follow the phone's language. */
data class EffectItem(
    val id: String,
    @StringRes val title: Int,
    @StringRes val transition: Int,
    @StringRes val description: Int
)

object EffectCatalog {
    private val originalFirstEffectIds = setOf(
        "ORIGINAL",
        "GLASS",
        "FROSTED",
        "HALFTONE",
        "COLORFILL_REVERSE",
        "NEON_REVERSE"
    )

    val items = listOf(
        EffectItem(
            "ORIGINAL",
            R.string.effect_original_title,
            R.string.effect_original_transition,
            R.string.effect_original_description
        ),
        EffectItem(
            "REVERSE",
            R.string.effect_reverse_title,
            R.string.effect_reverse_transition,
            R.string.effect_reverse_description
        ),
        EffectItem(
            "GLASS",
            R.string.effect_glass_title,
            R.string.effect_glass_transition,
            R.string.effect_glass_description
        ),
        EffectItem(
            "GLASS_REVERSE",
            R.string.effect_glass_reverse_title,
            R.string.effect_glass_reverse_transition,
            R.string.effect_glass_reverse_description
        ),
        EffectItem(
            "COLORFILL",
            R.string.effect_colorfill_title,
            R.string.effect_colorfill_transition,
            R.string.effect_colorfill_description
        ),
        EffectItem(
            "COLORFILL_REVERSE",
            R.string.effect_colorfill_reverse_title,
            R.string.effect_colorfill_reverse_transition,
            R.string.effect_colorfill_reverse_description
        ),
        EffectItem(
            "NEON",
            R.string.effect_neon_title,
            R.string.effect_neon_transition,
            R.string.effect_neon_description
        ),
        EffectItem(
            "NEON_REVERSE",
            R.string.effect_neon_reverse_title,
            R.string.effect_neon_reverse_transition,
            R.string.effect_neon_reverse_description
        ),
        EffectItem(
            "FROSTED",
            R.string.effect_frosted_title,
            R.string.effect_frosted_transition,
            R.string.effect_frosted_description
        ),
        EffectItem(
            "FROSTED_REVERSE",
            R.string.effect_frosted_reverse_title,
            R.string.effect_frosted_reverse_transition,
            R.string.effect_frosted_reverse_description
        ),
        EffectItem(
            "HALFTONE",
            R.string.effect_halftone_title,
            R.string.effect_halftone_transition,
            R.string.effect_halftone_description
        ),
        EffectItem(
            "HALFTONE_REVERSE",
            R.string.effect_halftone_reverse_title,
            R.string.effect_halftone_reverse_transition,
            R.string.effect_halftone_reverse_description
        )
    )

    fun find(id: String?): EffectItem = items.firstOrNull { it.id == id } ?: items.first()

    fun recommendedDurationMillis(id: String?): Long = when (id) {
        "ORIGINAL" -> 2500L
        "REVERSE" -> 1500L
        "GLASS", "GLASS_REVERSE" -> 1200L
        "FROSTED", "FROSTED_REVERSE" -> 500L
        "HALFTONE", "HALFTONE_REVERSE" -> 500L
        "COLORFILL", "COLORFILL_REVERSE" -> 1500L
        "NEON", "NEON_REVERSE" -> 1000L
        else -> 1000L
    }

    fun defaultDimness(id: String?): Float = when {
        id?.contains("HALFTONE") == true -> 0f
        id?.contains("COLORFILL") == true -> 0f
        id?.contains("NEON") == true -> 0f
        id?.contains("GLASS") == true -> 0f
        else -> 0.2f
    }

    fun isReverse(id: String): Boolean = id.endsWith("_REVERSE") || id == "REVERSE"

    fun startsFromOriginalWallpaper(id: String?): Boolean = id in originalFirstEffectIds

    fun supportsAtmosphereGlass(id: String?): Boolean =
        AtmosphereGlassPolicy.supportsEffect(id)

    fun family(id: String): String = when {
        id.contains("FROSTED") -> "FROSTED"
        id.contains("HALFTONE") -> "HALFTONE"
        id.contains("COLORFILL") -> "COLORFILL"
        id.contains("NEON") -> "CANVAS"
        id.contains("GLASS") -> "GLASS"
        else -> "ATMOSPHERE"
    }
}
