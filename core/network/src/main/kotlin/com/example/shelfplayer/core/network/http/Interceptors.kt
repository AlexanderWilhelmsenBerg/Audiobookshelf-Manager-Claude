package com.example.shelfplayer.core.network.http

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.common.time.ServerClock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    /** The active profile's credential and the server that issued it, or `null` when there is none. */
    fun current(): ActiveCredential?
}

/**
 * A bearer token together with the origin it is valid for.
 *
 * The origin is not decoration. Without it [AuthorizationInterceptor] has no way to tell a request to the
 * user's own server from a request to an address chosen by somebody else, and this app has a code path
 * where an outside caller can choose that address: `PlaybackService` is necessarily exported for Android
 * Auto and the platform media controls, and a Media3 controller can hand it a pre-resolved stream URI.
 * The streaming client is a clone of the authenticated one, so before this type existed the ambient bearer
 * was attached to whatever host that URI named.
 *
 * @property serverBaseUrl the profile's server address as stored. Compared by scheme, host and port only;
 *   the path is irrelevant because a server may be hosted under a sub-path and its media served beside it.
 */
data class ActiveCredential(val token: String, val serverBaseUrl: String)

/**
 * Phase 0 has no authentication, so there is no token to supply.
 *
 * This is a real binding rather than a `TODO`: every request the fake gateway makes is local, and a
 * provider that returns `null` proves the interceptor chain does the right thing with no credentials
 * — namely, send no `Authorization` header at all.
 */
@Singleton
class NoTokenProvider @Inject constructor() : TokenProvider {
    override fun current(): ActiveCredential? = null
}

/**
 * PRODUCT_SPEC 10.3 / 15 — authorization is sent as a header, to the issuing server and nowhere else.
 *
 * Two independent rules, and the second one is a credential boundary rather than a convenience.
 *
 * **A request that already carries its own `Authorization` is left alone.** That is what lets a call name
 * the profile it is acting for instead of inheriting whichever profile happens to be active: a library
 * sync for profile B must not be signed with profile A's token just because A is on screen
 * (PRODUCT_SPEC 5.2, product priority 4). Overwriting an explicit header with the ambient one is exactly
 * that bug, and `.header()` overwrites by default — hence the check rather than an ordering assumption
 * about where this interceptor sits in the chain.
 *
 * **The ambient token is attached only to the origin that issued it.** `PlaybackService` must stay
 * exported for Android Auto and the platform media controls, and a Media3 controller can submit a
 * pre-resolved stream URI; the media and download clients are clones of this one, so an unbound ambient
 * bearer meant another application could name a host and have this app deliver the user's server
 * credential to it. Matching on scheme, host and port closes that at the transport layer, independently
 * of whatever the playback layer decides to accept — two boundaries, either of which alone would do.
 *
 * Anything unparseable resolves to *not attached*. A credential withheld costs a `401` the caller already
 * handles; a credential sent to the wrong host cannot be recalled.
 */
class AuthorizationInterceptor @Inject constructor(private val tokenProvider: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) != null) return chain.proceed(request)
        val credential = tokenProvider.current() ?: return chain.proceed(request)
        if (!request.url.isSameOriginAs(credential.serverBaseUrl)) return chain.proceed(request)
        val authorized = request.newBuilder()
            .header(HEADER, "$SCHEME ${credential.token}")
            .build()
        return chain.proceed(authorized)
    }

    /**
     * Whether this URL addresses the same server as [serverBaseUrl].
     *
     * `HttpUrl` normalises the scheme and host to lower case and fills in the default port, so `https://a`
     * and `HTTPS://A:443/` compare equal without this having to normalise anything itself. A base URL that
     * does not parse returns `false`, which withholds the token.
     */
    private fun HttpUrl.isSameOriginAs(serverBaseUrl: String): Boolean {
        val issuer = serverBaseUrl.toHttpUrlOrNull() ?: return false
        return scheme == issuer.scheme && host == issuer.host && port == issuer.port
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

/**
 * PRODUCT_SPEC PLAY-005 — measures this device's clock against the server's, on every response.
 *
 * The `Date` header is HTTP's own (RFC 9110 §6.6.1) and OkHttp already parses it into a `java.util.Date`,
 * so this costs one field read per exchange and no extra request. A response without the header — which the
 * spec permits for a server with no reliable clock — records nothing rather than recording zero, because
 * "we do not know" and "the clocks agree" must not look the same in diagnostics.
 *
 * ### Why this is not the retry or auth chain's business
 *
 * It observes and returns the response untouched. An interceptor that measured *and* acted — refusing a
 * request from a badly-skewed device, say — would fail a listener whose clock is wrong through no fault of
 * theirs, and losing progress to protect a timestamp is the wrong trade (product priority 2).
 */
class ServerClockInterceptor @Inject constructor(private val serverClock: ServerClock, private val clock: AppClock) :
    Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // Read before returning, and read from the response we are handing back. `headers.getDate` returns
        // null for a missing *or* unparseable value, which is the same answer for our purposes: no reading.
        val serverDate = response.headers.getDate("Date")
        if (serverDate != null) {
            serverClock.record(serverTime = serverDate.toInstant(), deviceTime = clock.now())
        }
        return response
    }
}
