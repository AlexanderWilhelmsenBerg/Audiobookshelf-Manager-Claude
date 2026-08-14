package com.example.shelfplayer.log

import com.example.shelfplayer.core.common.log.EventLog
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LogSink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 14.4 — one rendered line, two destinations.
 *
 * `logcat` for a developer with a cable, and [EventLog] for the person holding the phone. Both receive the
 * *same* redacted string, which is the property worth keeping: there is no second rendering path that could
 * be given different rules and quietly become the leaky one (14.5).
 *
 * A fan-out rather than `AndroidLogSink` writing to both, so that each sink stays a thing with one job — and
 * so `AndroidLogSink` remains, literally, the only class in the app that names `android.util.Log`.
 */
@Singleton
class FanOutLogSink @Inject constructor(private val android: AndroidLogSink, private val events: EventLog) : LogSink {
    override fun write(level: LogLevel, tag: String, line: String) {
        android.write(level, tag, line)
        events.write(level, tag, line)
    }
}
