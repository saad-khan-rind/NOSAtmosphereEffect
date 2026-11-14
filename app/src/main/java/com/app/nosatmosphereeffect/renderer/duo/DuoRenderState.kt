package com.app.nosatmosphereeffect.renderer.duo

import android.content.SharedPreferences

/** How Duo turns the lock screen's photo into the home screen's. */
enum class DuoStyle {
    /** One fades into the other. */
    CROSSFADE,
    /** The home photo climbs the screen behind a soft edge. */
    WIPE,
    /** The home photo opens in a circle from the bottom of the screen. */
    CIRCLE,
    /** The home photo comes through in fine grains. */
    DISSOLVE;

    companion object {
        const val PREFERENCE_KEY = "duo_style"
        val DEFAULT = CROSSFADE

        fun fromOrdinal(ordinal: Int): DuoStyle = entries.getOrElse(ordinal) { DEFAULT }

        /** The style saved in [preferences]; the default if none, or if what is there is not one. */
        fun read(preferences: SharedPreferences): DuoStyle =
            fromOrdinal(runCatching { preferences.getInt(PREFERENCE_KEY, DEFAULT.ordinal) }.getOrDefault(DEFAULT.ordinal))
    }
}

data class DuoRenderState(
    /** 0 on the lock screen (its photo), 1 on the home screen (the other). */
    val progress: Float = 0f,
    val dimLevel: Float = 0f,
    val style: DuoStyle = DuoStyle.DEFAULT,
    /** The share of the home photo's width the screen shows, for wallpaper scrolling (Vulkan). */
    val homeWindowX: Float = 1f
) {
    fun sanitized(): DuoRenderState = copy(
        progress = progress.finiteOr(0f).coerceIn(0f, 1f),
        dimLevel = dimLevel.finiteOr(0f).coerceIn(0f, 1f),
        homeWindowX = homeWindowX.finiteOr(1f).coerceIn(0.001f, 1f)
    )

    private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

    companion object {
        const val LOCKED_PROGRESS = 0f
        const val UNLOCKED_PROGRESS = 1f
        const val DEFAULT_DURATION_MS = 900L
    }
}
