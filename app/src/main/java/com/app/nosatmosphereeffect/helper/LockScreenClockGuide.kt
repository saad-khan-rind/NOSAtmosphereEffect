package com.app.nosatmosphereeffect.helper

import android.os.Build
import androidx.annotation.StringRes
import com.app.nosatmosphereeffect.R
import java.util.Locale

/**
 * The phone the app is running on, read from [Build] — no permission needed.
 *
 * Kept as plain values so [LockScreenClockGuide.forDevice] can be tested
 * without a device: unit tests build one of these by hand.
 */
data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int
) {
    /** "Samsung SM-S928B", without repeating the maker when the model already says it. */
    val displayName: String
        get() {
            val maker = manufacturer.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
            val cleanModel = model.trim()
            return when {
                cleanModel.isEmpty() -> maker
                maker.isEmpty() -> cleanModel
                cleanModel.startsWith(maker, ignoreCase = true) -> cleanModel
                else -> "$maker $cleanModel"
            }
        }

    val androidLabel: String
        get() = if (androidRelease.isBlank()) "Android (API $sdkInt)" else "Android $androidRelease"

    companion object {
        fun current(): DeviceIdentity = DeviceIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT
        )
    }
}

/**
 * How to keep the phone's own lock screen clock out of the way of the
 * wallpaper clock, for the brand the app is running on.
 *
 * Written from what each maker's settings, support pages and community
 * answers said in September 2026. Menus move between updates, and most
 * makers offer no way to hide the clock at all — the guide says so rather
 * than inventing a path, and always ends with a web search the user can run
 * for their exact model.
 */
data class LockScreenClockGuide(
    /** "Samsung", "Google Pixel", ... or null when the brand is not covered. String resources throughout. */
    @StringRes val brandLabel: Int?,
    /** One line: whether hiding is possible here, as far as is known. */
    @StringRes val summary: Int,
    /** Steps to try, in order. Empty when there is nothing brand-specific to try. */
    val steps: List<Int>,
    /** What to search for, including the model and Android version. */
    val searchQuery: String
) {
    companion object {
        private const val ANDROID_15 = 35

        fun forDevice(device: DeviceIdentity): LockScreenClockGuide {
            val maker = "${device.manufacturer} ${device.brand}".lowercase(Locale.ROOT)
            val query = "hide lock screen clock ${device.displayName} ${device.androidLabel}"
            fun has(vararg names: String) = names.any { maker.contains(it) }

            return when {
                has("samsung") -> samsung(device, query)
                has("google") -> LockScreenClockGuide(
                    brandLabel = R.string.guide_brand_pixel,
                    summary = R.string.guide_pixel_summary,
                    steps = listOf(
                        R.string.guide_pixel_step1,
                        R.string.guide_pixel_step2,
                        R.string.guide_pixel_step3
                    ),
                    searchQuery = query
                )
                has("oneplus", "oppo", "realme") -> LockScreenClockGuide(
                    brandLabel = when {
                        has("oneplus") -> R.string.guide_brand_oneplus
                        has("realme") -> R.string.guide_brand_realme
                        else -> R.string.guide_brand_oppo
                    },
                    summary = R.string.guide_oppo_summary,
                    steps = listOf(
                        R.string.guide_oppo_step1,
                        R.string.guide_oppo_step2,
                        R.string.guide_oppo_step3
                    ),
                    searchQuery = query
                )
                has("xiaomi", "redmi", "poco") -> LockScreenClockGuide(
                    brandLabel = when {
                        has("redmi") -> R.string.guide_brand_redmi
                        has("poco") -> R.string.guide_brand_poco
                        else -> R.string.guide_brand_xiaomi
                    },
                    summary = R.string.guide_xiaomi_summary,
                    steps = listOf(
                        R.string.guide_xiaomi_step1,
                        R.string.guide_xiaomi_step2,
                        R.string.guide_xiaomi_step3
                    ),
                    searchQuery = query
                )
                has("nothing") -> LockScreenClockGuide(
                    brandLabel = R.string.guide_brand_nothing,
                    summary = R.string.guide_nothing_summary,
                    steps = listOf(
                        R.string.guide_nothing_step1,
                        R.string.guide_nothing_step2
                    ),
                    searchQuery = query
                )
                has("motorola") -> LockScreenClockGuide(
                    brandLabel = R.string.guide_brand_motorola,
                    summary = R.string.guide_motorola_summary,
                    steps = listOf(
                        R.string.guide_motorola_step1,
                        R.string.guide_motorola_step2
                    ),
                    searchQuery = query
                )
                else -> LockScreenClockGuide(
                    brandLabel = null,
                    summary = R.string.guide_unknown_summary,
                    steps = emptyList(),
                    searchQuery = query
                )
            }
        }

        /**
         * Samsung is the one brand where the answer turns on the version: Good
         * Lock's LockStar could remove the clock up to One UI 6, and Samsung
         * confirmed that option is gone from One UI 7 (Android 15) onwards.
         */
        private fun samsung(device: DeviceIdentity, query: String): LockScreenClockGuide {
            return if (device.sdkInt >= ANDROID_15) {
                LockScreenClockGuide(
                    brandLabel = R.string.guide_brand_samsung_new,
                    summary = R.string.guide_samsung_new_summary,
                    steps = listOf(
                        R.string.guide_samsung_new_step1,
                        R.string.guide_samsung_new_step2,
                        R.string.guide_samsung_new_step3,
                        R.string.guide_samsung_new_step4
                    ),
                    searchQuery = query
                )
            } else {
                LockScreenClockGuide(
                    brandLabel = R.string.guide_brand_samsung_old,
                    summary = R.string.guide_samsung_old_summary,
                    steps = listOf(
                        R.string.guide_samsung_old_step1,
                        R.string.guide_samsung_old_step2,
                        R.string.guide_samsung_old_step3
                    ),
                    searchQuery = query
                )
            }
        }
    }
}
