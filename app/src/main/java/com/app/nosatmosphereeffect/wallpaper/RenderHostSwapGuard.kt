package com.app.nosatmosphereeffect.wallpaper

internal object RenderHostSwapGuard {
    fun prepare(
        host: WallpaperRenderHost,
        replay: (WallpaperRenderHost) -> Unit
    ): RuntimeException? {
        return try {
            replay(host)
            null
        } catch (failure: RuntimeException) {
            runCatching { host.close() }
            failure
        }
    }

    fun prepareHandoff(
        expected: WallpaperRenderHost,
        replacement: WallpaperRenderHost,
        quiesce: (WallpaperRenderHost) -> Unit,
        replay: (WallpaperRenderHost) -> Unit
    ): RuntimeException? {
        val quiesceFailure = try {
            quiesce(expected)
            null
        } catch (failure: RuntimeException) {
            runCatching { replacement.close() }
            failure
        }
        if (quiesceFailure != null) return quiesceFailure

        val replacementFailure = prepare(replacement, replay) ?: return null
        try {
            replay(expected)
        } catch (restoreFailure: RuntimeException) {
            replacementFailure.addSuppressed(restoreFailure)
        }
        return replacementFailure
    }
}
