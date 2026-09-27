package com.app.nosatmosphereeffect.helper

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Extracts an explicit, representative color trio for the wallpaper framework. */
internal object WallpaperColorExtractor {
    private const val MAX_INPUT_SIDE = 512
    private const val MIN_OEM_SATURATION = 0.40f
    private const val MIN_CHROMATIC_SATURATION = 0.08f
    private const val MIN_OEM_LIGHTNESS = 0.30f
    private const val MAX_OEM_LIGHTNESS = 0.70f

    fun extract(file: File): WallpaperColors? {
        val bitmap = decodeSampledBitmap(file) ?: return null
        return try {
            extract(bitmap, shouldSanitizeForOem())
        } finally {
            bitmap.recycle()
        }
    }

    internal fun extract(bitmap: Bitmap, sanitizeForOem: Boolean): WallpaperColors {
        val frameworkColors = WallpaperColors.fromBitmap(bitmap)
        val palette = Palette.from(bitmap)
            .maximumColorCount(24)
            .generate()

        val frameworkPrimary = frameworkColors.primaryColor.toArgb()
        val frameworkSecondary = frameworkColors.secondaryColor?.toArgb()
        val frameworkTertiary = frameworkColors.tertiaryColor?.toArgb()

        val rawPrimary = firstColor(
            palette.vibrantSwatch?.rgb,
            palette.dominantSwatch?.rgb,
            frameworkPrimary
        )
        val rawSecondary = firstDistinctColor(
            rawPrimary,
            palette.lightVibrantSwatch?.rgb,
            palette.mutedSwatch?.rgb,
            palette.darkMutedSwatch?.rgb,
            frameworkSecondary
        ) ?: colorVariant(rawPrimary, lighten = true)
        val rawTertiary = firstDistinctColor(
            rawPrimary,
            rawSecondary,
            palette.darkVibrantSwatch?.rgb,
            palette.lightMutedSwatch?.rgb,
            palette.darkMutedSwatch?.rgb,
            frameworkTertiary
        ) ?: colorVariant(rawPrimary, lighten = false)

        val referenceHue = mostChromaticHue(rawPrimary, rawSecondary, rawTertiary)
        val primary = sanitizeIfNeeded(rawPrimary, referenceHue, sanitizeForOem)
        val secondary = sanitizeIfNeeded(rawSecondary, referenceHue, sanitizeForOem)
        val tertiary = sanitizeIfNeeded(rawTertiary, referenceHue, sanitizeForOem)

        // Preserve the framework's readability hints while supplying Palette's
        // explicit colors. Launchers still receive the correct light/dark text advice.
        return WallpaperColors(
            Color.valueOf(primary),
            Color.valueOf(secondary),
            Color.valueOf(tertiary),
            frameworkColors.colorHints
        )
    }

    /**
     * One colour that stands for the whole wallpaper, for the clock: the hue
     * of everything in it, weighted by how much of the image each colour
     * covers and how colourful it is, at the image's overall colourfulness.
     *
     * Not [extract]'s primary colour. That is the most vibrant swatch — an
     * accent that may cover a sliver of the photo — and on some devices it is
     * pushed to a strong saturation for the system theme, which made the clock
     * darker and more saturated than the picture it sits on.
     */
    fun representativeColor(file: File): Int? {
        val bitmap = decodeSampledBitmap(file) ?: return null
        return try {
            val swatches = Palette.from(bitmap)
                .maximumColorCount(24)
                .generate()
                .swatches
                .map { it.rgb to it.population }
            representativeColor(swatches)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [representativeColor] for colours and their pixel counts. Pure — the
     * colour maths is done here rather than through ColorUtils — so it can be
     * tested off the device.
     */
    internal fun representativeColor(swatches: List<Pair<Int, Int>>): Int? {
        var total = 0.0
        var saturationSum = 0.0
        var hueX = 0.0
        var hueY = 0.0
        var chromaTotal = 0.0
        var strongest = -1.0
        var strongestHue = 0f
        for ((rgb, population) in swatches) {
            if (population <= 0) continue
            val (hue, saturation, lightness) = toHsl(rgb)
            val weight = population.toDouble()
            total += weight
            // Near-black and near-white carry no hue worth following, but they
            // are still part of how colourful the picture is overall.
            val chromatic = lightness in 0.08f..0.95f
            saturationSum += weight * (if (chromatic) saturation else 0f)
            if (!chromatic) continue
            val chroma = weight * saturation
            val angle = Math.toRadians(hue.toDouble())
            hueX += kotlin.math.cos(angle) * chroma
            hueY += kotlin.math.sin(angle) * chroma
            chromaTotal += chroma
            if (chroma > strongest) {
                strongest = chroma
                strongestHue = hue
            }
        }
        if (total <= 0.0) return null
        val saturation = (saturationSum / total).toFloat()
        if (chromaTotal <= 0.0 || saturation < GREY_SATURATION) {
            return fromHsl(0f, 0f, REPRESENTATIVE_LIGHTNESS)
        }
        // Colours pulling opposite ways cancel to a hue nobody sees in the
        // picture; then the one covering most of it decides.
        val agreement = kotlin.math.hypot(hueX, hueY) / chromaTotal
        val hue = if (agreement < MIN_HUE_AGREEMENT) {
            strongestHue
        } else {
            ((Math.toDegrees(kotlin.math.atan2(hueY, hueX)) + 360.0) % 360.0).toFloat()
        }
        return fromHsl(hue, saturation, REPRESENTATIVE_LIGHTNESS)
    }

    private fun toHsl(rgb: Int): Triple<Float, Float, Float> {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val maxValue = max(r, max(g, b))
        val minValue = min(r, min(g, b))
        val lightness = (maxValue + minValue) / 2f
        val delta = maxValue - minValue
        if (delta < 1e-6f) return Triple(0f, 0f, lightness)
        val saturation = delta / (1f - abs(2f * lightness - 1f))
        val hue = when (maxValue) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return Triple((hue + 360f) % 360f, saturation.coerceIn(0f, 1f), lightness)
    }

    private fun fromHsl(hue: Float, saturation: Float, lightness: Float): Int {
        val c = (1f - abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = lightness - c / 2f
        val (r, g, b) = when ((hue / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun channel(value: Float) = ((value + m) * 255f).roundToIntSafe().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun Float.roundToIntSafe(): Int = if (isFinite()) Math.round(this) else 0

    /** Below this overall saturation the picture counts as black and white. */
    private const val GREY_SATURATION = 0.08f
    /** How far the hues must agree for their average to mean anything. */
    private const val MIN_HUE_AGREEMENT = 0.35
    private const val REPRESENTATIVE_LIGHTNESS = 0.6f

    private fun decodeSampledBitmap(file: File): Bitmap? {
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_INPUT_SIDE) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    @ColorInt
    private fun sanitizeIfNeeded(
        @ColorInt color: Int,
        referenceHue: Float?,
        sanitizeForOem: Boolean
    ): Int {
        if (!sanitizeForOem) return color

        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        when {
            hsl[1] >= MIN_OEM_SATURATION -> Unit
            hsl[1] >= MIN_CHROMATIC_SATURATION -> hsl[1] = MIN_OEM_SATURATION
            referenceHue != null -> {
                hsl[0] = referenceHue
                hsl[1] = MIN_OEM_SATURATION
            }
            // Do not invent a hue for genuinely monochrome wallpapers.
            else -> Unit
        }
        hsl[2] = hsl[2].coerceIn(MIN_OEM_LIGHTNESS, MAX_OEM_LIGHTNESS)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun shouldSanitizeForOem(): Boolean {
        val deviceIdentity = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase(Locale.ROOT)
        return deviceIdentity.contains("samsung") || deviceIdentity.contains("infinix")
    }

    @ColorInt
    private fun firstColor(vararg colors: Int?): Int = colors.firstNotNullOf { it }

    @ColorInt
    private fun firstDistinctColor(@ColorInt reference: Int, vararg colors: Int?): Int? {
        return colors.firstOrNull { candidate ->
            candidate != null && colorsAreDistinct(reference, candidate)
        }
    }

    @ColorInt
    private fun firstDistinctColor(
        @ColorInt firstReference: Int,
        @ColorInt secondReference: Int,
        vararg colors: Int?
    ): Int? {
        return colors.firstOrNull { candidate ->
            candidate != null &&
                colorsAreDistinct(firstReference, candidate) &&
                colorsAreDistinct(secondReference, candidate)
        }
    }

    private fun colorsAreDistinct(@ColorInt first: Int, @ColorInt second: Int): Boolean {
        val firstHsl = FloatArray(3)
        val secondHsl = FloatArray(3)
        ColorUtils.colorToHSL(first, firstHsl)
        ColorUtils.colorToHSL(second, secondHsl)

        val directHueDistance = abs(firstHsl[0] - secondHsl[0])
        val hueDistance = min(directHueDistance, 360f - directHueDistance)
        return hueDistance >= 18f ||
            abs(firstHsl[1] - secondHsl[1]) >= 0.12f ||
            abs(firstHsl[2] - secondHsl[2]) >= 0.12f
    }

    private fun mostChromaticHue(vararg colors: Int): Float? {
        var bestHue: Float? = null
        var bestSaturation = MIN_CHROMATIC_SATURATION
        colors.forEach { color ->
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            if (hsl[1] >= bestSaturation) {
                bestHue = hsl[0]
                bestSaturation = hsl[1]
            }
        }
        return bestHue
    }

    @ColorInt
    private fun colorVariant(@ColorInt color: Int, lighten: Boolean): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[2] = if (lighten) {
            (hsl[2] + 0.18f).coerceAtMost(0.78f)
        } else {
            (hsl[2] - 0.18f).coerceAtLeast(0.22f)
        }
        return ColorUtils.HSLToColor(hsl)
    }
}
