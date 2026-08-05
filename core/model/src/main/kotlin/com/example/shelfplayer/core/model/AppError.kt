package com.example.shelfplayer.core.model

private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500

/**
 * PRODUCT_SPEC 14.1 — the error taxonomy that crosses every layer boundary.
 *
 * Generic [Exception]s must never escape a module. Data and network code translates platform
 * failures into one of these cases so that policy, UI and diagnostics can reason about them
 * exhaustively.
 *
 * Every case carries a [summary] that is safe to show to a user and safe to write to a log: it
 * never embeds a token, a server host, a username, a media title or a filesystem path. Values that
 * would identify private self-hosted data belong in redacted structured log fields, not here
 * (PRODUCT_SPEC 14.5).
 */
sealed interface AppError {
    /** Plain-language, redaction-safe description of what went wrong (PRODUCT_SPEC 14.4). */
    val summary: String

    /** Stable machine-readable code shown as the optional technical detail (PRODUCT_SPEC 14.4). */
    val code: String

    /** Whether retrying the same operation unchanged can plausibly succeed (PRODUCT_SPEC 14.3). */
    val isRetryable: Boolean

    /** The connection could not be established, or it dropped mid-request. */
    data class Network(
        override val summary: String = "The server could not be reached.",
        val cause: Throwable? = null,
    ) : AppError {
        override val code: String = "network"
        override val isRetryable: Boolean = true
    }

    /** The request exceeded its configured timeout. */
    data class Timeout(
        override val summary: String = "The server took too long to respond.",
        val elapsedMillis: Long? = null,
    ) : AppError {
        override val code: String = "timeout"
        override val isRetryable: Boolean = true
    }

    /** The profile is not authenticated, or its token expired (`401`, PRODUCT_SPEC AUTH-004). */
    data class Authentication(
        override val summary: String = "This profile needs to sign in again.",
        val requiresReauthentication: Boolean = true,
    ) : AppError {
        override val code: String = "authentication"

        /** PRODUCT_SPEC 14.3: never blind-retry authentication. */
        override val isRetryable: Boolean = false
    }

    /** The account is authenticated but lacks the permission for this action (`403`). */
    data class Authorization(override val summary: String, val missingPermission: String? = null) : AppError {
        override val code: String = "authorization"
        override val isRetryable: Boolean = false
    }

    /** Client-side or server-side validation rejected the request payload. */
    data class Validation(override val summary: String, val fieldErrors: Map<String, String> = emptyMap()) : AppError {
        override val code: String = "validation"
        override val isRetryable: Boolean = false
    }

    /** The server returned an error status that is not modelled more specifically. */
    data class Server(
        override val summary: String = "The server reported an error.",
        val statusCode: Int? = null,
        val retryAfterSeconds: Long? = null,
    ) : AppError {
        override val code: String = "server"

        /**
         * PRODUCT_SPEC 14.3 — 5xx, an unknown status, and `429` are the retryable cases.
         *
         * `429` is the status that most explicitly invites a retry: the server is not refusing the
         * request, it is asking for it later, and [retryAfterSeconds] carries when. Treating it as a
         * non-retryable 4xx would turn transient rate limiting into a permanent-looking failure.
         */
        override val isRetryable: Boolean =
            statusCode == null || statusCode >= HTTP_SERVER_ERROR || statusCode == HTTP_TOO_MANY_REQUESTS
    }

    /**
     * PRODUCT_SPEC SYNC-001 — a required field was missing, or a capability the caller relied on is
     * not offered by the connected server. Never a crash.
     */
    data class ApiCompatibility(
        override val summary: String,
        val missingCapability: String? = null,
        val missingField: String? = null,
    ) : AppError {
        override val code: String = "api_compatibility"
        override val isRetryable: Boolean = false
    }

    /** Local storage failed: no space, unreadable file, or an unwritable directory. */
    data class Storage(override val summary: String, val freeBytes: Long? = null) : AppError {
        override val code: String = "storage"
        override val isRetryable: Boolean = false
    }

    /** A download job failed (PRODUCT_SPEC DL-001/DL-002). */
    data class Download(
        override val summary: String,
        val partIndex: Int? = null,
        override val isRetryable: Boolean = true,
    ) : AppError {
        override val code: String = "download"
    }

    /** The player could not prepare or continue (PRODUCT_SPEC PLAY-003). */
    data class Playback(override val summary: String, override val isRetryable: Boolean = false) : AppError {
        override val code: String = "playback"
    }

    /** TLS validation, keystore access or another security invariant failed (PRODUCT_SPEC 15). */
    data class Security(override val summary: String) : AppError {
        override val code: String = "security"
        override val isRetryable: Boolean = false
    }

    /** Local and remote state diverged and the user has to choose (`409`, PRODUCT_SPEC PLAY-005). */
    data class Conflict(override val summary: String) : AppError {
        override val code: String = "conflict"
        override val isRetryable: Boolean = false
    }

    /**
     * The operation was cancelled.
     *
     * Coroutine cancellation is always rethrown rather than mapped (PRODUCT_SPEC 14.2); this case
     * exists for user-initiated cancellation of a job that has its own lifecycle, such as a
     * download.
     */
    data class Canceled(override val summary: String = "The operation was canceled.") : AppError {
        override val code: String = "canceled"
        override val isRetryable: Boolean = true
    }

    /** An unclassified failure. Carrying the cause is allowed; logging it verbatim is not. */
    data class Unknown(override val summary: String = "Something went wrong.", val cause: Throwable? = null) :
        AppError {
        override val code: String = "unknown"
        override val isRetryable: Boolean = false
    }
}
