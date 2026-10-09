package com.app.nosatmosphereeffect.helper

import androidx.annotation.StringRes
import com.app.nosatmosphereeffect.R

/**
 * One drawn face: a family's variant, with the digits it draws.
 *
 * [digits] is null for the system faces (Default) and for [HANZI], which
 * draws Chinese numerals with the phone's own CJK font instead.
 */
internal enum class ClockDesign(
    val id: String,
    @StringRes val variantLabel: Int,
    val digits: DigitDesign?
) {
    DEFAULT("default", R.string.clock_font_default, null),

    TOWER_TALL(
        "tower_tall", R.string.clock_variant_tall,
        DigitDesign(
            width = 0.40f, height = 1.30f,
            pen = ClockPen.Mono(0.085f, roundEnds = false),
            bowl = Bowl.Box(corner = 1f),
            colonWidth = 0.22f
        )
    ),
    TOWER_SHORT(
        "tower_short", R.string.clock_variant_short,
        DigitDesign(
            width = 0.40f, height = 0.95f,
            pen = ClockPen.Mono(0.085f, roundEnds = false),
            bowl = Bowl.Box(corner = 1f),
            colonWidth = 0.22f
        )
    ),
    HEAVY(
        "heavy", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.60f, height = 0.74f,
            pen = ClockPen.Mono(0.17f, roundEnds = false),
            bowl = Bowl.Oval(2.4f),
            curvedTwo = true,
            colonWidth = 0.34f
        )
    ),
    NARROW(
        "narrow", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.38f, height = 0.84f,
            pen = ClockPen.Mono(0.13f),
            bowl = Bowl.Box(corner = 1f),
            colonWidth = 0.26f
        )
    ),
    GEOMETRIC_REGULAR(
        "geometric_regular", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.58f, height = 0.72f,
            pen = ClockPen.Mono(0.06f, roundEnds = false),
            sixTail = SixTail.DIAGONAL,
            curvedTwo = true
        )
    ),
    GEOMETRIC_BOLD(
        "geometric_bold", R.string.clock_variant_bold,
        DigitDesign(
            width = 0.60f, height = 0.72f,
            pen = ClockPen.Mono(0.14f, roundEnds = false),
            sixTail = SixTail.DIAGONAL,
            curvedTwo = true,
            colonWidth = 0.32f
        )
    ),
    SOFT(
        "soft", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.56f, height = 0.72f,
            pen = ClockPen.Mono(0.055f),
            sixTail = SixTail.DIAGONAL,
            curvedTwo = true
        )
    ),
    CHUNKY(
        "chunky", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.60f, height = 0.72f,
            pen = ClockPen.Mono(0.18f),
            bowl = Bowl.Oval(2.3f),
            curvedTwo = true,
            italicDegrees = 4f,
            colonWidth = 0.34f
        )
    ),
    BUBBLE(
        "bubble", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.60f, height = 0.72f,
            pen = ClockPen.Mono(0.19f),
            openFour = true,
            wobbleDegrees = 3f,
            colonWidth = 0.34f
        )
    ),
    TECHNO(
        "techno", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.66f, height = 0.62f,
            pen = ClockPen.Mono(0.085f),
            bowl = Bowl.Box(corner = 0.45f),
            openFour = true,
            boxyTwo = true,
            oneFlag = false
        )
    ),
    BLOCK(
        "block", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.64f, height = 0.64f,
            pen = ClockPen.Mono(0.17f, roundEnds = false, sharpCorners = true),
            bowl = Bowl.Box(corner = 0.12f, exponent = 4f),
            oneFlag = false,
            openFour = true,
            boxyTwo = true,
            squareDots = true,
            colonWidth = 0.32f
        )
    ),
    WIREFRAME(
        "wireframe", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.62f, height = 0.78f,
            pen = ClockPen.Mono(0.02f, roundEnds = false, sharpCorners = true),
            bowl = Bowl.Box(corner = 0f),
            slashedZero = true,
            openFour = true,
            boxyTwo = true,
            sixTail = SixTail.DIAGONAL,
            squareDots = true,
            colonWidth = 0.2f
        )
    ),
    DOTS(
        "dots", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.60f, height = 0.84f,
            pen = ClockPen.Dots(pitch = 0.12f),
            colonWidth = 0.2f
        )
    ),
    NEON(
        "neon", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.64f, height = 0.72f,
            pen = ClockPen.Lines(0.17f, count = 3),
            bowl = Bowl.Box(corner = 1f),
            oneFlag = false,
            colonWidth = 0.28f
        )
    ),
    BRUSH(
        "brush", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.56f, height = 0.72f,
            pen = ClockPen.Nib(thick = 0.17f, thin = 0.06f, angleDegrees = 35f),
            sixTail = SixTail.DIAGONAL,
            curvedTwo = true,
            italicDegrees = 14f,
            sideBearing = 0.1f,
            colonWidth = 0.34f
        )
    ),
    DIDONE_LIGHT(
        "didone_light", R.string.clock_variant_light,
        DigitDesign(
            width = 0.54f, height = 0.72f,
            pen = ClockPen.Nib(thick = 0.075f, thin = 0.012f),
            curvedTwo = true,
            balls = true,
            serifs = true
        )
    ),
    DIDONE_HEAVY(
        "didone_heavy", R.string.clock_variant_heavy,
        DigitDesign(
            width = 0.62f, height = 0.72f,
            pen = ClockPen.Nib(thick = 0.19f, thin = 0.014f),
            curvedTwo = true,
            balls = true,
            serifs = true,
            colonWidth = 0.32f
        )
    ),
    SERIF_REGULAR(
        "serif_regular", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.56f, height = 0.72f,
            pen = ClockPen.Nib(thick = 0.085f, thin = 0.03f, angleDegrees = 12f),
            bowl = Bowl.Oval(2.2f),
            curvedTwo = true,
            balls = true,
            serifs = true
        )
    ),
    SERIF_BOLD(
        "serif_bold", R.string.clock_variant_bold,
        DigitDesign(
            width = 0.58f, height = 0.72f,
            pen = ClockPen.Nib(thick = 0.15f, thin = 0.035f, angleDegrees = 12f),
            bowl = Bowl.Oval(2.2f),
            curvedTwo = true,
            balls = true,
            serifs = true,
            colonWidth = 0.32f
        )
    ),
    POSTER(
        "poster", R.string.clock_variant_regular,
        DigitDesign(
            width = 0.92f, height = 0.66f,
            pen = ClockPen.Nib(thick = 0.27f, thin = 0.03f),
            curvedTwo = true,
            balls = true,
            serifs = true,
            colonWidth = 0.38f
        )
    ),
    HANZI("hanzi", R.string.clock_variant_regular, null);

    companion object {
        fun fromId(id: String?): ClockDesign = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Families group a face's variants, the way effects group a look with its
 * reverse: one card per family, the variant chosen with a toggle.
 */
internal enum class ClockFontFamily(@StringRes val label: Int, val variants: List<ClockDesign>) {
    DEFAULT(R.string.clock_font_default, listOf(ClockDesign.DEFAULT)),
    TOWER(R.string.clock_font_tower, listOf(ClockDesign.TOWER_TALL, ClockDesign.TOWER_SHORT)),
    HEAVY(R.string.clock_font_heavy, listOf(ClockDesign.HEAVY)),
    NARROW(R.string.clock_font_narrow, listOf(ClockDesign.NARROW)),
    GEOMETRIC(R.string.clock_font_geometric, listOf(ClockDesign.GEOMETRIC_REGULAR, ClockDesign.GEOMETRIC_BOLD)),
    SOFT(R.string.clock_font_soft, listOf(ClockDesign.SOFT)),
    CHUNKY(R.string.clock_font_chunky, listOf(ClockDesign.CHUNKY)),
    BUBBLE(R.string.clock_font_bubble, listOf(ClockDesign.BUBBLE)),
    TECHNO(R.string.clock_font_techno, listOf(ClockDesign.TECHNO)),
    BLOCK(R.string.clock_font_block, listOf(ClockDesign.BLOCK)),
    WIREFRAME(R.string.clock_font_wireframe, listOf(ClockDesign.WIREFRAME)),
    DOTS(R.string.clock_font_dots, listOf(ClockDesign.DOTS)),
    NEON(R.string.clock_font_neon, listOf(ClockDesign.NEON)),
    BRUSH(R.string.clock_font_brush, listOf(ClockDesign.BRUSH)),
    DIDONE(R.string.clock_font_didone, listOf(ClockDesign.DIDONE_LIGHT, ClockDesign.DIDONE_HEAVY)),
    SERIF(R.string.clock_font_serif, listOf(ClockDesign.SERIF_REGULAR, ClockDesign.SERIF_BOLD)),
    POSTER(R.string.clock_font_poster, listOf(ClockDesign.POSTER)),
    HANZI(R.string.clock_font_hanzi, listOf(ClockDesign.HANZI));

    companion object {
        fun of(design: ClockDesign): ClockFontFamily = entries.first { design in it.variants }
    }
}

/**
 * How the clock's digits are drawn: which face the Normal look uses, and
 * whether any look shows its colon. Stored as one string, "tower_tall" or
 * "tower_tall:nocolon".
 */
internal data class ClockFont(val design: ClockDesign, val showColon: Boolean = true) {
    val id: String get() = if (showColon) design.id else design.id + NO_COLON

    val isDefault: Boolean get() = design == ClockDesign.DEFAULT

    companion object {
        private const val NO_COLON = ":nocolon"
        val DEFAULT = ClockFont(ClockDesign.DEFAULT)

        fun fromId(id: String?): ClockFont {
            if (id == null) return DEFAULT
            val hidden = id.endsWith(NO_COLON)
            return ClockFont(ClockDesign.fromId(id.removeSuffix(NO_COLON)), showColon = !hidden)
        }
    }
}
