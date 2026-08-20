package com.app.nosatmosphereeffect.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class AppLogLevel(val letter: Char) {
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
    ASSERT('A');

    companion object {
        fun fromLetter(letter: Char): AppLogLevel =
            entries.firstOrNull { it.letter == letter } ?: INFO
    }
}

internal data class AppLogEntry(
    val timestampMillis: Long,
    val level: AppLogLevel,
    val tag: String,
    val message: String
) {
    val formattedTime: String get() = TIME_FORMAT.format(Date(timestampMillis))

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * Bounded, thread-safe log buffer for the in-app Logs screen. This is a
 * testing/diagnostic aid only -- it doesn't replace normal logcat output,
 * it mirrors it into a buffer the app itself can display, since a reporter
 * usually can't pull `adb logcat` off their own phone. See [LogcatTail] for
 * how entries actually get in here.
 */
internal object AppLog {
    private const val MAX_ENTRIES = 4000

    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>()
    private val _entriesFlow = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entriesFlow: StateFlow<List<AppLogEntry>> get() = _entriesFlow

    /** Adds an already-parsed entry (used by [LogcatTail]). */
    fun add(entry: AppLogEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            _entriesFlow.value = entries.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _entriesFlow.value = emptyList()
        }
    }

    fun asPlainText(): String {
        val current = _entriesFlow.value
        return buildString {
            current.forEach { entry ->
                append(entry.formattedTime)
                append(' ')
                append(entry.level.letter)
                append('/')
                append(entry.tag)
                append(": ")
                appendLine(entry.message)
            }
        }
    }
}
