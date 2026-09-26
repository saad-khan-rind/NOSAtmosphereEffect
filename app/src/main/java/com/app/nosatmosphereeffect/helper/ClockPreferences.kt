package com.app.nosatmosphereeffect.helper

import android.content.SharedPreferences
import android.util.Log

/**
 * One place that turns stored preferences into a [ClockOverlayState].
 *
 * Every effect reads the same keys, so the clock keeps its position, style and
 * colour when the user switches effects — the alternative, per-effect keys,
 * would mean recalibrating the clock every time, which is the opposite of what
 * anyone wants from a wallpaper setting.
 *
 * The two things that DO vary per effect are resolved here rather than by the
 * caller: whether the clock is supported at all, and which side of the
 * transition it may appear on.
 */
object ClockPreferences {

    /**
     * [lockedProgress] and [unlockedProgress] come from the effect's wallpaper
     * service, which is the only thing that knows which end of its shader's
     * progress range is the lock screen.
     */
    fun read(
        preferences: SharedPreferences,
        effectId: String?,
        singleImageMode: Boolean,
        lockedProgress: Float,
        unlockedProgress: Float
    ): ClockOverlayState {
        val requestedColor = preferences.readInt(
            AtmosphereClockPolicy.COLOR_KEY,
            AtmosphereClockPolicy.DEFAULT_COLOR
        )
        val placement = readPlacement(preferences)
        val requestedScreen = ClockScreen.fromId(
            preferences.readString(
                AtmosphereClockPolicy.SCREEN_KEY,
                ClockScreenPolicy.defaultScreen(effectId).id
            )
        )
        return ClockOverlayState(
            enabled = AtmosphereClockPolicy.resolveEnabled(
                effectId = effectId,
                requested = preferences.readBoolean(
                    AtmosphereClockPolicy.ENABLED_KEY,
                    false
                ),
                singleImageMode = singleImageMode
            ),
            // Depth is stored globally but only composited by effects whose
            // display pass has a mask, so it is switched off here for the
            // rest rather than left on for a renderer that would ignore it.
            depthEnabled = AtmosphereClockPolicy.supportsDepth(effectId) &&
                preferences.readBoolean(
                    AtmosphereClockPolicy.DEPTH_KEY,
                    AtmosphereClockPolicy.DEFAULT_DEPTH
                ),
            styleId = preferences.readString(
                AtmosphereClockPolicy.STYLE_KEY,
                ClockStyle.DEFAULT.id
            ),
            showDate = preferences.readBoolean(
                AtmosphereClockPolicy.DATE_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE
            ),
            animate = preferences.readBoolean(
                AtmosphereClockPolicy.ANIMATE_KEY,
                AtmosphereClockPolicy.DEFAULT_ANIMATE
            ),
            centerX = placement.centerX,
            top = placement.top,
            height = placement.height,
            widthScale = placement.widthScale,
            dateCenterX = preferences.readFloat(
                AtmosphereClockPolicy.DATE_CENTER_X_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE_CENTER_X
            ),
            dateTop = preferences.readFloat(
                AtmosphereClockPolicy.DATE_TOP_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE_TOP
            ),
            dateHeight = preferences.readFloat(
                AtmosphereClockPolicy.DATE_HEIGHT_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE_HEIGHT
            ),
            dateWidthScale = preferences.readFloat(
                AtmosphereClockPolicy.DATE_WIDTH_SCALE_KEY,
                AtmosphereClockPolicy.DEFAULT_DATE_WIDTH_SCALE
            ),
            // Always 1: readPlacement has already folded any stored stretch
            // into the placement itself.
            heightScale = 1f,
            opacity = preferences.readFloat(
                AtmosphereClockPolicy.OPACITY_KEY,
                AtmosphereClockPolicy.DEFAULT_OPACITY
            ),
            frost = preferences.readFloat(
                AtmosphereClockPolicy.FROST_KEY,
                AtmosphereClockPolicy.DEFAULT_FROST
            ),
            weight = preferences.readFloat(
                AtmosphereClockPolicy.WEIGHT_KEY,
                AtmosphereClockPolicy.DEFAULT_WEIGHT
            ),
            requestedColor = requestedColor,
            // Left unresolved on purpose: deriving the wallpaper tint decodes
            // an image and runs Palette, which must not happen wherever this
            // is called from. ClockRuntime folds the real colour in once it
            // has one, and until then the fallback shows.
            color = if (ClockPalette.followsWallpaper(requestedColor)) {
                ClockPalette.DEFAULT_FALLBACK
            } else {
                requestedColor
            },
            hourFormat = preferences.readString(
                AtmosphereClockPolicy.HOUR_FORMAT_KEY,
                AtmosphereClockPolicy.DEFAULT_HOUR_FORMAT
            ),
            screenId = ClockScreenPolicy.resolveScreen(effectId, requestedScreen).id,
            lockedProgress = lockedProgress,
            unlockedProgress = unlockedProgress
        ).sanitized()
    }

    /**
     * The clock's stored placement, converted if it predates the change from
     * storing the face texture's rectangle to storing the digits' own box.
     *
     * The conversion is applied on every read rather than written back: this
     * runs in the wallpaper's process, where a write would race the settings
     * process, and it is idempotent — the calibration screen stamps the new
     * version the next time the user drags anything.
     */
    fun readPlacement(preferences: SharedPreferences): ClockPlacement {
        // The vertical stretch is folded in here rather than carried onwards.
        // It was the second of two size sliders, which the draggable box
        // replaced; leaving it as a separate multiplier meant the two backends
        // could apply it at different points in the same sum. The fold is
        // exact — the width is a ratio of the height, so dividing the ratio by
        // whatever multiplies the height leaves the shape untouched.
        val stretch = preferences.readFloat(
            AtmosphereClockPolicy.HEIGHT_SCALE_KEY,
            AtmosphereClockPolicy.DEFAULT_HEIGHT_SCALE
        ).takeIf { it.isFinite() && it > 0f } ?: 1f
        val stored = ClockPlacement(
            centerX = preferences.readFloat(
                AtmosphereClockPolicy.CENTER_X_KEY,
                AtmosphereClockPolicy.DEFAULT_CENTER_X
            ),
            top = preferences.readFloat(
                AtmosphereClockPolicy.TOP_KEY,
                AtmosphereClockPolicy.DEFAULT_TOP
            ),
            height = AtmosphereClockPolicy.sanitizeHeight(
                preferences.readFloat(
                    AtmosphereClockPolicy.HEIGHT_KEY,
                    AtmosphereClockPolicy.DEFAULT_HEIGHT
                ) * stretch
            ),
            widthScale = AtmosphereClockPolicy.sanitizeAxisScale(
                preferences.readFloat(
                    AtmosphereClockPolicy.WIDTH_SCALE_KEY,
                    AtmosphereClockPolicy.DEFAULT_WIDTH_SCALE
                ) / stretch
            )
        )
        // Nothing stored at all is a fresh install, not an old one: the
        // defaults already describe the digits, so converting them would
        // shrink the clock for someone who has never touched it.
        val untouched = !preferences.contains(AtmosphereClockPolicy.HEIGHT_KEY) &&
            !preferences.contains(AtmosphereClockPolicy.TOP_KEY)
        val version = if (untouched) {
            AtmosphereClockPolicy.GEOMETRY_VERSION
        } else {
            preferences.readInt(AtmosphereClockPolicy.GEOMETRY_VERSION_KEY, 1)
        }
        return if (version < AtmosphereClockPolicy.GEOMETRY_VERSION) {
            AtmosphereClockPolicy.migrateGeometry(stored)
        } else {
            stored
        }
    }

    private fun SharedPreferences.readBoolean(key: String, fallback: Boolean): Boolean {
        return try {
            getBoolean(key, fallback)
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Clock preference '$key' has the wrong type", failure)
            fallback
        }
    }

    private fun SharedPreferences.readFloat(key: String, fallback: Float): Float {
        return try {
            getFloat(key, fallback)
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Clock preference '$key' has the wrong type", failure)
            fallback
        }
    }

    private fun SharedPreferences.readInt(key: String, fallback: Int): Int {
        return try {
            getInt(key, fallback)
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Clock preference '$key' has the wrong type", failure)
            fallback
        }
    }

    private fun SharedPreferences.readString(key: String, fallback: String): String {
        return try {
            getString(key, fallback) ?: fallback
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Clock preference '$key' has the wrong type", failure)
            fallback
        }
    }

    private const val TAG = "ClockPreferences"
}
