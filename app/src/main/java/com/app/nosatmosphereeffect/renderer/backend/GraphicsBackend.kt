package com.app.nosatmosphereeffect.renderer.backend

enum class GraphicsBackend {
    OPENGL_ES,
    VULKAN
}

enum class GraphicsBackendPreference(val storedValue: String) {
    AUTOMATIC("automatic"),
    VULKAN("vulkan"),
    OPENGL_ES("opengl_es");

    companion object {
        fun fromStoredValue(value: String?): GraphicsBackendPreference {
            return entries.firstOrNull { it.storedValue == value } ?: AUTOMATIC
        }
    }
}

object GraphicsBackendSelector {
    private val vulkanEffects = setOf(
        "ORIGINAL",
        "REVERSE",
        "GLASS",
        "GLASS_REVERSE",
        "COLORFILL",
        "COLORFILL_REVERSE",
        "NEON",
        "NEON_REVERSE",
        "FROSTED",
        "FROSTED_REVERSE",
        "HALFTONE",
        "HALFTONE_REVERSE",
        "VHS",
        "VHS_REVERSE"
    )

    fun select(
        effectId: String,
        hasVulkan11: Boolean,
        nativeProbePassed: Boolean,
        blockedAfterFailure: Boolean,
        preference: GraphicsBackendPreference
    ): GraphicsBackend {
        if (preference == GraphicsBackendPreference.OPENGL_ES) {
            return GraphicsBackend.OPENGL_ES
        }
        return if (
            effectId in vulkanEffects &&
            hasVulkan11 &&
            nativeProbePassed &&
            !blockedAfterFailure
        ) {
            GraphicsBackend.VULKAN
        } else {
            GraphicsBackend.OPENGL_ES
        }
    }
}
