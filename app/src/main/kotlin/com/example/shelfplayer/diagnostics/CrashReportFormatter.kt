package com.example.shelfplayer.diagnostics

import com.example.shelfplayer.core.common.log.LoggedEvent
import java.time.Instant

/**
 * Builds the persisted crash envelope from an allow-list.
 *
 * Throwable messages are deliberately absent: network and file exceptions routinely embed server hosts,
 * media paths or other private strings. Class names and source stack frames are code-owned and safe, while
 * event lines have already passed through the normal [com.example.shelfplayer.core.common.log.Redactor].
 */
internal object CrashReportFormatter {
    fun uncaught(
        at: Instant,
        app: String,
        sdk: Int,
        mainThread: Boolean,
        throwable: Throwable,
        events: List<LoggedEvent>,
    ): FormattedCrashReport {
        val meta = CrashReportMeta(CrashReportSource.UncaughtException, at.toEpochMilli())
        val causes = causesOf(throwable)
        val text = buildString {
            appendHeader(meta = meta, at = at, app = app, sdk = sdk)
            appendLine("mainThread: $mainThread")
            appendLine("exception: ${causes.joinToString(" <- ") { it.javaClass.name }}")
            appendLine()
            appendLine("[stack]")
            stackFrames(causes).forEach { frame -> appendLine(frame.asSafeLine()) }
            appendLine()
            val tail = events.takeLast(EVENT_LINES)
            appendLine("[events] ${tail.size} of ${events.size}")
            tail.forEach { event ->
                appendLine(
                    "${event.at} ${event.level.name.first()} ${safeLine(event.tag)} ${safeLine(event.line)}",
                )
            }
        }
        return FormattedCrashReport(meta, text.take(MAX_REPORT_CHARS))
    }

    fun processExit(
        at: Instant,
        app: String,
        sdk: Int,
        reason: String,
        reasonCode: Int,
        status: Int,
        importance: Int,
    ): FormattedCrashReport {
        val meta = CrashReportMeta(CrashReportSource.ProcessExit, at.toEpochMilli())
        val text = buildString {
            appendHeader(meta = meta, at = at, app = app, sdk = sdk)
            appendLine("reason: ${safeLine(reason)}")
            appendLine("reasonCode: $reasonCode")
            appendLine("status: $status")
            appendLine("importance: $importance")
            appendLine()
            appendLine("[stack]")
            appendLine("unavailable — Android recorded the termination after the process had already ended")
            appendLine()
            appendLine("[events] 0 of 0")
            appendLine("unavailable — the normal event ring is intentionally memory-only")
        }
        return FormattedCrashReport(meta, text.take(MAX_REPORT_CHARS))
    }

    private fun StringBuilder.appendHeader(meta: CrashReportMeta, at: Instant, app: String, sdk: Int) {
        appendLine("BookWave crash report")
        appendLine("schema: $SCHEMA_VERSION")
        appendLine("source: ${meta.source.wireName}")
        appendLine("occurredEpochMs: ${meta.occurredAtEpochMs}")
        appendLine("occurred: $at")
        appendLine("app: ${safeLine(app)}")
        appendLine("sdk: $sdk")
    }

    private fun causesOf(root: Throwable): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var current: Throwable? = root
        while (current != null && result.size < MAX_CAUSES && result.none { seen -> seen === current }) {
            result += current
            current = current.cause
        }
        return result
    }

    private fun stackFrames(causes: List<Throwable>): List<StackTraceElement> {
        val appFrames = causes.flatMap { cause ->
            cause.stackTrace.filter { frame -> frame.className.startsWith(APP_PACKAGE_PREFIX) }
        }
        val candidates = appFrames.ifEmpty { causes.firstOrNull()?.stackTrace?.toList().orEmpty() }
        return candidates.take(STACK_LINES)
    }

    private fun StackTraceElement.asSafeLine(): String {
        val source = fileName ?: "UnknownSource"
        return safeLine("$className.$methodName($source:$lineNumber)")
    }

    private fun safeLine(value: String): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(MAX_LINE_CHARS)

    private const val APP_PACKAGE_PREFIX = "com.example.shelfplayer."
    private const val SCHEMA_VERSION = 1
    private const val MAX_CAUSES = 8
    private const val STACK_LINES = 40
    private const val EVENT_LINES = 80
    private const val MAX_LINE_CHARS = 700
    private const val MAX_REPORT_CHARS = 64_000
}
