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
    // Monotonic and unique per entry, independent of timestamp/content --
    // used as the LazyColumn item key. Millisecond-timestamp collisions are
    // common under a burst (multiple lines land in the same millisecond),
    // and two data-identical lines (e.g. a repeated warning) hash the same
    // too, so neither timestamp nor content alone is a safe key.
    val sequence: Long,
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

    // Caps how often the UI-facing snapshot is republished. A burst of
    // lines arriving faster than this just gets coalesced into the next
    // snapshot instead of triggering a copy-and-recompose per line -- the
    // buffer itself (and asPlainText/share/copy) always has every line
    // immediately regardless of this interval; only how often the Logs
    // screen repaints is throttled.
    private const val MIN_EMIT_INTERVAL_MS = 200L

    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>()
    private var nextSequence = 0L
    private var dirty = false
    private val _entriesFlow = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entriesFlow: StateFlow<List<AppLogEntry>> get() = _entriesFlow

    init {
        val ticker = Thread({
            while (true) {
                Thread.sleep(MIN_EMIT_INTERVAL_MS)
                flushIfDirty()
            }
        }, "AppLogEmitTicker")
        ticker.isDaemon = true
        ticker.start()
    }

    fun add(level: AppLogLevel, tag: String, message: String) {
        synchronized(lock) {
            entries.addLast(
                AppLogEntry(nextSequence++, System.currentTimeMillis(), level, tag, message)
            )
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            dirty = true
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            dirty = false
        }
        _entriesFlow.value = emptyList()
    }

    fun asPlainText(): String {
        val current = synchronized(lock) { entries.toList() }
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

    private fun flushIfDirty() {
        val snapshot: List<AppLogEntry> = synchronized(lock) {
            if (!dirty) return
            dirty = false
            entries.toList()
        }
        _entriesFlow.value = snapshot
    }
}
