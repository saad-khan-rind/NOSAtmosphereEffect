package com.app.nosatmosphereeffect.helper

import com.app.nosatmosphereeffect.R
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

        assertEquals(R.string.guide_brand_samsung_new, guide.brandLabel)
        assertTrue(R.string.guide_samsung_new_step3 in guide.steps)
    }

    @Test
    fun `Samsung before Android 15 gets LockStar`() {
        val guide = LockScreenClockGuide.forDevice(
            device("samsung", release = "14", sdkInt = 34)
        )

        assertEquals(R.string.guide_brand_samsung_old, guide.brandLabel)
        assertTrue(R.string.guide_samsung_old_step1 in guide.steps)
    }

    @Test
    fun `sub-brands resolve to their own label`() {
        assertEquals(R.string.guide_brand_pixel, LockScreenClockGuide.forDevice(device("Google")).brandLabel)
        assertEquals(R.string.guide_brand_oneplus, LockScreenClockGuide.forDevice(device("OnePlus")).brandLabel)
        assertEquals(R.string.guide_brand_realme, LockScreenClockGuide.forDevice(device("realme")).brandLabel)
        assertEquals(R.string.guide_brand_oppo, LockScreenClockGuide.forDevice(device("OPPO")).brandLabel)
        assertEquals(
            R.string.guide_brand_poco,
            LockScreenClockGuide.forDevice(device("Xiaomi", brand = "POCO")).brandLabel
        )
        assertEquals(
            R.string.guide_brand_redmi,
            LockScreenClockGuide.forDevice(device("Xiaomi", brand = "Redmi")).brandLabel
        )
        assertEquals(R.string.guide_brand_nothing, LockScreenClockGuide.forDevice(device("Nothing")).brandLabel)
        assertEquals(R.string.guide_brand_motorola, LockScreenClockGuide.forDevice(device("motorola")).brandLabel)
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
