package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.content.SharedPreferences

/**
 * Temporary: routes the Atmosphere effect to OpenGL ES whenever anything on
 * screen needs the subject mask.
 *
 * Subject isolation works on the GLES path and does not on the Vulkan one —
 * the mask reaches the shader on GLES and `hasSubject` never survives to the
 * uniform on Vulkan. That covers three user-visible features that share the
 * same input:
 *
 * - the clock's depth effect,
 * - the Atmosphere effect's own Glass "background only" mode,
 * - and, indirectly, anything else that reads the mask.
 *
 * Rather than ship those broken on the Vulkan backend, the whole effect falls
 * back to GLES for as long as any of them is switched on. The cost is the
 * Vulkan renderer's efficiency on those configurations only; with isolation
 * off — which is the default for Glass and the case for most users — Vulkan
 * is used exactly as before.
 *
 * This is a routing decision, not a fix. Delete this file and its call in
 * [com.app.nosatmosphereeffect.renderer.vulkan.VulkanSupport.resolveBackend]
 * once the Vulkan mask path is working.
 */
object SubjectIsolationBackendPolicy {

    /** Only the Atmosphere pair route through the affected host. */
    private val AFFECTED_EFFECT_IDS = setOf("ORIGINAL", "REVERSE")

    /**
     * True when [effectId] currently has something switched on that needs the
     * subject mask, and should therefore avoid the Vulkan backend.
     *
     * Reads preferences directly rather than taking a render state: backend
     * selection happens in `attach()`, before any state has been pushed to a
     * host, so the state object is not available at the point the decision
     * has to be made.
     */
    fun requiresOpenGl(context: Context, effectId: String?): Boolean {
        if (effectId !in AFFECTED_EFFECT_IDS) return false
        val preferences = runCatching {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        }.getOrNull() ?: return false
        return glassBackgroundOnly(preferences) || clockDepth(context, preferences, effectId)
    }

    private fun glassBackgroundOnly(preferences: SharedPreferences): Boolean {
        val glassEnabled = runCatching {
            preferences.getBoolean(AtmosphereGlassPolicy.ENABLED_KEY, false)
        }.getOrDefault(false)
        if (!glassEnabled) return false
        return runCatching {
            GlassEffectPreferences.read(preferences).backgroundOnly
        }.getOrDefault(false)
    }

    private fun clockDepth(
        context: Context,
        preferences: SharedPreferences,
        effectId: String?
    ): Boolean {
        val requested = runCatching {
            preferences.getBoolean(AtmosphereClockPolicy.ENABLED_KEY, false)
        }.getOrDefault(false)
        val clockEnabled = AtmosphereClockPolicy.resolveEnabled(
            effectId = effectId,
            requested = requested,
            singleImageMode = runCatching {
                !PlaylistModeManager.isPlaylistMode(context)
            }.getOrDefault(true)
        )
        if (!clockEnabled) return false
        // Effects whose display pass has no subject mask hide the depth switch
        // and force it off, so a stale "true" left in preferences from another
        // effect must not make this one download the segmentation model for
        // something it would never draw.
        if (!AtmosphereClockPolicy.supportsDepth(effectId)) return false
        return runCatching {
            preferences.getBoolean(
                AtmosphereClockPolicy.DEPTH_KEY,
                AtmosphereClockPolicy.DEFAULT_DEPTH
            )
        }.getOrDefault(AtmosphereClockPolicy.DEFAULT_DEPTH)
    }

    private const val PREFERENCES_NAME = "app_prefs"
}
