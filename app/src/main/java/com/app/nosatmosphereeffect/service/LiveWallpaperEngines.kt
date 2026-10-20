package com.app.nosatmosphereeffect.service

/**
 * The live (not preview) wallpaper engines running in this process, newest
 * last. Some skins (MIUI) hide which live wallpaper is set: WallpaperManager
 * reports none even while Atmo's engine draws the wallpaper. Atmo's engines
 * run in its own process, so they can answer instead (FineTuneRetention).
 */
object LiveWallpaperEngines {
    private val engines = LinkedHashMap<Any, String>()

    @Synchronized
    fun add(engine: Any, effectId: String) {
        engines.remove(engine)
        engines[engine] = effectId
    }

    @Synchronized
    fun remove(engine: Any) {
        engines.remove(engine)
    }

    /** The effect of the newest engine still running here, or null when none is. */
    @Synchronized
    fun effectId(): String? = engines.values.lastOrNull()
}
