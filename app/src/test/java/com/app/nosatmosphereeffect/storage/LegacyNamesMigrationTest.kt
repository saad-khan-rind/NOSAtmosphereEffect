package com.app.nosatmosphereeffect.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyNamesMigrationTest {

    @Test
    fun effectIdKeysAndValuesMove() {
        assertEquals("vulkan_failure_id_ATMOSPHERE_REVERSE", LegacyNamesMigration.renameKey("vulkan_failure_id_REVERSE"))
        assertEquals("vulkan_failure_reason_COLOR_FILL", LegacyNamesMigration.renameKey("vulkan_failure_reason_COLORFILL"))
        assertEquals("CANVAS_SKETCH", LegacyNamesMigration.renameValue("fine_tune_effect_id", "NEON"))
        assertEquals("canvas_sketch_line_width", LegacyNamesMigration.renameKey("neon_line_width"))
    }

    @Test
    fun otherSettingsStay() {
        // A clock font is called NEON too.
        assertEquals("NEON", LegacyNamesMigration.renameValue("clock_font", "NEON"))
        assertEquals("vulkan_failure_id_GLASS_REVERSE", LegacyNamesMigration.renameKey("vulkan_failure_id_GLASS_REVERSE"))
        assertEquals("ATMOSPHERE", LegacyNamesMigration.renameValue("fine_tune_effect_id", "ATMOSPHERE"))
    }
}
