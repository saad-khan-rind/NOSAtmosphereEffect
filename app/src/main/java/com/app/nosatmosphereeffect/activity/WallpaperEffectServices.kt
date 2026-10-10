package com.app.nosatmosphereeffect.activity

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.service.wallpaper.WallpaperService
import android.util.Log
import com.app.nosatmosphereeffect.service.AtmosphereService
import com.app.nosatmosphereeffect.service.BlurToSharpService
import com.app.nosatmosphereeffect.service.ColorFillReverseService
import com.app.nosatmosphereeffect.service.ColorFillService
import com.app.nosatmosphereeffect.service.FrostedReverseService
import com.app.nosatmosphereeffect.service.FrostedService
import com.app.nosatmosphereeffect.service.GlassReverseService
import com.app.nosatmosphereeffect.service.GlassService
import com.app.nosatmosphereeffect.service.HalftoneReverseService
import com.app.nosatmosphereeffect.service.HalftoneService
import com.app.nosatmosphereeffect.service.VhsReverseService
import com.app.nosatmosphereeffect.service.VhsService
import com.app.nosatmosphereeffect.service.NeonReverseService
import com.app.nosatmosphereeffect.service.NeonService

internal object WallpaperEffectServices {
    const val DEFAULT_EFFECT_ID = "ORIGINAL"

    private const val TAG = "WallpaperEffects"

    private val serviceByEffectId: Map<String, Class<out WallpaperService>> = linkedMapOf(
        "ORIGINAL" to AtmosphereService::class.java,
        "REVERSE" to BlurToSharpService::class.java,
        "GLASS" to GlassService::class.java,
        "GLASS_REVERSE" to GlassReverseService::class.java,
        "COLORFILL" to ColorFillService::class.java,
        "COLORFILL_REVERSE" to ColorFillReverseService::class.java,
        "NEON" to NeonService::class.java,
        "NEON_REVERSE" to NeonReverseService::class.java,
        "FROSTED" to FrostedService::class.java,
        "FROSTED_REVERSE" to FrostedReverseService::class.java,
        "HALFTONE" to HalftoneService::class.java,
        "HALFTONE_REVERSE" to HalftoneReverseService::class.java,
        "VHS" to VhsService::class.java,
        "VHS_REVERSE" to VhsReverseService::class.java
    )

    private val effectIdByServiceName = serviceByEffectId.entries.associate { (id, service) ->
        service.name to id
    }

    val supportedEffectIds: List<String>
        get() = serviceByEffectId.keys.toList()

    fun normalize(effectId: String?, fallback: String = DEFAULT_EFFECT_ID): String {
        val safeFallback = fallback.takeIf(serviceByEffectId::containsKey) ?: DEFAULT_EFFECT_ID
        return effectId?.takeIf(serviceByEffectId::containsKey) ?: safeFallback
    }

    fun serviceFor(effectId: String?): Class<out WallpaperService> =
        serviceByEffectId.getValue(normalize(effectId))

    fun effectIdForService(className: String?): String? =
        className?.let(effectIdByServiceName::get)

    fun launchPicker(activity: Activity, effectId: String?): Boolean {
        val serviceIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
            ComponentName(activity, serviceFor(effectId))
        )

        try {
            activity.startActivity(serviceIntent)
            return true
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "Direct live-wallpaper picker is unavailable", error)
        } catch (error: SecurityException) {
            Log.w(TAG, "Direct live-wallpaper picker was rejected", error)
        }

        return try {
            activity.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            true
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "No live-wallpaper picker is available", error)
            false
        } catch (error: SecurityException) {
            Log.e(TAG, "Live-wallpaper chooser was rejected", error)
            false
        }
    }
}
