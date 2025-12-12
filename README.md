# NOS Atmosphere Effect

**NOS Atmosphere Effect** is an Android application designed to replicate the distinctive "Atmosphere" transition effect found in Nothing OS.
## ⚠️ Device Support & Disclaimer

**Current Testing Status:**
This application has currently been tested **exclusively on the Samsung Galaxy S25 Ultra**.

While it may work on other Android devices running Android 14+ (API 34), behavior on different manufacturers' skins (Pixel UI, OxygenOS, etc.) is not guaranteed.

## Usage Guide

Follow these steps to set up the effect properly on your device.

### 1\. Select Your Image

Open the app and tap the **"Select & Apply"** button. Choose the image you wish to use from your device's gallery.

### 2\. Adjust Alignment

Use gestures to pinch-to-zoom and drag the image to your desired position. What you see inside the view is exactly how the wallpaper will be cropped. Once satisfied, press **"Apply"**.

### 3\. Application Options

After pressing Apply, a dialog will appear with two checkboxes. Configure them based on your needs:

  * **Option 1: Set Static Lock Screen**
      * **What it does:** Sets the cropped image as your system Lock Screen wallpaper.
      * **⚠️ Important Note for Samsung Users:** Enabling this option will cause the Adaptive Clock to stop working on OneUI (with or without LockStar). If you rely on the adaptive clock, uncheck this and set your lock screen manually using the image either saved in Option 2 or the original image.
  * **Option 2: Save Copy to Gallery**
      * **What it does:** Saves a copy of the final cropped image to your public gallery (`Pictures/Atmosphere`).
      * **Note:** This only keeps the *latest* applied wallpaper. You can use this file to manually set your Lock Screen wallpaper later if needed.

### 4\. Activate the Live Wallpaper (Crucial Step)

Once you confirm the options above, the app will redirect you to the Android System's Live Wallpaper preview screen.

1.  Tap **"Set Wallpaper"**.
2.  **MANDATORY:** You will likely be presented with options (e.g. "Lock Screen" or "Home Screen" or "Home Screen and Lock Screen").
3.  **You must select ONLY "Home Screen".**

> **Why?** If you apply the live wallpaper to both the Lock Screen and Home Screen, the transition effect will animate for both Lock Screen and Home Screen which is not the intended effect.

## Known Issues

  * **Samsung Adaptive Clock:** As mentioned, programmatically setting the lock screen (Option 1) interferes with Samsung's Adaptive Clock.
  * **Device Compatibility:** Only verified on S25 Ultra.

## Build & Installation

This project is built using Kotlin and Gradle.

1.  Clone the repository.
2.  Open in Android Studio (Ladybug or newer recommended).
3.  Sync Gradle.
4.  Build and Run on your device.

<!-- end list -->

```bash
git clone https://github.com/yourusername/NOSAtmosphereEffect.git
```
