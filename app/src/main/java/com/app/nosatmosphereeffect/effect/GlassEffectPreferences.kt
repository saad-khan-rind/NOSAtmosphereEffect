package com.app.nosatmosphereeffect.effect

import android.content.SharedPreferences

object GlassEffectPreferences {
    private data class ResolvedPreferences(
        val settings: GlassEffectSettings,
        val storedCount: Int,
        val storedThickness: Float,
        val storedTransition: String,
        val presetVersion: Int
    )

    fun read(preferences: SharedPreferences): GlassEffectSettings =
        resolve(preferences).settings

    fun readAndMigrate(preferences: SharedPreferences): GlassEffectSettings {
        val resolved = resolve(preferences)
        val settings = resolved.settings
        if (
            resolved.presetVersion != GlassEffectPolicy.CURRENT_PRESET_VERSION ||
            settings.lineCount != resolved.storedCount ||
            settings.lineThickness != resolved.storedThickness ||
            settings.transitionStyle.storedValue != resolved.storedTransition
        ) {
            write(preferences.edit(), settings).apply()
        }

        return settings
    }

    private fun resolve(preferences: SharedPreferences): ResolvedPreferences {
        val storedCount = preferences.readInt(
            GlassEffectPolicy.LINE_COUNT_KEY,
            GlassEffectPolicy.DEFAULT_LINE_COUNT
        )
        val storedThickness = preferences.readFloat(
            GlassEffectPolicy.LINE_THICKNESS_KEY,
            GlassEffectPolicy.DEFAULT_LINE_THICKNESS
        )
        val presetVersion = preferences.readInt(
            GlassEffectPolicy.PRESET_VERSION_KEY,
            0
        )
        val storedTransition = preferences.readString(
            GlassEffectPolicy.TRANSITION_STYLE_KEY,
            GlassTransitionStyle.RIGHT_TO_LEFT.storedValue
        )
        val transitionStyle = GlassTransitionStyle.fromStoredValue(storedTransition)
        val backgroundOnly = preferences.readBoolean(
            GlassEffectPolicy.BACKGROUND_ONLY_KEY,
            false
        )
        val settings = GlassEffectPolicy.resolveStoredSettings(
            lineCount = storedCount,
            lineThickness = storedThickness,
            presetVersion = presetVersion,
            transitionStyle = transitionStyle,
            backgroundOnly = backgroundOnly
        )
        return ResolvedPreferences(
            settings = settings,
            storedCount = storedCount,
            storedThickness = storedThickness,
            storedTransition = storedTransition,
            presetVersion = presetVersion
        )
    }

    fun write(
        editor: SharedPreferences.Editor,
        settings: GlassEffectSettings
    ): SharedPreferences.Editor {
        val safeSettings = GlassEffectPolicy.resolveStoredSettings(
            lineCount = settings.lineCount,
            lineThickness = settings.lineThickness,
            presetVersion = GlassEffectPolicy.CURRENT_PRESET_VERSION,
            transitionStyle = settings.transitionStyle,
            backgroundOnly = settings.backgroundOnly
        )
        return editor
            .putInt(GlassEffectPolicy.LINE_COUNT_KEY, safeSettings.lineCount)
            .putFloat(
                GlassEffectPolicy.LINE_THICKNESS_KEY,
                safeSettings.lineThickness
            )
            .putString(
                GlassEffectPolicy.TRANSITION_STYLE_KEY,
                safeSettings.transitionStyle.storedValue
            )
            .putBoolean(
                GlassEffectPolicy.BACKGROUND_ONLY_KEY,
                safeSettings.backgroundOnly
            )
            .putInt(
                GlassEffectPolicy.PRESET_VERSION_KEY,
                GlassEffectPolicy.CURRENT_PRESET_VERSION
            )
    }

    private fun SharedPreferences.readInt(key: String, fallback: Int): Int {
        return try {
            getInt(key, fallback)
        } catch (_: ClassCastException) {
            fallback
        }
    }

    private fun SharedPreferences.readFloat(key: String, fallback: Float): Float {
        return try {
            getFloat(key, fallback)
        } catch (_: ClassCastException) {
            fallback
        }
    }

    private fun SharedPreferences.readString(key: String, fallback: String): String {
        return try {
            getString(key, fallback) ?: fallback
        } catch (_: ClassCastException) {
            fallback
        }
    }

    private fun SharedPreferences.readBoolean(key: String, fallback: Boolean): Boolean {
        return try {
            getBoolean(key, fallback)
        } catch (_: ClassCastException) {
            fallback
        }
    }
}
