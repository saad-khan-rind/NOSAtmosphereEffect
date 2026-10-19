package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

enum class AlwaysAppliedTarget(val storedValue: String) {
    HOME("home"),
    LOCK("lock"),
    BOTH("both");

    fun showsEffectOn(surface: WallpaperSurface): Boolean = when (this) {
        HOME -> surface == WallpaperSurface.HOME
        LOCK -> surface == WallpaperSurface.LOCK
        BOTH -> true
    }

    companion object {
        fun fromStoredValue(value: String?): AlwaysAppliedTarget =
            entries.firstOrNull { it.storedValue == value } ?: BOTH
    }
}

enum class WallpaperSurface {
    HOME,
    LOCK
}

data class WallpaperBehaviorSettings(
    val transitionsEnabled: Boolean = true,
    val alwaysAppliedTarget: AlwaysAppliedTarget = AlwaysAppliedTarget.BOTH,
    val wakeTransition: WakeTransitionSettings = WakeTransitionSettings(),
    /**
     * Playlists: how long after the screen turns off the next image waits.
     * Turned back on within it, nothing switches, so a quick lock and
     * unlock doesn't change the wallpaper. 0 switches at once.
     */
    val switchDelayMs: Int = 0
) {
    companion object {
        const val MAX_SWITCH_DELAY_MS = 3_000

        fun sanitizeSwitchDelay(ms: Int): Int = ms.coerceIn(0, MAX_SWITCH_DELAY_MS)
    }
}

data class EffectStateEndpoints(
    val originalProgress: Float,
    val appliedProgress: Float
)

object WallpaperBehaviorPolicy {
    const val TRANSITIONS_ENABLED_KEY = "effect_transitions_enabled"
    const val ALWAYS_APPLIED_TARGET_KEY = "always_applied_target"
    const val WAKE_ENABLED_KEY = "wake_transition_enabled"
    const val WAKE_STYLE_KEY = "wake_transition_style"
    const val WAKE_DURATION_KEY = "wake_transition_ms"
    const val WAKE_ZOOM_KEY = "wake_transition_zoom_percent"
    const val WAKE_STRETCH_INWARD_KEY = "wake_transition_stretch_inward"
    const val WAKE_STRETCH_KEY = "wake_transition_stretch_percent"
    const val WAKE_SHAKE_DIRECTION_KEY = "wake_transition_shake_direction"
    const val WAKE_SHAKE_KEY = "wake_transition_shake_percent"
    const val WAKE_SHAKE_COUNT_KEY = "wake_transition_shake_count"
    const val SWITCH_DELAY_KEY = "playlist_switch_delay_ms"

    fun resolveSurface(
        isHomeEngine: Boolean,
        isLockEngine: Boolean,
        isKeyguardLocked: Boolean
    ): WallpaperSurface = when {
        isHomeEngine && !isLockEngine -> WallpaperSurface.HOME
        isLockEngine && !isHomeEngine -> WallpaperSurface.LOCK
        isKeyguardLocked -> WallpaperSurface.LOCK
        else -> WallpaperSurface.HOME
    }
}

object EffectStatePolicy {
    fun endpoints(effectId: String?): EffectStateEndpoints = when (effectId) {
        // The effect shows at the start of these, the photo at the end.
        "HALFTONE_REVERSE", "VHS" -> EffectStateEndpoints(
            originalProgress = 1f,
            appliedProgress = 0f
        )
        "NEON" -> EffectStateEndpoints(
            originalProgress = 1f,
            appliedProgress = 0f
        )
        else -> EffectStateEndpoints(
            originalProgress = 0f,
            appliedProgress = 1f
        )
    }

    fun transitionProgress(effectId: String?, lockToHomeProgress: Float): Float {
        val progress = lockToHomeProgress
            .takeIf(Float::isFinite)
            ?.coerceIn(0f, 1f)
            ?: 0f
        return when (effectId) {
            "REVERSE", "FROSTED_REVERSE" -> 1f - progress
            "GLASS", "GLASS_REVERSE" -> GlassEffectPolicy.shaderProgress(
                progress,
                reverse = effectId == "GLASS_REVERSE"
            )
            "COLORFILL" -> 1f - progress
            else -> progress
        }
    }
}

object WallpaperBehaviorPreferences {
    const val PREFS_NAME = "wallpaper_behavior_prefs"
    private const val TAG = "WallpaperBehavior"

    fun read(context: Context): WallpaperBehaviorSettings {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WallpaperBehaviorSettings(
            transitionsEnabled = preferences.readBoolean(
                WallpaperBehaviorPolicy.TRANSITIONS_ENABLED_KEY,
                true
            ),
            alwaysAppliedTarget = AlwaysAppliedTarget.fromStoredValue(
                preferences.readStringSafely(
                    WallpaperBehaviorPolicy.ALWAYS_APPLIED_TARGET_KEY,
                    AlwaysAppliedTarget.BOTH.storedValue
                )
            ),
            wakeTransition = WakeTransitionSettings(
                enabled = preferences.readBoolean(WallpaperBehaviorPolicy.WAKE_ENABLED_KEY, false),
                style = WakeTransitionStyle.fromStoredValue(
                    preferences.readStringSafely(
                        WallpaperBehaviorPolicy.WAKE_STYLE_KEY,
                        WakeTransitionStyle.ZOOM.storedValue
                    )
                ),
                durationMs = WakeTransitionSettings.sanitizeDuration(
                    preferences.readInt(
                        WallpaperBehaviorPolicy.WAKE_DURATION_KEY,
                        WakeTransitionSettings.DEFAULT_DURATION_MS
                    )
                ),
                zoomPercent = WakeTransitionSettings.sanitizeZoom(
                    preferences.readInt(
                        WallpaperBehaviorPolicy.WAKE_ZOOM_KEY,
                        WakeTransitionSettings.DEFAULT_ZOOM_PERCENT
                    )
                ),
                stretchInward = preferences.readBoolean(WallpaperBehaviorPolicy.WAKE_STRETCH_INWARD_KEY, false),
                stretchPercent = WakeTransitionSettings.sanitizeStretch(
                    preferences.readInt(
                        WallpaperBehaviorPolicy.WAKE_STRETCH_KEY,
                        WakeTransitionSettings.DEFAULT_STRETCH_PERCENT
                    )
                ),
                shakeDirection = WakeShakeDirection.fromStoredValue(
                    preferences.readStringSafely(
                        WallpaperBehaviorPolicy.WAKE_SHAKE_DIRECTION_KEY,
                        WakeShakeDirection.SIDEWAYS.storedValue
                    )
                ),
                shakePercent = WakeTransitionSettings.sanitizeShake(
                    preferences.readInt(
                        WallpaperBehaviorPolicy.WAKE_SHAKE_KEY,
                        WakeTransitionSettings.DEFAULT_SHAKE_PERCENT
                    )
                ),
                shakeCount = WakeTransitionSettings.sanitizeShakeCount(
                    preferences.readInt(
                        WallpaperBehaviorPolicy.WAKE_SHAKE_COUNT_KEY,
                        WakeTransitionSettings.DEFAULT_SHAKE_COUNT
                    )
                )
            ),
            switchDelayMs = WallpaperBehaviorSettings.sanitizeSwitchDelay(
                preferences.readInt(WallpaperBehaviorPolicy.SWITCH_DELAY_KEY, 0)
            )
        )
    }

    fun write(context: Context, settings: WallpaperBehaviorSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(
                WallpaperBehaviorPolicy.TRANSITIONS_ENABLED_KEY,
                settings.transitionsEnabled
            )
            putString(
                WallpaperBehaviorPolicy.ALWAYS_APPLIED_TARGET_KEY,
                settings.alwaysAppliedTarget.storedValue
            )
            putBoolean(WallpaperBehaviorPolicy.WAKE_ENABLED_KEY, settings.wakeTransition.enabled)
            putString(WallpaperBehaviorPolicy.WAKE_STYLE_KEY, settings.wakeTransition.style.storedValue)
            putInt(
                WallpaperBehaviorPolicy.WAKE_DURATION_KEY,
                WakeTransitionSettings.sanitizeDuration(settings.wakeTransition.durationMs)
            )
            putInt(
                WallpaperBehaviorPolicy.WAKE_ZOOM_KEY,
                WakeTransitionSettings.sanitizeZoom(settings.wakeTransition.zoomPercent)
            )
            putBoolean(WallpaperBehaviorPolicy.WAKE_STRETCH_INWARD_KEY, settings.wakeTransition.stretchInward)
            putInt(
                WallpaperBehaviorPolicy.WAKE_STRETCH_KEY,
                WakeTransitionSettings.sanitizeStretch(settings.wakeTransition.stretchPercent)
            )
            putString(
                WallpaperBehaviorPolicy.WAKE_SHAKE_DIRECTION_KEY,
                settings.wakeTransition.shakeDirection.storedValue
            )
            putInt(
                WallpaperBehaviorPolicy.WAKE_SHAKE_KEY,
                WakeTransitionSettings.sanitizeShake(settings.wakeTransition.shakePercent)
            )
            putInt(
                WallpaperBehaviorPolicy.WAKE_SHAKE_COUNT_KEY,
                WakeTransitionSettings.sanitizeShakeCount(settings.wakeTransition.shakeCount)
            )
            putInt(
                WallpaperBehaviorPolicy.SWITCH_DELAY_KEY,
                WallpaperBehaviorSettings.sanitizeSwitchDelay(settings.switchDelayMs)
            )
        }
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(WallpaperBehaviorPolicy.TRANSITIONS_ENABLED_KEY)
            remove(WallpaperBehaviorPolicy.ALWAYS_APPLIED_TARGET_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_ENABLED_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_STYLE_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_DURATION_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_ZOOM_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_STRETCH_INWARD_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_STRETCH_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_SHAKE_DIRECTION_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_SHAKE_KEY)
            remove(WallpaperBehaviorPolicy.WAKE_SHAKE_COUNT_KEY)
            remove(WallpaperBehaviorPolicy.SWITCH_DELAY_KEY)
        }
    }

    private fun SharedPreferences.readBoolean(key: String, fallback: Boolean): Boolean {
        return try {
            getBoolean(key, fallback)
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Preference '$key' has the wrong type; using $fallback", failure)
            fallback
        }
    }

    private fun SharedPreferences.readInt(key: String, fallback: Int): Int {
        return try {
            getInt(key, fallback)
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Preference '$key' has the wrong type; using $fallback", failure)
            fallback
        }
    }

    private fun SharedPreferences.readStringSafely(key: String, fallback: String): String {
        return try {
            getString(key, fallback) ?: fallback
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Preference '$key' has the wrong type; using $fallback", failure)
            fallback
        }
    }
}
