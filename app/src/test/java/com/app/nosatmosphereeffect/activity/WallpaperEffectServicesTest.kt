package com.app.nosatmosphereeffect.activity

import com.app.nosatmosphereeffect.service.AtmosphereService
import com.app.nosatmosphereeffect.service.AtmosphereReverseService
import com.app.nosatmosphereeffect.service.AnimatedEffectWallpaperService
import com.app.nosatmosphereeffect.service.GlassReverseService
import com.app.nosatmosphereeffect.service.GlassService
import com.app.nosatmosphereeffect.ui.model.EffectCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperEffectServicesTest {

    @Test
    fun `service registry matches the catalog and its ordering`() {
        assertEquals(
            EffectCatalog.items.map { it.id },
            WallpaperEffectServices.supportedEffectIds
        )
    }

    @Test
    fun `every registered effect uses the shared renderer status lifecycle`() {
        WallpaperEffectServices.supportedEffectIds.forEach { effectId ->
            assertTrue(
                "$effectId must use AnimatedEffectWallpaperService",
                AnimatedEffectWallpaperService::class.java.isAssignableFrom(
                    WallpaperEffectServices.serviceFor(effectId)
                )
            )
        }
    }

    @Test
    fun `glass effect maps to its wallpaper service in both directions`() {
        assertEquals("GLASS", WallpaperEffectServices.normalize("GLASS"))
        assertEquals(GlassService::class.java, WallpaperEffectServices.serviceFor("GLASS"))
        assertEquals(
            "GLASS",
            WallpaperEffectServices.effectIdForService(GlassService::class.java.name)
        )
        assertEquals("GLASS_REVERSE", WallpaperEffectServices.normalize("GLASS_REVERSE"))
        assertEquals(
            GlassReverseService::class.java,
            WallpaperEffectServices.serviceFor("GLASS_REVERSE")
        )
        assertEquals(
            "GLASS_REVERSE",
            WallpaperEffectServices.effectIdForService(GlassReverseService::class.java.name)
        )
    }

    @Test
    fun `atmosphere glass option is visible only for services that consume it`() {
        val atmosphereGlassServices = setOf(
            AtmosphereService::class.java,
            AtmosphereReverseService::class.java
        )

        WallpaperEffectServices.supportedEffectIds.forEach { effectId ->
            assertEquals(
                WallpaperEffectServices.serviceFor(effectId) in atmosphereGlassServices,
                EffectCatalog.supportsAtmosphereGlass(effectId)
            )
        }
    }
}
