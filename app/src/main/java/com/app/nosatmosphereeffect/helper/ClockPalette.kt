package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import java.io.File

/**
 * Colour handling for the wallpaper clock.
 *
 * ## Why "auto" is not just the extracted colour
 *
 * The clock is drawn over an arbitrary photo, so a raw dominant colour is
 * often unreadable — a dark navy wallpaper yields a dark navy clock. [condition]
 * keeps the extracted *hue* (which is what makes it feel part of the theme)
 * and forces saturation and lightness into a band that stays legible against
 * whatever is behind it. The result reads as a tinted white rather than a
 * flat white, which is the effect the system clock gets on most launchers.
 *
 * The same conditioning is deliberately NOT applied to a colour the user
 * picked by hand: if someone chooses deep red, they get deep red.
 */
object ClockPalette {

    /** Sentinel stored in [AtmosphereClockPolicy.COLOR_KEY] for auto mode. */
    const val AUTO = 0

    const val DEFAULT_FALLBACK: Int = Color.WHITE

    /**
     * Curated swatches offered in the picker. Chosen to stay readable at the
     * clock's typical size over both light and dark photos — nothing below
     * roughly 70% lightness, which is why there are no deep tones here. The
     * custom picker exists for anyone who wants to go outside this range.
     */
    val PRESETS: List<Swatch> = listOf(
        Swatch("White", 0xFFFFFFFF.toInt()),
        Swatch("Warm white", 0xFFFFF2E0.toInt()),
        Swatch("Cool white", 0xFFE8F1FF.toInt()),
        Swatch("Sand", 0xFFF2DCB3.toInt()),
        Swatch("Blush", 0xFFFFD3D8.toInt()),
        Swatch("Coral", 0xFFFFB4A2.toInt()),
        Swatch("Amber", 0xFFFFD479.toInt()),
        Swatch("Mint", 0xFFB8EBD0.toInt()),
        Swatch("Sky", 0xFFA8D8FF.toInt()),
        Swatch("Periwinkle", 0xFFC3C8FF.toInt()),
        Swatch("Lilac", 0xFFE0C3FF.toInt()),
        Swatch("Slate", 0xFFBFC7D1.toInt())
    )

    data class Swatch(val label: String, @ColorInt val color: Int)

    /**
     * Resolves the colour the face should be drawn in.
     *
     * [stored] is the raw preference value; [AUTO] means "follow the
     * wallpaper". Returns [DEFAULT_FALLBACK] when auto is requested but no
     * wallpaper colour is available yet — better a white clock for one frame
     * than no clock.
     */
    @ColorInt
    fun resolve(stored: Int, @ColorInt autoColor: Int?): Int {
        if (stored != AUTO) return opaque(stored)
        return opaque(autoColor ?: DEFAULT_FALLBACK)
    }

    fun isAuto(stored: Int): Boolean = stored == AUTO

    /**
     * Derives the auto colour from the currently applied wallpaper.
     *
     * Reads the same `files/wallpaper.jpg` the rest of the app treats as "the
     * applied image". Returns null when there is no wallpaper yet or it
     * cannot be decoded; callers fall back to white.
     *
     * Does real I/O and Palette work — call it off the main thread. Results
     * are cached against the file's modification time so a playlist rotation
     * re-derives but a settings change does not.
     */
    @ColorInt
    fun autoColorFor(context: Context): Int? {
        val file = File(context.applicationContext.filesDir, "wallpaper.jpg")
        if (!file.exists()) return null
        val stamp = file.lastModified()

        synchronized(cacheLock) {
            if (stamp == cachedStamp && cachedColor != null) return cachedColor
        }

        val extracted = try {
            WallpaperColorExtractor.extract(file)
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null

        val conditioned = condition(extracted.primaryColor.toArgb())
        synchronized(cacheLock) {
            cachedStamp = stamp
            cachedColor = conditioned
        }
        return conditioned
    }

    /** Drops any cached auto colour; call when the wallpaper is replaced. */
    fun invalidateAutoColor() {
        synchronized(cacheLock) {
            cachedStamp = Long.MIN_VALUE
            cachedColor = null
        }
    }

    /**
     * Keeps the hue, pulls saturation and lightness into a legible band.
     *
     * A nearly-grey source (saturation under [MIN_SOURCE_SATURATION]) has no
     * meaningful hue to preserve, so it becomes plain white instead of a
     * muddy off-grey.
     */
    @ColorInt
    fun condition(@ColorInt source: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(source, hsl)
        if (hsl[1] < MIN_SOURCE_SATURATION) return DEFAULT_FALLBACK
        hsl[1] = hsl[1].coerceIn(MIN_TINT_SATURATION, MAX_TINT_SATURATION)
        hsl[2] = TARGET_LIGHTNESS
        return opaque(ColorUtils.HSLToColor(hsl))
    }

    @ColorInt
    private fun opaque(@ColorInt color: Int): Int = color or (0xFF shl 24)

    private val cacheLock = Any()
    private var cachedStamp: Long = Long.MIN_VALUE
    @ColorInt private var cachedColor: Int? = null

    private const val MIN_SOURCE_SATURATION = 0.10f
    private const val MIN_TINT_SATURATION = 0.18f
    private const val MAX_TINT_SATURATION = 0.42f
    private const val TARGET_LIGHTNESS = 0.90f
}
