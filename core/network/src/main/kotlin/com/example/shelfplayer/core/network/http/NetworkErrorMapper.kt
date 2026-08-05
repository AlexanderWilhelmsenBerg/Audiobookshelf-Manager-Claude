package com.example.shelfplayer.core.network.http

import com.example.shelfplayer.core.model.AppError
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * PRODUCT_SPEC 14.1 / 14.3 — translates transport and HTTP failures into [AppError].
 *
 * The mapping is the executable form of the retry policy in PRODUCT_SPEC 14.3: `401` requires
 * reauthentication and is never retried, `403` refreshes permissions, `429` honours `Retry-After`,
 * and only network, timeout and 5xx failures report themselves as retryable.
 *
 * No branch here embeds the request URL or the response body in [AppError.summary]; both routinely
 * contain a server host or a media title (PRODUCT_SPEC 14.5).
 */
@Singleton
class NetworkErrorMapper @Inject constructor() {
    fun fromThrowable(throwable: Throwable): AppError = when (throwable) {
        is SocketTimeoutException -> AppError.Timeout()
        is UnknownHostException -> AppError.Network(
            summary = "The server could not be found. Check the address and your connection.",
        )
        is SSLException -> AppError.Security(
            summary = "The server's security certificate could not be verified.",
        )
        is IOException -> AppError.Network(cause = throwable)
        else -> AppError.Unknown(cause = throwable)
    }

    fun fromStatus(statusCode: Int, retryAfterSeconds: Long? = null): AppError = when (statusCode) {
        HTTP_UNAUTHORIZED -> AppError.Authentication()
        HTTP_FORBIDDEN -> AppError.Authorization(
            summary = "This account is not allowed to perform that action.",
        )
        HTTP_NOT_FOUND -> AppError.Server(
            summary = "The server no longer has that item.",
            statusCode = statusCode,
        )
        HTTP_CONFLICT -> AppError.Conflict(
            summary = "The server has a newer version of this item.",
        )
        HTTP_UNPROCESSABLE -> AppError.Validation(
            summary = "The server rejected the request.",
        )
        HTTP_TOO_MANY_REQUESTS -> AppError.Server(
            summary = "The server is rate limiting requests.",
            statusCode = statusCode,
            retryAfterSeconds = retryAfterSeconds,
        )
        else -> AppError.Server(statusCode = statusCode, retryAfterSeconds = retryAfterSeconds)
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
