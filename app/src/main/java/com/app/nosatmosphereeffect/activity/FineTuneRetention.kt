package com.app.nosatmosphereeffect.activity

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * Decides whether applying a wallpaper keeps the Fine tuning settings.
 *
 * Fine tuning lives in "app_prefs" and is tuned for one effect: a blur radius,
 * a grain, a clock placed and styled against that effect's look. Changing only
 * the image or the playlist under the same effect keeps all of it. Switching to
 * a different effect starts fresh, because values tuned for one effect rarely
 * suit another.
 */
internal object FineTuneRetention {

    /**
     * The effect the settings in "app_prefs" were last applied with. Written
     * on every apply, so it survives the wipe that a change of effect does.
     */
    const val APPLIED_EFFECT_KEY = "fine_tune_effect_id"

    /**
     * True when applying [targetEffectId] should keep the current settings:
     * the same effect is being applied again.
     *
     * Android's live wallpaper is the authority when it is one of ours; the
     * stored effect covers the case where another wallpaper was set in
     * between, or the system will not say. With neither, [whenUnknown]
     * decides: editing an existing playlist has always kept its settings.
     */
    fun keeps(
        context: Context,
        appPreferences: SharedPreferences,
        targetEffectId: String,
        whenUnknown: Boolean = false
    ): Boolean = decide(
        liveEffectId = liveEffectId(context),
        storedEffectId = appPreferences.getString(APPLIED_EFFECT_KEY, null),
        targetEffectId = targetEffectId,
        whenUnknown = whenUnknown
    )

    /** [keeps] without Android in the way. */
    fun decide(
        liveEffectId: String?,
        storedEffectId: String?,
        targetEffectId: String,
        whenUnknown: Boolean
    ): Boolean {
        val current = liveEffectId ?: storedEffectId ?: return whenUnknown
        return WallpaperEffectServices.normalize(current) ==
            WallpaperEffectServices.normalize(targetEffectId)
    }

    /** Records the effect the settings now belong to; part of the apply's edit. */
    fun markApplied(editor: SharedPreferences.Editor, effectId: String): SharedPreferences.Editor =
        editor.putString(APPLIED_EFFECT_KEY, WallpaperEffectServices.normalize(effectId))

    /**
     * The effect id of this app's live wallpaper on the Home screen, or on the
     * Lock screen when Home is something else; null when neither is ours.
     */
    fun liveEffectId(context: Context): String? {
        val manager = WallpaperManager.getInstance(context)
        val packageName = context.packageName
        val homeInfo = try {
            manager.wallpaperInfo
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to inspect the Home screen live wallpaper", failure)
            null
        }
        if (homeInfo?.packageName == packageName) {
            return WallpaperEffectServices.effectIdForService(homeInfo.component.className)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val lockInfo = try {
            manager.getWallpaperInfo(WallpaperManager.FLAG_LOCK)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Unable to inspect the Lock screen live wallpaper", failure)
            null
        }
        if (lockInfo?.packageName != packageName) return null
        return WallpaperEffectServices.effectIdForService(lockInfo.component.className)
    }

    private const val TAG = "FineTuneRetention"
}
