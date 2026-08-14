package com.example.shelfplayer.core.common.log

import com.example.shelfplayer.core.common.time.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One rendered log line, with the moment it was written. */
data class LoggedEvent(val at: Instant, val level: LogLevel, val tag: String, val line: String) {
    /** The two levels a user reporting a defect actually wants to see. */
    val isProblem: Boolean get() = level == LogLevel.Warn || level == LogLevel.Error
}

/**
 * PRODUCT_SPEC 14.4 / 14.5 — the last few hundred log lines, kept so they can be shown in the app.
 *
 * ### Why this exists
 *
 * A device report reached the owner as "it stopped, and then I could not start it". Everything the app knew
 * about *why* went to `logcat`, which needs a cable, a computer and the ADB tools — so in practice the app
 * knew and nobody could read it. This keeps the same lines in memory and puts them under Settings → About,
 * which turns "it stopped" into an error code and a sequence of events.
 *
 * ### Why it is safe to show
 *
 * It is a [LogSink], which means it receives lines **after** [RedactingLogger] has rendered them through the
 * [Redactor]. It never sees a [LogEvent] and never sees a field value that redaction would have removed, so
 * a media title or a token cannot reach it — the same argument that makes `AndroidLogSink` safe, and the
 * reason both are sinks rather than loggers.
 *
 * ### In memory, deliberately
 *
 * Nothing is written to disk. A crash therefore loses the buffer, which is a real cost — but a log file is a
 * file full of somebody's private-server activity that outlives the problem it was kept for, and PRODUCT_SPEC
 * 14.5's whole posture is that this data should exist in as few places as possible. A ring that dies with the
 * process is the version that cannot be forgotten about.
 */
@Singleton
class EventLog @Inject constructor(private val clock: AppClock) : LogSink {

    private val lock = Any()
    private val ring = ArrayDeque<LoggedEvent>(CAPACITY)
    private val _events = MutableStateFlow<List<LoggedEvent>>(emptyList())

    /** Oldest first, which is the order they happened in and the order a reader follows. */
    val events: StateFlow<List<LoggedEvent>> = _events.asStateFlow()

    override fun write(level: LogLevel, tag: String, line: String) {
        val event = LoggedEvent(at = clock.now(), level = level, tag = tag, line = line)
        // Synchronised because logging happens from the player's thread, the IO dispatcher and the main
        // thread at once, and an `ArrayDeque` torn between two of them would be a crash in the diagnostics.
        val snapshot = synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(event)
            ring.toList()
        }
        _events.value = snapshot
    }

    fun clear() {
        synchronized(lock) { ring.clear() }
        _events.value = emptyList()
    }

    private companion object {
        /**
         * Enough to cover the minutes around a defect, small enough to be free.
         *
         * At roughly 120 bytes a line this is about 60 kB held for the life of the process. The journal
         * writes nothing at Info, so an idle hour of playback adds a handful of lines rather than hundreds.
         */
        const val CAPACITY = 500
    }
}
