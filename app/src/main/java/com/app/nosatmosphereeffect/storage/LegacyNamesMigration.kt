package com.app.nosatmosphereeffect.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Once, before anything reads them: saved settings that still use the old
 * effect IDs (ORIGINAL, REVERSE, NEON, COLORFILL...) or Canvas Sketch's old
 * key names, moved to the names the app uses now. Only the settings that hold
 * an effect ID are touched: other values may share a word (a clock font is
 * called NEON).
 */
object LegacyNamesMigration {

    private const val TAG = "LegacyNamesMigration"
    private const val PREFS_NAME = "legacy_names_migration"
    private const val KEY_VERSION = "version"
    private const val VERSION = 1

    val effectIds = mapOf(
        "ORIGINAL" to "ATMOSPHERE",
        "REVERSE" to "ATMOSPHERE_REVERSE",
        "NEON" to "CANVAS_SKETCH",
        "NEON_REVERSE" to "CANVAS_SKETCH_REVERSE",
        "COLORFILL" to "COLOR_FILL",
        "COLORFILL_REVERSE" to "COLOR_FILL_REVERSE"
    )

    /** Settings whose value is an effect ID. */
    private val effectIdValueKeys = setOf("effect_id", "vulkan_session_effect", "fine_tune_effect_id")

    /** Settings whose key ends in an effect ID. */
    private val effectIdKeyPrefixes = listOf("vulkan_failure_id_", "vulkan_failure_reason_")

    private val renamedKeys = mapOf(
        "neon_sensitivity" to "canvas_sketch_sensitivity",
        "neon_line_width" to "canvas_sketch_line_width"
    )

    fun run(context: Context) {
        val state = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (state.getInt(KEY_VERSION, 0) >= VERSION) return
        try {
            File(context.dataDir, "shared_prefs").listFiles()
                ?.filter { it.name.endsWith(".xml") && it.name != "$PREFS_NAME.xml" }
                ?.forEach { migrate(context.getSharedPreferences(it.name.removeSuffix(".xml"), Context.MODE_PRIVATE)) }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Couldn't move some settings to their new names", error)
        }
        state.edit().putInt(KEY_VERSION, VERSION).commit()
    }

    /** The key [key] has now, or itself. */
    fun renameKey(key: String): String {
        renamedKeys[key]?.let { return it }
        val prefix = effectIdKeyPrefixes.firstOrNull(key::startsWith) ?: return key
        return effectIds[key.removePrefix(prefix)]?.let { prefix + it } ?: key
    }

    /** The value under [key] now: a new effect ID for an old one, where the key holds one. */
    fun renameValue(key: String, value: Any?): Any? =
        if (key in effectIdValueKeys && value is String) effectIds[value] ?: value else value

    private fun migrate(prefs: SharedPreferences) {
        val editor = prefs.edit()
        var changed = false
        for ((key, value) in prefs.all) {
            val newKey = renameKey(key)
            val newValue = renameValue(key, value)
            if (newKey == key && newValue == value) continue
            if (newKey != key) editor.remove(key)
            when (newValue) {
                is String -> editor.putString(newKey, newValue)
                is Int -> editor.putInt(newKey, newValue)
                is Long -> editor.putLong(newKey, newValue)
                is Float -> editor.putFloat(newKey, newValue)
                is Boolean -> editor.putBoolean(newKey, newValue)
                is Set<*> -> editor.putStringSet(newKey, newValue.filterIsInstance<String>().toSet())
            }
            changed = true
        }
        if (changed) editor.commit()
    }
}
