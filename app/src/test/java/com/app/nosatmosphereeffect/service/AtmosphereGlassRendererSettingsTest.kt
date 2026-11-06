package com.app.nosatmosphereeffect.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.app.nosatmosphereeffect.effect.AtmosphereGlassPolicy
import com.app.nosatmosphereeffect.effect.GlassEffectPolicy
import com.app.nosatmosphereeffect.renderer.AtmosphereRenderController
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphereGlassRendererSettingsTest {
    private val context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `saved glass option reaches both atmosphere renderer directions`() {
        val preferences = ReadOnlyPreferences(
            currentGlassPreferences() + mapOf(
                AtmosphereGlassPolicy.ENABLED_KEY to true,
                GlassEffectPolicy.LINE_COUNT_KEY to 16,
                GlassEffectPolicy.LINE_THICKNESS_KEY to 0.55f,
                GlassEffectPolicy.BACKGROUND_ONLY_KEY to true
            )
        )
        val forward = AtmosphereRenderController(context, reverse = false)
        val reverse = AtmosphereRenderController(context, reverse = true)

        configure(AtmosphereService(), forward, preferences)
        configure(AtmosphereReverseService(), reverse, preferences)

        val forwardState = forward.currentStateForTesting()
        val reverseState = reverse.currentStateForTesting()
        assertTrue(forwardState.glassEnabled)
        assertTrue(reverseState.glassEnabled)
        assertEquals(16, forwardState.glassLineCount)
        assertEquals(16, reverseState.glassLineCount)
        assertEquals(0.55f, forwardState.glassLineThickness, 0f)
        assertEquals(0.55f, reverseState.glassLineThickness, 0f)
        assertTrue(forwardState.glassBackgroundOnly)
        assertTrue(reverseState.glassBackgroundOnly)
        forward.release()
        reverse.release()
    }

    @Test
    fun `missing or malformed glass preference safely disables both renderers`() {
        listOf(
            ReadOnlyPreferences(currentGlassPreferences()),
            ReadOnlyPreferences(
                currentGlassPreferences() +
                    mapOf(AtmosphereGlassPolicy.ENABLED_KEY to "invalid")
            )
        ).forEach { preferences ->
            val forward = AtmosphereRenderController(context, reverse = false)
            val reverse = AtmosphereRenderController(context, reverse = true)

            configure(AtmosphereService(), forward, preferences)
            configure(AtmosphereReverseService(), reverse, preferences)

            assertFalse(forward.currentStateForTesting().glassEnabled)
            assertFalse(reverse.currentStateForTesting().glassEnabled)
            assertFalse(forward.currentStateForTesting().glassBackgroundOnly)
            assertFalse(reverse.currentStateForTesting().glassBackgroundOnly)
            forward.release()
            reverse.release()
        }
    }

    @Test
    fun `both atmosphere renderers use the canonical static glass profile`() {
        val forward = AtmosphereRenderController(context, reverse = false)
        val reverse = AtmosphereRenderController(context, reverse = true)

        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_COUNT,
            forward.currentStateForTesting().glassLineCount
        )
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_COUNT,
            reverse.currentStateForTesting().glassLineCount
        )
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            forward.currentStateForTesting().glassLineThickness,
            0f
        )
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            reverse.currentStateForTesting().glassLineThickness,
            0f
        )

        forward.configure(
            dimLevel = 0.2f,
            saturation = 1f,
            contrast = 1f,
            noiseEnabled = false,
            noiseScale = 2_000f,
            noiseStrength = 0.06f,
            glassEnabled = false,
            glassLineCount = Int.MAX_VALUE,
            glassLineThickness = GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            glassBackgroundOnly = false
        )
        reverse.configure(
            dimLevel = 0.2f,
            saturation = 1f,
            contrast = 1f,
            noiseEnabled = false,
            noiseScale = 2_000f,
            noiseStrength = 0.06f,
            glassEnabled = false,
            glassLineCount = GlassEffectPolicy.DEFAULT_LINE_COUNT,
            glassLineThickness = Float.NaN,
            glassBackgroundOnly = false
        )
        assertEquals(
            GlassEffectPolicy.MAX_LINE_COUNT,
            forward.currentStateForTesting().glassLineCount
        )
        assertEquals(
            GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
            reverse.currentStateForTesting().glassLineThickness,
            0f
        )
        forward.release()
        reverse.release()
    }

    private fun configure(
        service: Any,
        renderer: Any,
        preferences: SharedPreferences
    ) {
        val method: Method = service.javaClass.declaredMethods.single { candidate ->
            candidate.name == "configureRenderer" &&
                candidate.parameterTypes.firstOrNull() == renderer.javaClass
        }
        method.isAccessible = true
        method.invoke(service, renderer, preferences)
    }

    private fun currentGlassPreferences(): Map<String, Any> = mapOf(
        GlassEffectPolicy.LINE_COUNT_KEY to GlassEffectPolicy.DEFAULT_LINE_COUNT,
        GlassEffectPolicy.LINE_THICKNESS_KEY to GlassEffectPolicy.DEFAULT_LINE_THICKNESS,
        GlassEffectPolicy.TRANSITION_STYLE_KEY to "right_to_left",
        GlassEffectPolicy.BACKGROUND_ONLY_KEY to false,
        GlassEffectPolicy.PRESET_VERSION_KEY to GlassEffectPolicy.CURRENT_PRESET_VERSION
    )

    private class ReadOnlyPreferences(
        private val values: Map<String, Any>
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String, defaultValue: String?): String? {
            return typedValue(key, defaultValue)
        }

        override fun getStringSet(
            key: String,
            defaultValues: MutableSet<String>?
        ): MutableSet<String>? {
            val value = values[key] ?: return defaultValues
            if (value !is Set<*> || value.any { it !is String }) {
                throw ClassCastException("$key is not a string set")
            }
            return value.filterIsInstance<String>().toMutableSet()
        }

        override fun getInt(key: String, defaultValue: Int): Int {
            return typedValue(key, defaultValue)
        }

        override fun getLong(key: String, defaultValue: Long): Long {
            return typedValue(key, defaultValue)
        }

        override fun getFloat(key: String, defaultValue: Float): Float {
            return typedValue(key, defaultValue)
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
            return typedValue(key, defaultValue)
        }

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor {
            throw UnsupportedOperationException("Read-only test preferences")
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
        ) = Unit

        private inline fun <reified T> typedValue(key: String, fallback: T): T {
            val value = values[key] ?: return fallback
            if (value !is T) throw ClassCastException("$key is not ${T::class.java.simpleName}")
            return value
        }
    }
}
