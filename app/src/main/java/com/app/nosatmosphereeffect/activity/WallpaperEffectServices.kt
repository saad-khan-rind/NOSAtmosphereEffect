package com.app.nosatmosphereeffect.activity

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.wallpaper.WallpaperService
import android.util.Log
import com.app.nosatmosphereeffect.effect.ShuffleEffects
import com.app.nosatmosphereeffect.service.AnimatedEffectWallpaperService
import com.app.nosatmosphereeffect.service.AtmosphereService
import com.app.nosatmosphereeffect.service.AtmosphereReverseService
import com.app.nosatmosphereeffect.service.ColorFillReverseService
import com.app.nosatmosphereeffect.service.ColorFillService
import com.app.nosatmosphereeffect.service.DuoService
import com.app.nosatmosphereeffect.service.FrostedReverseService
import com.app.nosatmosphereeffect.service.FrostedService
import com.app.nosatmosphereeffect.service.GlassReverseService
import com.app.nosatmosphereeffect.service.GlassService
import com.app.nosatmosphereeffect.service.HalftoneReverseService
import com.app.nosatmosphereeffect.service.HostedEffect
import com.app.nosatmosphereeffect.service.HalftoneService
import com.app.nosatmosphereeffect.service.ShuffleService
import com.app.nosatmosphereeffect.service.VhsReverseService
import com.app.nosatmosphereeffect.service.VhsService
import com.app.nosatmosphereeffect.service.ZoomReverseService
import com.app.nosatmosphereeffect.service.ZoomService
import com.app.nosatmosphereeffect.service.CanvasSketchReverseService
import com.app.nosatmosphereeffect.service.CanvasSketchService

internal object WallpaperEffectServices {
    const val DEFAULT_EFFECT_ID = "ATMOSPHERE"

    private const val TAG = "WallpaperEffects"

    private val serviceByEffectId: Map<String, Class<out WallpaperService>> = linkedMapOf(
        "ATMOSPHERE" to AtmosphereService::class.java,
        "ATMOSPHERE_REVERSE" to AtmosphereReverseService::class.java,
        "GLASS" to GlassService::class.java,
        "GLASS_REVERSE" to GlassReverseService::class.java,
        "COLOR_FILL" to ColorFillService::class.java,
        "COLOR_FILL_REVERSE" to ColorFillReverseService::class.java,
        "CANVAS_SKETCH" to CanvasSketchService::class.java,
        "CANVAS_SKETCH_REVERSE" to CanvasSketchReverseService::class.java,
        "FROSTED" to FrostedService::class.java,
        "FROSTED_REVERSE" to FrostedReverseService::class.java,
        "HALFTONE" to HalftoneService::class.java,
        "HALFTONE_REVERSE" to HalftoneReverseService::class.java,
        "VHS" to VhsService::class.java,
        "VHS_REVERSE" to VhsReverseService::class.java,
        "ZOOM" to ZoomService::class.java,
        "ZOOM_REVERSE" to ZoomReverseService::class.java,
        "DUO" to DuoService::class.java,
        ShuffleEffects.EFFECT_ID to ShuffleService::class.java
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

    /**
     * [effectId]'s wallpaper hooks for [host], a service showing the effect
     * in place of its own (ShuffleService): a new instance of the effect's own
     * service, as a holder of its hooks rather than a wallpaper.
     */
    fun newHostedEffect(effectId: String, host: Context): HostedEffect {
        val service = serviceFor(effectId).getDeclaredConstructor().newInstance()
        return (service as AnimatedEffectWallpaperService<*>).hostIn(host)
    }

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
