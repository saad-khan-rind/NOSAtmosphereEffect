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
            showSeconds = preferences.readBoolean(
                AtmosphereClockPolicy.SECONDS_KEY,
                AtmosphereClockPolicy.DEFAULT_SECONDS
            ),
            animate = preferences.readBoolean(
                AtmosphereClockPolicy.ANIMATE_KEY,
                AtmosphereClockPolicy.DEFAULT_ANIMATE
            ),
            centerX = preferences.readFloat(
                AtmosphereClockPolicy.CENTER_X_KEY,
                AtmosphereClockPolicy.DEFAULT_CENTER_X
            ),
            top = preferences.readFloat(
                AtmosphereClockPolicy.TOP_KEY,
                AtmosphereClockPolicy.DEFAULT_TOP
            ),
            height = preferences.readFloat(
                AtmosphereClockPolicy.HEIGHT_KEY,
                AtmosphereClockPolicy.DEFAULT_HEIGHT
            ),
            widthScale = preferences.readFloat(
                AtmosphereClockPolicy.WIDTH_SCALE_KEY,
                AtmosphereClockPolicy.DEFAULT_WIDTH_SCALE
            ),
            heightScale = preferences.readFloat(
                AtmosphereClockPolicy.HEIGHT_SCALE_KEY,
                AtmosphereClockPolicy.DEFAULT_HEIGHT_SCALE
            ),
            opacity = preferences.readFloat(
                AtmosphereClockPolicy.OPACITY_KEY,
                AtmosphereClockPolicy.DEFAULT_OPACITY
            ),
            requestedColor = requestedColor,
            // Left unresolved on purpose: deriving the wallpaper tint decodes
            // an image and runs Palette, which must not happen wherever this
            // is called from. ClockRuntime folds the real colour in once it
            // has one, and until then the fallback shows.
            color = if (ClockPalette.isAuto(requestedColor)) {
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
