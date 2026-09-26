package com.app.nosatmosphereeffect.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenClockGuideTest {

    private fun device(
        manufacturer: String,
        brand: String = manufacturer,
        model: String = "Model X",
        release: String = "15",
        sdkInt: Int = 35
    ) = DeviceIdentity(manufacturer, brand, model, release, sdkInt)

    @Test
    fun `Samsung on Android 15 or later gets the One UI 7 workaround`() {
        val guide = LockScreenClockGuide.forDevice(device("samsung", sdkInt = 35))

        assertEquals("Samsung (One UI 7 or later)", guide.brandLabel)
        assertTrue(guide.steps.any { it.contains("transparency") })
    }

    @Test
    fun `Samsung before Android 15 gets LockStar`() {
        val guide = LockScreenClockGuide.forDevice(
            device("samsung", release = "14", sdkInt = 34)
        )

        assertEquals("Samsung (One UI 6 or earlier)", guide.brandLabel)
        assertTrue(guide.steps.any { it.contains("LockStar") })
    }

    @Test
    fun `sub-brands resolve to their own label`() {
        assertEquals("Google Pixel", LockScreenClockGuide.forDevice(device("Google")).brandLabel)
        assertEquals("OnePlus", LockScreenClockGuide.forDevice(device("OnePlus")).brandLabel)
        assertEquals("realme", LockScreenClockGuide.forDevice(device("realme")).brandLabel)
        assertEquals("OPPO", LockScreenClockGuide.forDevice(device("OPPO")).brandLabel)
        assertEquals(
            "POCO",
            LockScreenClockGuide.forDevice(device("Xiaomi", brand = "POCO")).brandLabel
        )
        assertEquals(
            "Redmi",
            LockScreenClockGuide.forDevice(device("Xiaomi", brand = "Redmi")).brandLabel
        )
        assertEquals("Nothing", LockScreenClockGuide.forDevice(device("Nothing")).brandLabel)
        assertEquals("Motorola", LockScreenClockGuide.forDevice(device("motorola")).brandLabel)
    }

    @Test
    fun `unknown brands get no steps and fall back to a search`() {
        val guide = LockScreenClockGuide.forDevice(
            device("Fairphone", model = "FP5", release = "14", sdkInt = 34)
        )

        assertNull(guide.brandLabel)
        assertTrue(guide.steps.isEmpty())
        assertEquals("hide lock screen clock Fairphone FP5 Android 14", guide.searchQuery)
    }

    @Test
    fun `display name does not repeat the maker`() {
        assertEquals("Samsung SM-S928B", device("samsung", model = "SM-S928B").displayName)
        assertEquals("Nothing Phone (2)", device("Nothing", model = "Nothing Phone (2)").displayName)
    }

    @Test
    fun `missing release falls back to the API level`() {
        assertEquals("Android (API 36)", device("Google", release = "", sdkInt = 36).androidLabel)
    }
}
