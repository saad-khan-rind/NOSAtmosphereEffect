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
    /** The wake transition's keys are this followed by [WAKE_KEYS]; Random's styles have their own prefix. */
    const val WAKE_PREFIX = "wake_transition_"
    fun randomWakePrefix(style: WakeTransitionStyle) = "wake_random_${style.storedValue}_"
    val WAKE_KEYS = listOf(
        "enabled", "style", "ms", "zoom_percent", "stretch_inward", "stretch_percent",
        "shake_direction", "shake_percent", "shake_count"
    )
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
            wakeTransition = preferences.readWake(WallpaperBehaviorPolicy.WAKE_PREFIX).copy(
                random = WakeTransitionSettings.RANDOM_POOL.associateWith { style ->
                    preferences.readWake(WallpaperBehaviorPolicy.randomWakePrefix(style)).copy(style = style)
                }
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
            putWake(WallpaperBehaviorPolicy.WAKE_PREFIX, settings.wakeTransition)
            WakeTransitionSettings.RANDOM_POOL.forEach { style ->
                putWake(WallpaperBehaviorPolicy.randomWakePrefix(style), settings.wakeTransition.randomFor(style))
            }
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
            val prefixes = listOf(WallpaperBehaviorPolicy.WAKE_PREFIX) +
                WakeTransitionSettings.RANDOM_POOL.map(WallpaperBehaviorPolicy::randomWakePrefix)
            prefixes.forEach { prefix -> WallpaperBehaviorPolicy.WAKE_KEYS.forEach { remove(prefix + it) } }
            remove(WallpaperBehaviorPolicy.SWITCH_DELAY_KEY)
        }
    }

    private fun SharedPreferences.readWake(prefix: String) = WakeTransitionSettings(
        enabled = readBoolean(prefix + "enabled", false),
        style = WakeTransitionStyle.fromStoredValue(
            readStringSafely(prefix + "style", WakeTransitionStyle.ZOOM.storedValue)
        ),
        durationMs = WakeTransitionSettings.sanitizeDuration(
            readInt(prefix + "ms", WakeTransitionSettings.DEFAULT_DURATION_MS)
        ),
        zoomPercent = WakeTransitionSettings.sanitizeZoom(
            readInt(prefix + "zoom_percent", WakeTransitionSettings.DEFAULT_ZOOM_PERCENT)
        ),
        stretchInward = readBoolean(prefix + "stretch_inward", false),
        stretchPercent = WakeTransitionSettings.sanitizeStretch(
            readInt(prefix + "stretch_percent", WakeTransitionSettings.DEFAULT_STRETCH_PERCENT)
        ),
        shakeDirection = WakeShakeDirection.fromStoredValue(
            readStringSafely(prefix + "shake_direction", WakeShakeDirection.SIDEWAYS.storedValue)
        ),
        shakePercent = WakeTransitionSettings.sanitizeShake(
            readInt(prefix + "shake_percent", WakeTransitionSettings.DEFAULT_SHAKE_PERCENT)
        ),
        shakeCount = WakeTransitionSettings.sanitizeShakeCount(
            readInt(prefix + "shake_count", WakeTransitionSettings.DEFAULT_SHAKE_COUNT)
        )
    )

    private fun SharedPreferences.Editor.putWake(prefix: String, wake: WakeTransitionSettings) {
        putBoolean(prefix + "enabled", wake.enabled)
        putString(prefix + "style", wake.style.storedValue)
        putInt(prefix + "ms", WakeTransitionSettings.sanitizeDuration(wake.durationMs))
        putInt(prefix + "zoom_percent", WakeTransitionSettings.sanitizeZoom(wake.zoomPercent))
        putBoolean(prefix + "stretch_inward", wake.stretchInward)
        putInt(prefix + "stretch_percent", WakeTransitionSettings.sanitizeStretch(wake.stretchPercent))
        putString(prefix + "shake_direction", wake.shakeDirection.storedValue)
        putInt(prefix + "shake_percent", WakeTransitionSettings.sanitizeShake(wake.shakePercent))
        putInt(prefix + "shake_count", WakeTransitionSettings.sanitizeShakeCount(wake.shakeCount))
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
