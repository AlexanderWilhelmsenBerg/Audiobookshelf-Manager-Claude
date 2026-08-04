package com.example.shelfplayer.core.common.log

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * PRODUCT_SPEC SET-002 — the two diagnostics opt-ins, both off by default.
 *
 * These are the *only* switches that can widen a log line. No build type, no developer toggle and no
 * log level can make [LogField.Secret] render its value.
 */
data class RedactionPolicy(val includeServerHost: Boolean = false, val includeMediaTitles: Boolean = false) {
    companion object {
        /** PRODUCT_SPEC 14.5 — the shipping default. */
        val Default = RedactionPolicy()
    }
}

/**
 * PRODUCT_SPEC 14.5 — turns a [LogField] into text that is safe to persist and to export.
 *
 * Redacted values are replaced by a short stable digest rather than a constant `***`, so two events
 * about the same book can still be correlated in a diagnostic bundle without naming the book.
 */
interface Redactor {
    fun render(field: LogField): String

    /** Renders a throwable as its exception-class chain, never its message. */
    fun renderThrowable(throwable: Throwable): String
}

@Singleton
class DefaultRedactor @Inject constructor(private val policy: RedactionPolicy = RedactionPolicy.Default) : Redactor {
    override fun render(field: LogField): String = when (field) {
        is LogField.Public -> field.value
        is LogField.Millis -> "${field.value}ms"
        is LogField.Count -> field.value.toString()
        is LogField.Secret -> REDACTED
        is LogField.Identifier -> digest(field.value)
        is LogField.Username -> digest(field.value)
        is LogField.FilePath -> redactPath(field.value)
        is LogField.Url -> redactUrl(field.value)
        is LogField.ServerHost ->
            if (policy.includeServerHost) field.value else digest(field.value)
        is LogField.MediaTitle ->
            if (policy.includeMediaTitles) field.value else digest(field.value)
    }

    override fun renderThrowable(throwable: Throwable): String = generateSequence(throwable, Throwable::cause)
        .take(MAX_CAUSE_DEPTH)
        .joinToString(separator = " <- ") { it.javaClass.name }

    /**
     * Keeps only the file extension. A self-hosted library's directory names routinely contain the
     * author, the series and the book title (PRODUCT_SPEC 22.20).
     */
    private fun redactPath(path: String): String {
        val extension = path.substringAfterLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH && it.all(Char::isLetterOrDigit) }
        return if (extension == null) digest(path) else "${digest(path)}.$extension"
    }

    /**
     * Keeps the scheme and the shape of the path, drops userinfo, query and fragment entirely.
     *
     * PRODUCT_SPEC 10.3 forbids the app from putting tokens in query strings, but a server or a
     * reverse proxy can still hand one back in a redirect, so the query is dropped rather than
     * filtered for known parameter names.
     */
    private fun redactUrl(url: String): String {
        val uri = try {
            URI(url)
        } catch (ignored: URISyntaxException) {
            // The value cannot be parsed, so no part of its shape can be reported safely. The
            // failure itself is not interesting enough to log and would risk echoing the raw value.
            return "$REDACTED_URL_SCHEME://$REDACTED"
        }
        val scheme = uri.scheme ?: REDACTED_URL_SCHEME
        val host = uri.host?.let { if (policy.includeServerHost) it else digest(it) } ?: REDACTED
        val pathShape = uri.path
            ?.split('/')
            ?.filter(String::isNotEmpty)
            ?.joinToString(separator = "/") { segment ->
                if (segment.any(Char::isDigit) || segment.length > MAX_PATH_SEGMENT) "*" else segment
            }
            .orEmpty()
        return buildString {
            append(scheme)
            append("://")
            append(host)
            if (pathShape.isNotEmpty()) {
                append('/')
                append(pathShape)
            }
        }
    }

    /**
     * A short, stable, non-reversible token.
     *
     * [String.hashCode] is not a cryptographic hash and is not meant to be: it is a correlation
     * handle inside a single diagnostic bundle, and the value it stands for is never transmitted.
     */
    private fun digest(value: String): String =
        "#" + value.hashCode().absoluteValue.toString(HEX_RADIX).uppercase(Locale.ROOT).padStart(DIGEST_WIDTH, '0')

    private companion object {
        const val REDACTED = "<redacted>"
        const val REDACTED_URL_SCHEME = "scheme"
        const val MAX_CAUSE_DEPTH = 5
        const val MAX_EXTENSION_LENGTH = 5
        const val MAX_PATH_SEGMENT = 24
        const val HEX_RADIX = 16
        const val DIGEST_WIDTH = 6
    }
}
