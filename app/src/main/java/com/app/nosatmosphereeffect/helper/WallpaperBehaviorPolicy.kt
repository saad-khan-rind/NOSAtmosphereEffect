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
    val alwaysAppliedTarget: AlwaysAppliedTarget = AlwaysAppliedTarget.BOTH
)

data class EffectStateEndpoints(
    val originalProgress: Float,
    val appliedProgress: Float
)

object WallpaperBehaviorPolicy {
    const val TRANSITIONS_ENABLED_KEY = "effect_transitions_enabled"
    const val ALWAYS_APPLIED_TARGET_KEY = "always_applied_target"

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
        }
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(WallpaperBehaviorPolicy.TRANSITIONS_ENABLED_KEY)
            remove(WallpaperBehaviorPolicy.ALWAYS_APPLIED_TARGET_KEY)
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

    private fun SharedPreferences.readStringSafely(key: String, fallback: String): String {
        return try {
            getString(key, fallback) ?: fallback
        } catch (failure: ClassCastException) {
            Log.w(TAG, "Preference '$key' has the wrong type; using $fallback", failure)
            fallback
        }
    }
}
