package com.app.nosatmosphereeffect.debug

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
/**
 * Streams this process's own `logcat` output into [AppLog] so it can be
 * shown on-device. Testing/diagnostic aid only.
 *
 * Reading an app's own logcat lines needs no special permission (the
 * READ_LOGS restriction only applies to reading *other* apps' logs), and
 * `logcat --pid` has been supported since API 24 -- comfortably below this
 * app's minSdk -- so no fallback filtering is needed.
 */
internal object LogcatTail {
    private const val TAG = "LogcatTail"
    private const val LINE_PATTERN =
        """^\S+\s+\S+\s+([VDIWEAF])/([^(]*)\(\s*\d+\):\s?(.*)$"""
    private val lineRegex = Regex(LINE_PATTERN)

    // Every Log tag this app's own code actually uses, gathered by
    // grepping for `const val TAG = "..."` plus a handful of one-off
    // string tags across app/src/main, app/src/play and app/src/fdroid.
    // Kept as an explicit allowlist rather than a priority cutoff: *:I
    // still let through a lot of Android framework/runtime chatter (ART
    // GC, ActivityManager, WindowManager, SurfaceFlinger, etc.) that gets
    // attributed to our PID purely because a live wallpaper renders
    // continuously -- none of that is "related to the app". Silencing
    // everything else (`*:S` below) removes it regardless of how chatty
    // the framework or a given OEM build happens to be.
    private val APP_TAGS = listOf(
        // Carries the wallpaper-active-detection diagnostics -- the one
        // that matters most right now.
        "MainActivity",
        "AdvancedSettings",
        "AtmosphereRenderController",
        "AtmosphereRenderer",
        "BaseCropActivity",
        "BitmapDecoder",
        "BitmapStore",
        "BlurToSharpRenderer",
        "CanvasController",
        "ColorFillController",
        "EffectPreferences",
        "EffectPreviewService",
        "EffectSelection",
        "FileTransactions",
        "FrostedController",
        "GLWallpaperService",
        "GlassRenderController",
        "GlassRenderer",
        "GraphicsBackendPrefs",
        "HalftoneController",
        "HalftoneRenderer",
        "LogcatTail",
        "MultiImageCrop",
        "PlaylistCollectionStore",
        "PlaylistEditor",
        "PlaylistEditorScreen",
        "PlaylistRotation",
        "RendererRuntimeStatus",
        "SubjectMaskExtractor",
        "ThemePlaylistEditor",
        "UriFiles",
        "VulkanAtmosphereHost",
        "VulkanCanvasHost",
        "VulkanColorFill",
        "VulkanEffectHost",
        "VulkanFrostedHost",
        "VulkanGlassHost",
        "VulkanSupport",
        "WallpaperBehavior",
        "WallpaperEffects",
        "WallpaperFitHelper",
        // Concrete live-wallpaper Service classes log under their own
        // simple class name rather than a shared TAG constant (see
        // AnimatedEffectWallpaperService.logTag).
        "AtmosphereService",
        "BlurToSharpService",
        "GlassService",
        "GlassReverseService",
        "ColorFillService",
        "ColorFillReverseService",
        "NeonService",
        "NeonReverseService",
        "FrostedService",
        "FrostedReverseService",
        "HalftoneService",
        "HalftoneReverseService"
    )

    @Volatile
    private var process: Process? = null

    @Synchronized
    fun start() {
        if (process != null) return
        try {
            val pid = android.os.Process.myPid()
            val args = buildList {
                add("logcat")
                add("-v")
                add("time")
                add("--pid=$pid")
                APP_TAGS.forEach { add("$it:D") }
                // Default priority for every tag not listed above: silent.
                add("*:S")
            }
            val proc = ProcessBuilder(args).redirectErrorStream(true).start()
            process = proc

            val reader = Thread({
                readLoop(proc)
            }, "AtmoLogcatTail")
            reader.isDaemon = true
            reader.start()
        } catch (error: Exception) {
            // Some OEM builds restrict spawning `logcat` from an app
            // process entirely -- the Logs screen just stays empty rather
            // than the app failing to start over a diagnostics feature.
            Log.w(TAG, "Unable to start logcat tail", error)
        }
    }

    private fun readLoop(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    ingest(line)
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {
            // Stream closed (process torn down, etc.) -- nothing to recover.
        }
    }

    private fun ingest(rawLine: String) {
        val match = lineRegex.matchEntire(rawLine)
        if (match != null) {
            val (levelLetter, tag, message) = match.destructured
            AppLog.add(
                level = AppLogLevel.fromLetter(levelLetter.firstOrNull() ?: 'I'),
                tag = tag.trim(),
                message = message
            )
        } else {
            // Unparsed line (continuation of a multi-line stack trace, or an
            // OEM logcat format we didn't anticipate) -- keep it verbatim
            // rather than dropping it, since a stack trace's later lines
            // are exactly what's useful for a crash report.
            AppLog.add(level = AppLogLevel.INFO, tag = "logcat", message = rawLine)
        }
    }
}
