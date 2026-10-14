package com.app.nosatmosphereeffect.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectCatalogTest {
    @Test
    fun `effect identifiers are unique`() {
        val ids = EffectCatalog.items.map(EffectItem::id)

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `effect families follow the requested catalog order`() {
        assertEquals(
            listOf(
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
            ),
            EffectCatalog.items.map(EffectItem::id)
        )
    }

    @Test
    fun `every reverse effect has a forward counterpart`() {
        val ids = EffectCatalog.items.map(EffectItem::id).toSet()

        EffectCatalog.items
            .filter { EffectCatalog.isReverse(it.id) }
            .forEach { reverse ->
                val forwardId = if (reverse.id == "REVERSE") {
                    "ORIGINAL"
                } else {
                    reverse.id.removeSuffix("_REVERSE")
                }
                assertTrue("$reverse has no forward counterpart", forwardId in ids)
                assertEquals(
                    EffectCatalog.family(forwardId),
                    EffectCatalog.family(reverse.id)
                )
            }
    }

    @Test
    fun `families pair every effect with its reverse in catalog order`() {
        val families = EffectCatalog.families()

        assertEquals(
            listOf("ATMOSPHERE", "GLASS", "COLORFILL", "CANVAS", "FROSTED", "HALFTONE", "VHS"),
            families.map(EffectFamily::key)
        )
        families.forEach { family ->
            assertFalse(EffectCatalog.isReverse(family.forward.id))
            assertTrue(EffectCatalog.isReverse(family.reverse!!.id))
            assertSame(family.reverse, family.pick(reversed = true))
            assertSame(family.forward, family.pick(reversed = false))
        }
        assertEquals(
            EffectCatalog.items.toSet(),
            families.flatMap { listOfNotNull(it.forward, it.reverse) }.toSet()
        )
    }

    @Test
    fun `a family offered in one direction falls back to it`() {
        val reverseOnly = EffectCatalog.families(listOf(EffectCatalog.find("NEON_REVERSE"))).single()

        assertEquals("NEON_REVERSE", reverseOnly.forward.id)
        assertEquals(null, reverseOnly.reverse)
        assertSame(reverseOnly.forward, reverseOnly.pick(reversed = true))
    }

    @Test
    fun `unknown and null identifiers resolve to the default effect`() {
        val default = EffectCatalog.items.first()

        assertSame(default, EffectCatalog.find(null))
        assertSame(default, EffectCatalog.find("NOT_AN_EFFECT"))
    }

    @Test
    fun `all catalog effects have positive recommended durations`() {
        EffectCatalog.items.forEach { effect ->
            assertTrue(
                "${effect.id} has an invalid duration",
                EffectCatalog.recommendedDurationMillis(effect.id) > 0L
            )
        }
        assertEquals(1000L, EffectCatalog.recommendedDurationMillis(null))
    }

    @Test
    fun `drawing and print families disable dimming`() {
        listOf(
            "HALFTONE",
            "HALFTONE_REVERSE",
            "COLORFILL",
            "COLORFILL_REVERSE",
            "NEON",
            "NEON_REVERSE",
            "GLASS",
            "GLASS_REVERSE"
        ).forEach { id ->
            assertEquals(0f, EffectCatalog.defaultDimness(id), 0f)
        }

        assertEquals(0.2f, EffectCatalog.defaultDimness("ORIGINAL"), 0f)
        assertEquals(0.2f, EffectCatalog.defaultDimness(null), 0f)
    }

    @Test
    fun `only declared effects start from the original wallpaper`() {
        val originalFirst = setOf(
            "ORIGINAL",
            "GLASS",
            "FROSTED",
            "HALFTONE",
            "COLORFILL_REVERSE",
            "NEON_REVERSE",
            "VHS_REVERSE"
        )

        EffectCatalog.items.forEach { effect ->
            assertEquals(
                effect.id in originalFirst,
                EffectCatalog.startsFromOriginalWallpaper(effect.id)
            )
        }
        assertFalse(EffectCatalog.startsFromOriginalWallpaper(null))
    }

    @Test
    fun `glass effects share a family and timing`() {
        assertEquals("GLASS", EffectCatalog.family("GLASS"))
        assertEquals("GLASS", EffectCatalog.family("GLASS_REVERSE"))
        assertEquals(1200L, EffectCatalog.recommendedDurationMillis("GLASS"))
        assertEquals(1200L, EffectCatalog.recommendedDurationMillis("GLASS_REVERSE"))
        assertFalse(EffectCatalog.isReverse("GLASS"))
        assertTrue(EffectCatalog.isReverse("GLASS_REVERSE"))
    }

    @Test
    fun `image glass is offered only for original and reverse atmosphere`() {
        EffectCatalog.items.forEach { effect ->
            assertEquals(
                effect.id == "ORIGINAL" || effect.id == "REVERSE",
                EffectCatalog.supportsAtmosphereGlass(effect.id)
            )
        }
        assertFalse(EffectCatalog.supportsAtmosphereGlass(null))
        assertFalse(EffectCatalog.supportsAtmosphereGlass("NOT_AN_EFFECT"))
    }
}
