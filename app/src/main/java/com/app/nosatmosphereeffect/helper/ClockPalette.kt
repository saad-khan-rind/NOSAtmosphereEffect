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

    /**
     * Sentinel for the Adaptive face's own colour mode: each digit a pale tint
     * of what is behind it, varying down the digit. Fully transparent, so it
     * can never be mistaken for a colour someone picked.
     *
     * Every face that is not the Adaptive one treats it exactly like [AUTO] —
     * one colour for the whole clock, derived from the wallpaper — so
     * switching styles never leaves a clock with no colour.
     */
    const val ADAPTIVE = 1

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
        if (!followsWallpaper(stored)) return opaque(stored)
        return opaque(autoColor ?: DEFAULT_FALLBACK)
    }

    fun isAuto(stored: Int): Boolean = stored == AUTO

    fun isAdaptive(stored: Int): Boolean = stored == ADAPTIVE

    /** True for either wallpaper-derived mode — anything but a picked colour. */
    fun followsWallpaper(stored: Int): Boolean = stored == AUTO || stored == ADAPTIVE

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

        // The whole picture's colour rather than its most vibrant accent: the
        // clock sits on all of it.
        val source = try {
            WallpaperColorExtractor.representativeColor(file)
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null

        val conditioned = condition(source)
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
     * The clock's colour for a wallpaper whose overall colour is [source]:
     * its hue, at a fixed saturation and lightness.
     *
     * The way One UI colours its own clock, measured from its lock screen: a
     * near-black-and-white photo with a faint blue cast gets a clearly light
     * blue clock, not grey. Only the hue comes from the picture — its
     * strength does not, so a faint tint and a vivid one give an equally
     * readable clock. A picture with no hue at all gets a light neutral grey.
     */
    @ColorInt
    fun condition(@ColorInt source: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(source, hsl)
        if (hsl[1] < MIN_SOURCE_SATURATION) {
            return opaque(ColorUtils.HSLToColor(floatArrayOf(0f, 0f, TARGET_LIGHTNESS)))
        }
        hsl[1] = TARGET_SATURATION
        hsl[2] = TARGET_LIGHTNESS
        return opaque(ColorUtils.HSLToColor(hsl))
    }

    @ColorInt
    private fun opaque(@ColorInt color: Int): Int = color or (0xFF shl 24)

    private val cacheLock = Any()
    private var cachedStamp: Long = Long.MIN_VALUE
    @ColorInt private var cachedColor: Int? = null

    /** Below this the picture has no hue worth following. */
    private const val MIN_SOURCE_SATURATION = 0.01f
    private const val TARGET_SATURATION = 0.43f
    private const val TARGET_LIGHTNESS = 0.78f
}
