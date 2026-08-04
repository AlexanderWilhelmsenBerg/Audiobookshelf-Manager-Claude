package com.example.shelfplayer.core.network.http

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.time.AppClock
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC AUTH-003 — supplies the bearer token for authenticated requests.
 *
 * The token is fetched per request rather than captured once, so that a reauthentication takes
 * effect on the next call instead of after an app restart.
 */
fun interface TokenProvider {
    /** The current token for the active profile, or `null` when there is none. */
    fun currentToken(): String?
}

/**
 * Phase 0 has no authentication, so there is no token to supply.
 *
 * This is a real binding rather than a `TODO`: every request the fake gateway makes is local, and a
 * provider that returns `null` proves the interceptor chain does the right thing with no credentials
 * — namely, send no `Authorization` header at all.
 */
@Singleton
class NoTokenProvider @Inject constructor() : TokenProvider {
    override fun currentToken(): String? = null
}

/**
 * PRODUCT_SPEC 10.3 — authorization is sent as a header, never as a query parameter.
 */
class AuthorizationInterceptor @Inject constructor(private val tokenProvider: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider.currentToken()
            ?: return chain.proceed(chain.request())
        val authorized = chain.request().newBuilder()
            .header(HEADER, "$SCHEME $token")
            .build()
        return chain.proceed(authorized)
    }

    private companion object {
        const val HEADER = "Authorization"
        const val SCHEME = "Bearer"
    }
}

/**
 * PRODUCT_SPEC 10.3 — a user agent containing the app version and no private identifiers.
 *
 * No device id, no installation id and no username: a self-hosted server's access log should not be
 * able to distinguish two users of this app beyond what the connection already reveals.
 */
class UserAgentInterceptor @Inject constructor(private val userAgent: UserAgent) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("User-Agent", userAgent.value)
            .build()
        return chain.proceed(request)
    }
}

/**
 * Deliberately a `data class` and not a `@JvmInline value class`.
 *
 * A Kotlin value class in a constructor parameter mangles that constructor's JVM signature, and
 * Dagger's generated **Java** factory then cannot call it:
 * `error: UserAgentInterceptor(String) has private access`. The wrapper exists for type safety at
 * the injection boundary, where one allocation per process is irrelevant.
 *
 * The identifier value classes in `:core:model` are unaffected: they are constructed inside Kotlin,
 * never injected.
 */
data class UserAgent(val value: String)

/**
 * PRODUCT_SPEC 10.3 / 14.5 — the HTTP logger, redacting by construction.
 *
 * OkHttp's own `HttpLoggingInterceptor` is deliberately not used: its `BASIC` level already prints
 * the full URL, and its `HEADERS` level prints `Authorization` unless every sensitive header is
 * enumerated correctly. This interceptor cannot print a token, a host or a path segment, because it
 * hands the URL to the [Logger] as a [LogField.Url] and the redactor decides what survives.
 */
class RedactingHttpLoggingInterceptor @Inject constructor(private val logger: Logger, private val clock: AppClock) :
    Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedAt = clock.elapsed()
        val response = chain.proceed(request)
        val elapsed = clock.elapsed() - startedAt
        logger.debug(
            LogCategory.Network,
            "HTTP exchange",
            LogField.Public("method", request.method),
            LogField.Url("url", request.url.toString()),
            LogField.Public("status", response.code),
            LogField.Millis("elapsed", elapsed.inWholeMilliseconds),
        )
        return response
    }
}
