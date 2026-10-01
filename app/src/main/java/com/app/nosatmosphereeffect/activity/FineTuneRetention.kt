package com.app.nosatmosphereeffect.activity

import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.app.nosatmosphereeffect.helper.AtmosphereClockPolicy

/**
 * Decides what applying a wallpaper does to the Fine tuning settings.
 *
 * Fine tuning lives in "app_prefs" and is tuned for one effect and one photo:
 * a blur radius, a grain, a clock placed and styled against that photo.
 *
 * - Setting a wallpaper when none of ours is live (the first time, or after
 *   another wallpaper was used in between) starts fresh, so nothing left over
 *   from an old setup comes back.
 * - Switching to a different effect starts fresh too, even with the same
 *   photo: values tuned for one effect rarely suit another. This includes
 *   "Change effect", which goes straight to the system picker; it used to
 *   skip this altogether, so the new effect kept the old one's settings.
 * - New images under the same effect keep everything except the wallpaper
 *   clock, which is switched off: a clock placed for the old photo may well
 *   sit badly on the new one.
 * - The same effect again with the same images keeps everything.
 */
internal object FineTuneRetention {

    /** Recorded on every apply, for reference; decisions go by the live wallpaper. */
    const val APPLIED_EFFECT_KEY = "fine_tune_effect_id"

    enum class Plan {
        /** Everything back to its defaults. */
        RESET,
        /** Everything kept. */
        KEEP,
        /** Everything kept but the wallpaper clock, which is switched off. */
        KEEP_CLOCK_OFF
    }

    /** [applyTo]'s decision, apart from Android so it can be tested. */
    fun plan(liveEffectId: String?, targetEffectId: String, imagesChanged: Boolean): Plan {
        val live = liveEffectId ?: return Plan.RESET
        if (WallpaperEffectServices.normalize(live) != WallpaperEffectServices.normalize(targetEffectId)) {
            return Plan.RESET
        }
        return if (imagesChanged) Plan.KEEP_CLOCK_OFF else Plan.KEEP
    }

    /**
     * Part of an apply's edit of "app_prefs": resets, keeps, or switches the
     * clock off, as [plan] decides, and records [targetEffectId]. A reset is
     * an [SharedPreferences.Editor.clear], which Android applies before the
     * rest of the same edit, so the caller's own values still land.
     */
    fun applyTo(
        context: Context,
        editor: SharedPreferences.Editor,
        targetEffectId: String,
        imagesChanged: Boolean
    ): Plan {
        val plan = plan(liveEffectId(context), targetEffectId, imagesChanged)
        when (plan) {
            Plan.RESET -> editor.clear()
            Plan.KEEP_CLOCK_OFF -> editor.putBoolean(AtmosphereClockPolicy.ENABLED_KEY, false)
            Plan.KEEP -> Unit
        }
        editor.putString(APPLIED_EFFECT_KEY, WallpaperEffectServices.normalize(targetEffectId))
        return plan
    }

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
