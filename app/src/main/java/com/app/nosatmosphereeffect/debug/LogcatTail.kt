package com.app.nosatmosphereeffect.debug

import android.os.Process
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

    @Volatile
    private var process: Process? = null

    @Synchronized
    fun start() {
        if (process != null) return
        try {
            val pid = Process.myPid()
            val proc = ProcessBuilder(
                "logcat",
                "-v", "time",
                "--pid=$pid"
            ).redirectErrorStream(true).start()
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
        val entry = if (match != null) {
            val (levelLetter, tag, message) = match.destructured
            AppLogEntry(
                timestampMillis = System.currentTimeMillis(),
                level = AppLogLevel.fromLetter(levelLetter.firstOrNull() ?: 'I'),
                tag = tag.trim(),
                message = message
            )
        } else {
            // Unparsed line (continuation of a multi-line stack trace, or an
            // OEM logcat format we didn't anticipate) -- keep it verbatim
            // rather than dropping it, since a stack trace's later lines
            // are exactly what's useful for a crash report.
            AppLogEntry(
                timestampMillis = System.currentTimeMillis(),
                level = AppLogLevel.INFO,
                tag = "logcat",
                message = rawLine
            )
        }
        AppLog.add(entry)
    }
}
