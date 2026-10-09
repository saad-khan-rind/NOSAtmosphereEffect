package com.app.nosatmosphereeffect.renderer

/**
 * The one-pass image effects that share Halftone's renderer: the same
 * textures, subject mask, clock and playlist handling, each with its own pair
 * of fragment shaders. Adding a look of this kind is a pair of shaders and a
 * row here, not another renderer.
 *
 * Only Halftone has a Vulkan host. VHS is not in the Vulkan effect list, so
 * the backend selector always gives it OpenGL ES and this renderer.
 */
enum class SinglePassLook(
    private val forwardId: String,
    private val reverseId: String,
    private val directory: String,
    private val forwardShader: String,
    private val reverseShader: String,
    /** Whether the look can keep the subject untouched; VHS cannot. */
    val hasBackgroundOnly: Boolean,
    val defaultDurationMs: Long
) {
    HALFTONE(
        forwardId = "HALFTONE",
        reverseId = "HALFTONE_REVERSE",
        directory = "halftone",
        forwardShader = "halftone_to_sharp.frag",
        reverseShader = "sharp_to_halftone.frag",
        hasBackgroundOnly = true,
        defaultDurationMs = 500L
    ),
    VHS(
        forwardId = "VHS",
        reverseId = "VHS_REVERSE",
        directory = "vhs",
        forwardShader = "vhs_to_sharp.frag",
        reverseShader = "sharp_to_vhs.frag",
        hasBackgroundOnly = false,
        defaultDurationMs = 900L
    );

    fun effectId(reverse: Boolean): String = if (reverse) reverseId else forwardId

    fun fragmentShader(reverse: Boolean): String =
        "shaders/$directory/${if (reverse) reverseShader else forwardShader}"

    companion object {
        const val VERTEX_SHADER = "shaders/halftone/halftone.vert"

        fun of(effectId: String?): SinglePassLook =
            entries.firstOrNull { effectId == it.forwardId || effectId == it.reverseId } ?: HALFTONE

        fun isReverse(effectId: String?): Boolean = entries.any { it.reverseId == effectId }
    }
}
