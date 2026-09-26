package com.app.nosatmosphereeffect.helper

import android.os.Build
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
    /** "Samsung", "Google Pixel", ... or null when the brand is not covered. */
    val brandLabel: String?,
    /** One line: whether hiding is possible here, as far as is known. */
    val summary: String,
    /** Steps to try, in order. Empty when there is nothing brand-specific to try. */
    val steps: List<String>,
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
                    brandLabel = "Google Pixel",
                    summary = "Pixel phones have no setting to hide the lock screen clock. " +
                        "You can make it smaller so it covers less of the wallpaper clock.",
                    steps = listOf(
                        "Touch and hold an empty spot on the home screen, then tap " +
                            "Wallpaper & style.",
                        "Open the Lock screen tab and choose the smallest clock style.",
                        "On Android 16 and later, turn off Large size for the clock."
                    ),
                    searchQuery = query
                )
                has("oneplus", "oppo", "realme") -> LockScreenClockGuide(
                    brandLabel = when {
                        has("oneplus") -> "OnePlus"
                        has("realme") -> "realme"
                        else -> "OPPO"
                    },
                    summary = "OxygenOS, ColorOS and realme UI let you restyle the lock " +
                        "screen clock, but no setting to hide it has been confirmed.",
                    steps = listOf(
                        "Touch and hold the lock screen (or open Settings > Wallpapers " +
                            "& style) to edit it.",
                        "Tap the clock and pick the smallest or thinnest style.",
                        "If your version offers clock colour or opacity, try the " +
                            "lowest setting."
                    ),
                    searchQuery = query
                )
                has("xiaomi", "redmi", "poco") -> LockScreenClockGuide(
                    brandLabel = when {
                        has("redmi") -> "Redmi"
                        has("poco") -> "POCO"
                        else -> "Xiaomi"
                    },
                    summary = "HyperOS and MIUI let you change the lock screen clock " +
                        "format, but no setting to hide it has been confirmed.",
                    steps = listOf(
                        "Open Settings and search for \"Always-on display & Lock screen\".",
                        "Tap Lock screen clock format and choose the least intrusive style.",
                        "The Themes app sometimes has lock screen styles with a " +
                            "smaller clock."
                    ),
                    searchQuery = query
                )
                has("nothing") -> LockScreenClockGuide(
                    brandLabel = "Nothing",
                    summary = "Nothing OS 3 and later lets you customise the lock screen " +
                        "clock. Whether it can be removed depends on your version.",
                    steps = listOf(
                        "Touch and hold the lock screen and tap Customise lock screen.",
                        "Tap the clock and look through the styles for the smallest one, " +
                            "or an option to remove it."
                    ),
                    searchQuery = query
                )
                has("motorola") -> LockScreenClockGuide(
                    brandLabel = "Motorola",
                    summary = "Motorola phones have no setting to hide the lock screen " +
                        "clock. You can change its style or make it smaller.",
                    steps = listOf(
                        "Open Settings > Display > Lock screen.",
                        "Choose a smaller clock style."
                    ),
                    searchQuery = query
                )
                else -> LockScreenClockGuide(
                    brandLabel = null,
                    summary = "We don't have instructions for this brand yet.",
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
                    brandLabel = "Samsung (One UI 7 or later)",
                    summary = "One UI 7 removed LockStar's option to turn the clock off. " +
                        "Users report you can still make it invisible:",
                    steps = listOf(
                        "Touch and hold the lock screen and tap Edit.",
                        "Tap the clock and choose a plain, single-colour style.",
                        "Open the custom colour picker (the rainbow circle) and drag " +
                            "the transparency slider to 0%.",
                        "Optionally make the clock as small as possible and move it " +
                            "to a corner."
                    ),
                    searchQuery = query
                )
            } else {
                LockScreenClockGuide(
                    brandLabel = "Samsung (One UI 6 or earlier)",
                    summary = "Samsung's Good Lock app can remove the lock screen clock " +
                        "on this version.",
                    steps = listOf(
                        "Install Good Lock from the Galaxy Store and open the " +
                            "LockStar module.",
                        "Turn LockStar on and tap the lock screen preview.",
                        "Tap the clock and use the minus (–) icon to remove it, then save."
                    ),
                    searchQuery = query
                )
            }
        }
    }
}
