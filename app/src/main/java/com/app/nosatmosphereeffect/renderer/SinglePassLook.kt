package com.app.nosatmosphereeffect.renderer

/**
 * The one-pass image effects that share Halftone's renderer: the same
 * textures, subject mask, clock and playlist handling, each with its own pair
 * of fragment shaders. Adding a look of this kind is a pair of shaders and a
 * row here, not another renderer.
 *
 * On Vulkan both share Halftone's native bridge, its push constants and its
 * bindings; [nativeShader] tells it which fragment shader to load.
 */
enum class SinglePassLook(
    private val forwardId: String,
    private val reverseId: String,
    private val directory: String,
    /** For thread names and error messages. */
    val label: String,
    private val forwardShader: String,
    private val reverseShader: String,
    /** Whether the look can keep the subject untouched; VHS cannot. */
    val hasBackgroundOnly: Boolean,
    val defaultDurationMs: Long,
    /** The fragment shader the native bridge loads; see vulkan_halftone_jni.cpp. */
    val nativeShader: Int,
    /** Whether the forward variant shows the effect at the lock screen and clears to the photo. */
    private val forwardEffectFirst: Boolean
) {
    HALFTONE(
        forwardId = "HALFTONE",
        reverseId = "HALFTONE_REVERSE",
        directory = "halftone",
        label = "Halftone",
        forwardShader = "halftone_to_sharp.frag",
        reverseShader = "sharp_to_halftone.frag",
        hasBackgroundOnly = true,
        defaultDurationMs = 500L,
        nativeShader = 0,
        // Halftone's forward variant goes from the photo into dots.
        forwardEffectFirst = false
    ),
    VHS(
        forwardId = "VHS",
        reverseId = "VHS_REVERSE",
        directory = "vhs",
        label = "VHS",
        forwardShader = "vhs_to_sharp.frag",
        reverseShader = "sharp_to_vhs.frag",
        hasBackgroundOnly = false,
        defaultDurationMs = 900L,
        nativeShader = 1,
        // VHS's forward variant starts on tape and clears to the photo.
        forwardEffectFirst = true
    );

    fun effectId(reverse: Boolean): String = if (reverse) reverseId else forwardId

    /** Whether this variant shows the effect at the lock screen: what the Vulkan shaders call reverse. */
    fun effectFirst(reverse: Boolean): Boolean = forwardEffectFirst != reverse

    fun fragmentShader(reverse: Boolean): String =
        "shaders/$directory/${if (reverse) reverseShader else forwardShader}"

    companion object {
        const val VERTEX_SHADER = "shaders/halftone/halftone.vert"

        fun of(effectId: String?): SinglePassLook =
            entries.firstOrNull { effectId == it.forwardId || effectId == it.reverseId } ?: HALFTONE

        fun isReverse(effectId: String?): Boolean = entries.any { it.reverseId == effectId }
    }
}
