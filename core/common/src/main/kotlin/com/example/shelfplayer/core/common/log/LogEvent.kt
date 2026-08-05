package com.example.shelfplayer.core.common.log

/**
 * PRODUCT_SPEC 14.5 — structured events with a category and a correlation id.
 *
 * [message] must be a constant string. Anything variable belongs in [fields], because only fields
 * pass through the [Redactor]; an interpolated message would smuggle private data straight into the
 * log line.
 */
data class LogEvent(
    val level: LogLevel,
    val category: LogCategory,
    val message: String,
    val fields: List<LogField> = emptyList(),
    val correlationId: String? = null,
    val throwable: Throwable? = null,
)

enum class LogLevel {
    Verbose,
    Debug,
    Info,
    Warn,
    Error,
}

/** Coarse routing category so diagnostics can filter without parsing message text. */
enum class LogCategory {
    Auth,
    Database,
    Download,
    Network,
    Playback,
    Management,
    Settings,
    Sync,
    App,
}

/**
 * A single key/value pair on a log event.
 *
 * The type of the field — not the call site — decides whether the value survives redaction. That is
 * the whole point: a developer adding a new log line cannot accidentally publish a media title,
 * because the only way to attach one is [MediaTitle], which redacts by default.
 */
sealed interface LogField {
    val key: String

    /** A value that is safe in any build: an enum name, a status code, a boolean. */
    data class Public(override val key: String, val value: String) : LogField {
        constructor(key: String, value: Int) : this(key, value.toString())
        constructor(key: String, value: Long) : this(key, value.toString())
        constructor(key: String, value: Boolean) : this(key, value.toString())
    }

    /** A duration in milliseconds. Timings are never private. */
    data class Millis(override val key: String, val value: Long) : LogField

    /** A count. Counts are never private. */
    data class Count(override val key: String, val value: Int) : LogField

    /** Server host or base URL. Redacted unless the user opted in (PRODUCT_SPEC SET-002). */
    data class ServerHost(override val key: String, val value: String) : LogField

    /** Username or display name. Always redacted. */
    data class Username(override val key: String, val value: String) : LogField

    /** Media title, subtitle or description. Redacted unless the user opted in. */
    data class MediaTitle(override val key: String, val value: String) : LogField

    /** A filesystem path. Always redacted; only the extension survives. */
    data class FilePath(override val key: String, val value: String) : LogField

    /** A URL that may carry credentials in userinfo or query. Always redacted. */
    data class Url(override val key: String, val value: String) : LogField

    /**
     * A token, cookie, password or authorization header.
     *
     * PRODUCT_SPEC AUTH-003: this never renders its value, in any build, under any setting.
     */
    data class Secret(override val key: String) : LogField

    /**
     * A stable identifier such as a library or item id.
     *
     * Ids are opaque to a reader of the log but let a support conversation correlate two events, so
     * they are hashed rather than dropped.
     */
    data class Identifier(override val key: String, val value: String) : LogField
}
