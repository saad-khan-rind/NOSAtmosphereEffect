package com.app.nosatmosphereeffect.helper

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only log of renderer backend events, readable from the in-app
 * diagnostics screen.
 *
 * ## Why a file and not an in-memory object
 *
 * The wallpaper engine and the settings UI share a process today, but the
 * engine can be created and torn down long before anyone opens settings, and
 * a Vulkan fallback is exactly the kind of event that happens once at startup
 * and then never again. An in-memory buffer would routinely be empty by the
 * time someone went looking. A small capped file survives that, and survives
 * process death.
 *
 * Deliberately not a crash reporter: nothing here leaves the device, and the
 * user has to go and read it.
 */
object RendererDiagnosticsLog {

    private const val TAG = "RendererDiagnostics"
    private const val FILE_NAME = "renderer-diagnostics.log"
    private const val MAX_BYTES = 96 * 1024
    private const val TRIM_TO_BYTES = 64 * 1024

    private val lock = Any()

    fun record(context: Context, category: String, message: String) {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp  [$category] $message"
        Log.i(TAG, line)
        synchronized(lock) {
            try {
                val file = logFile(context)
                if (!file.exists()) {
                    file.writeText(header())
                }
                file.appendText(line + "\n")
                if (file.length() > MAX_BYTES) trimLocked(file)
            } catch (failure: java.io.IOException) {
                // Diagnostics failing must never affect rendering.
                Log.w(TAG, "Could not write the diagnostics log", failure)
            }
        }
    }

    /** Multi-line detail (e.g. drained native errors) indented under a header. */
    fun recordBlock(context: Context, category: String, message: String, detail: String) {
        val trimmed = detail.trim()
        if (trimmed.isEmpty()) {
            record(context, category, message)
            return
        }
        val indented = trimmed.lines().joinToString("\n") { "        $it" }
        record(context, category, "$message\n$indented")
    }

    fun read(context: Context): String {
        return synchronized(lock) {
            try {
                val file = logFile(context)
                if (file.exists()) file.readText() else ""
            } catch (failure: java.io.IOException) {
                Log.w(TAG, "Could not read the diagnostics log", failure)
                ""
            }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            try {
                logFile(context).delete()
            } catch (failure: SecurityException) {
                Log.w(TAG, "Could not clear the diagnostics log", failure)
            }
        }
    }

    private fun header(): String {
        return buildString {
            append("AtmoEngine renderer diagnostics\n")
            append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n")
            append("build: ${Build.FINGERPRINT}\n")
            append("----\n")
        }
    }

    /** Keeps the tail — the most recent events are the interesting ones. */
    private fun trimLocked(file: File) {
        val text = file.readText()
        if (text.length <= TRIM_TO_BYTES) return
        val tail = text.takeLast(TRIM_TO_BYTES)
        val fromLineStart = tail.indexOf('\n').let { if (it >= 0) tail.substring(it + 1) else tail }
        file.writeText(header() + "(older entries trimmed)\n" + fromLineStart)
    }

    private fun logFile(context: Context): File {
        return File(context.applicationContext.filesDir, FILE_NAME)
    }
}
