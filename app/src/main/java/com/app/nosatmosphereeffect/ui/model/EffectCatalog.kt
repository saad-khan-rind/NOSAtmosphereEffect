package com.app.nosatmosphereeffect.ui.model

import androidx.annotation.StringRes
import com.app.nosatmosphereeffect.R
import com.app.nosatmosphereeffect.effect.AtmosphereGlassPolicy

/**
 * [title], [transition] and [description] are string resources, so they follow the phone's language.
 * [transition] may name the effect as %1$s, filled with the untranslated [name].
 */
data class EffectItem(
    val id: String,
    @StringRes val title: Int,
    @StringRes val transition: Int,
    @StringRes val description: Int,
    @StringRes val name: Int = title
)

/** One look in both directions; [reverse] is null when only one direction is offered. */
data class EffectFamily(
    val key: String,
    val forward: EffectItem,
    val reverse: EffectItem?
) {
    fun pick(reversed: Boolean): EffectItem = if (reversed) reverse ?: forward else forward
}

object EffectCatalog {
    private val originalFirstEffectIds = setOf(
        "ATMOSPHERE",
        "GLASS",
        "FROSTED",
        "HALFTONE",
        "COLOR_FILL_REVERSE",
        "CANVAS_SKETCH_REVERSE",
        "VHS_REVERSE",
        "ZOOM",
        "DUO"
    )

    val items = listOf(
        EffectItem(
            "ATMOSPHERE",
            R.string.effect_atmosphere_title,
            R.string.effect_atmosphere_transition,
            R.string.effect_atmosphere_description
        ),
        EffectItem(
            "ATMOSPHERE_REVERSE",
            R.string.effect_atmosphere_reverse_title,
            R.string.effect_atmosphere_reverse_transition,
            R.string.effect_atmosphere_reverse_description
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
            "COLOR_FILL",
            R.string.effect_color_fill_title,
            R.string.effect_color_fill_transition,
            R.string.effect_color_fill_description
        ),
        EffectItem(
            "COLOR_FILL_REVERSE",
            R.string.effect_color_fill_reverse_title,
            R.string.effect_color_fill_reverse_transition,
            R.string.effect_color_fill_reverse_description
        ),
        EffectItem(
            "CANVAS_SKETCH",
            R.string.effect_canvas_sketch_title,
            R.string.effect_canvas_sketch_transition,
            R.string.effect_canvas_sketch_description
        ),
        EffectItem(
            "CANVAS_SKETCH_REVERSE",
            R.string.effect_canvas_sketch_reverse_title,
            R.string.effect_canvas_sketch_reverse_transition,
            R.string.effect_canvas_sketch_reverse_description
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
            R.string.effect_halftone_description,
            R.string.halftone_group
        ),
        EffectItem(
            "HALFTONE_REVERSE",
            R.string.effect_halftone_reverse_title,
            R.string.effect_halftone_reverse_transition,
            R.string.effect_halftone_reverse_description,
            R.string.halftone_group
        ),
        EffectItem(
            "VHS",
            R.string.effect_vhs_title,
            R.string.effect_vhs_transition,
            R.string.effect_vhs_description
        ),
        EffectItem(
            "VHS_REVERSE",
            R.string.effect_vhs_reverse_title,
            R.string.effect_vhs_reverse_transition,
            R.string.effect_vhs_reverse_description
        ),
        EffectItem(
            "ZOOM",
            R.string.effect_zoom_title,
            R.string.effect_zoom_transition,
            R.string.effect_zoom_description
        ),
        EffectItem(
            "ZOOM_REVERSE",
            R.string.effect_zoom_reverse_title,
            R.string.effect_zoom_reverse_transition,
            R.string.effect_zoom_reverse_description
        ),
        EffectItem(
            "DUO",
            R.string.effect_duo_title,
            R.string.effect_duo_transition,
            R.string.effect_duo_description
        ),
        EffectItem(
            "SHUFFLE",
            R.string.effect_shuffle_title,
            R.string.effect_shuffle_transition,
            R.string.effect_shuffle_description
        )
    )

    fun find(id: String?): EffectItem = items.firstOrNull { it.id == id } ?: items.first()

    fun recommendedDurationMillis(id: String?): Long = when (id) {
        "ATMOSPHERE" -> 2500L
        "ATMOSPHERE_REVERSE" -> 1500L
        "GLASS", "GLASS_REVERSE" -> 1200L
        "FROSTED", "FROSTED_REVERSE" -> 500L
        "HALFTONE", "HALFTONE_REVERSE" -> 500L
        "VHS", "VHS_REVERSE" -> 900L
        "ZOOM", "ZOOM_REVERSE" -> 500L
        "DUO" -> 900L
        "COLOR_FILL", "COLOR_FILL_REVERSE" -> 1500L
        "CANVAS_SKETCH", "CANVAS_SKETCH_REVERSE" -> 1000L
        else -> 1000L
    }

    fun defaultDimness(id: String?): Float = when {
        id?.contains("HALFTONE") == true -> 0f
        id?.contains("VHS") == true -> 0f
        id?.contains("ZOOM") == true -> 0f
        id == "DUO" -> 0f
        id == "SHUFFLE" -> 0f
        id?.contains("COLOR_FILL") == true -> 0f
        id?.contains("CANVAS_SKETCH") == true -> 0f
        id?.contains("GLASS") == true -> 0f
        else -> 0.2f
    }

    fun isReverse(id: String): Boolean = id.endsWith("_REVERSE")

    fun startsFromOriginalWallpaper(id: String?): Boolean = id in originalFirstEffectIds

    fun supportsAtmosphereGlass(id: String?): Boolean =
        AtmosphereGlassPolicy.supportsEffect(id)

    /** Groups [effects] by [family], keeping catalog order; a lone reverse effect stands as its own forward. */
    fun families(effects: List<EffectItem> = items): List<EffectFamily> =
        effects.groupBy { family(it.id) }.map { (key, members) ->
            val forward = members.firstOrNull { !isReverse(it.id) }
            EffectFamily(
                key = key,
                forward = forward ?: members.first(),
                reverse = if (forward == null) null else members.firstOrNull { isReverse(it.id) }
            )
        }

    fun family(id: String): String = when {
        id.contains("FROSTED") -> "FROSTED"
        id.contains("HALFTONE") -> "HALFTONE"
        id.contains("VHS") -> "VHS"
        id.contains("ZOOM") -> "ZOOM"
        id == "DUO" -> "DUO"
        id == "SHUFFLE" -> "SHUFFLE"
        id.contains("COLOR_FILL") -> "COLOR_FILL"
        id.contains("CANVAS_SKETCH") -> "CANVAS_SKETCH"
        id.contains("GLASS") -> "GLASS"
        else -> "ATMOSPHERE"
    }
}
