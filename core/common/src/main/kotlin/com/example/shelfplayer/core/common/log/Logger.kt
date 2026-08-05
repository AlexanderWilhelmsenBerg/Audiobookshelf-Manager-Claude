package com.example.shelfplayer.core.common.log

import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 14.5 — the only logging entry point in the application.
 *
 * There is no `Log.d` and no `println` anywhere else; detekt's `ForbiddenMethodCall` blocks the
 * latter and Android Lint's `LogNotTimber`-style escape hatches are unnecessary because the Android
 * log API is reachable from exactly one class (`AndroidLogSink` in `:app`).
 */
interface Logger {
    fun log(event: LogEvent)
}

fun Logger.debug(category: LogCategory, message: String, vararg fields: LogField, correlationId: String? = null) =
    log(LogEvent(LogLevel.Debug, category, message, fields.toList(), correlationId))

fun Logger.info(category: LogCategory, message: String, vararg fields: LogField, correlationId: String? = null) =
    log(LogEvent(LogLevel.Info, category, message, fields.toList(), correlationId))

fun Logger.warn(
    category: LogCategory,
    message: String,
    vararg fields: LogField,
    correlationId: String? = null,
    throwable: Throwable? = null,
) = log(LogEvent(LogLevel.Warn, category, message, fields.toList(), correlationId, throwable))

fun Logger.error(
    category: LogCategory,
    message: String,
    vararg fields: LogField,
    correlationId: String? = null,
    throwable: Throwable? = null,
) = log(LogEvent(LogLevel.Error, category, message, fields.toList(), correlationId, throwable))

/**
 * Where rendered log lines go.
 *
 * Splitting the sink from the [Logger] keeps `:core:common` free of the Android framework while
 * still letting `:app` route to `android.util.Log`, and lets tests assert on exactly the text that
 * would have been written.
 */
interface LogSink {
    fun write(level: LogLevel, tag: String, line: String)
}

/** Drops everything. The binding used when no sink is configured, so logging can never crash. */
@Singleton
class NoOpLogSink @Inject constructor() : LogSink {
    override fun write(level: LogLevel, tag: String, line: String) = Unit
}

/**
 * Renders a [LogEvent] through the [Redactor] and hands the result to a [LogSink].
 *
 * The throwable is rendered as its exception-class chain only. An exception message routinely
 * carries the very things PRODUCT_SPEC 14.5 forbids logging — `UnknownHostException` contains the
 * server host, `FileNotFoundException` contains the media path — so it is never written.
 */
@Singleton
class RedactingLogger @Inject constructor(private val sink: LogSink, private val redactor: Redactor) : Logger {
    override fun log(event: LogEvent) {
        sink.write(event.level, event.category.name, render(event))
    }

    private fun render(event: LogEvent): String = buildString {
        append(event.message)
        event.correlationId?.let {
            append(" cid=")
            append(it)
        }
        event.fields.forEach { field ->
            append(' ')
            append(field.key)
            append('=')
            append(redactor.render(field))
        }
        event.throwable?.let {
            append(" error=")
            append(redactor.renderThrowable(it))
        }
    }
}
