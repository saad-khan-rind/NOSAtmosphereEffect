package com.app.nosatmosphereeffect

import android.app.Application
import com.app.nosatmosphereeffect.storage.LegacyNamesMigration

/** Runs before any screen or wallpaper: settings saved under old names are moved first. */
class AtmoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LegacyNamesMigration.run(this)
    }
}
