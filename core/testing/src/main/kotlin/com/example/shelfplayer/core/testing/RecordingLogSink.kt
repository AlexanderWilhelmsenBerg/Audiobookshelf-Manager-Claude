package com.example.shelfplayer.core.testing

import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.LogSink

/** One line as it would have been written, after redaction. */
data class RecordedLogLine(val level: LogLevel, val tag: String, val line: String)

/**
 * Captures rendered log lines so a test can assert on exactly the text that would reach the device
 * log — which is how PRODUCT_SPEC AUTH-003 ("tokens never appear in logs") becomes an executable
 * assertion instead of a code-review convention.
 */
class RecordingLogSink : LogSink {
    private val recorded = mutableListOf<RecordedLogLine>()

    val lines: List<RecordedLogLine> get() = recorded.toList()

    val text: String get() = recorded.joinToString(separator = "\n") { it.line }

    override fun write(level: LogLevel, tag: String, line: String) {
        recorded += RecordedLogLine(level, tag, line)
    }

    fun clear() = recorded.clear()
}
