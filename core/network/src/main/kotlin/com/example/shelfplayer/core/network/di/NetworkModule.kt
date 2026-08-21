package com.example.shelfplayer.core.network.di

import com.example.shelfplayer.core.network.http.AuthorizationInterceptor
import com.example.shelfplayer.core.network.http.RedactingHttpLoggingInterceptor
import com.example.shelfplayer.core.network.http.ServerClockInterceptor
import com.example.shelfplayer.core.network.http.UserAgentInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.random.Random

/**
 * PRODUCT_SPEC 10.3 — one configured OkHttp stack per context.
 *
 * [AuthenticatedClient] is the ordinary API client. [MediaStreamingClient] and [DownloadStreamingClient]
 * keep the same authenticated stack and stall timeouts, but have no whole-call deadline: their response
 * bodies can legitimately remain open far longer than an ordinary API exchange.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

/**
 * PRODUCT_SPEC 10.3 / PLAY-006 — the authenticated client used by Media3 remote streams.
 *
 * It shares the API client's dispatcher, connection pool and interceptor chain, while disabling the
 * absolute call timeout that would otherwise cancel a healthy stream after 60 seconds.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaStreamingClient

/**
 * PRODUCT_SPEC 10.3 / DL-001 — the authenticated client used by Retrofit `@Streaming` downloads.
 *
 * A download is bounded by WorkManager and caller cancellation, and its read timeout still detects a
 * stalled server. It must not be bounded by the ordinary API client's whole-call deadline.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadStreamingClient

/**
 * PRODUCT_SPEC 9.4 — "qualifiers for authenticated vs unauthenticated clients".
 *
 * This client has no [AuthorizationInterceptor] at all, and the authentication endpoints use it. The
 * distinction is not cosmetic: `GET /status` and `POST /login` are addressed at a server the user is
 * *not* signed in to, and the ambient token belongs to whichever profile is currently active — on a
 * different server. Sending it would hand one server's credential to another host that merely
 * received a URL the user typed (PRODUCT_SPEC 14.5, product priority 4).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnauthenticatedClient

@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {
    // TokenProvider is deliberately not bound here. It needs the credential store, which lives in
    // `:core:datastore`, and binding it in this module would force `:core:network` to depend on it —
    // widening a boundary for a wiring concern. `:app` binds it (PRODUCT_SPEC 9.3), and
    // `NoTokenProvider` remains available for any graph that has no credential store.

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val CALL_TIMEOUT_SECONDS = 60L

        /**
         * PRODUCT_SPEC SYNC-001 — unknown fields are tolerated; missing required fields become a
         * typed compatibility error rather than a crash, which is what `explicitNulls = false`
         * combined with an explicit failure path in the gateway gives.
         *
         * PRODUCT_SPEC 15: no lenient parsing. Accepting malformed JSON from a server would mean
         * accepting whatever a compromised reverse proxy decided to send.
         */
        @Provides
        @Singleton
        fun providesJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
            coerceInputValues = false
        }

        /**
         * PRODUCT_SPEC 14.3 — the jitter source for `RetryPolicy`, injected so a test can seed it.
         *
         * Not security-sensitive: it decides how far apart two retries land, so `Random.Default` is the
         * right choice and a `SecureRandom` here would be cargo cult. What it must be is *replaceable*,
         * because a backoff test that cannot predict its own delays asserts nothing.
         */
        @Provides
        @Singleton
        fun providesRandom(): Random = Random.Default

        /**
         * PRODUCT_SPEC 15 — platform trust only.
         *
         * There is no `sslSocketFactory` override, no custom `HostnameVerifier` and no trust-all
         * mode anywhere in this repository, and there must never be one (PRODUCT_SPEC AUTH-001).
         *
         * PRODUCT_SPEC 10.3 — `retryOnConnectionFailure` is left on for transport-level recovery,
         * but the request-level retry policy lives above OkHttp so that it can distinguish a
         * retryable `GET` from a non-idempotent write.
         */
        @Provides
        @Singleton
        @AuthenticatedClient
        fun providesAuthenticatedClient(
            authorization: AuthorizationInterceptor,
            userAgent: UserAgentInterceptor,
            logging: RedactingHttpLoggingInterceptor,
            serverClock: ServerClockInterceptor,
        ): OkHttpClient = baseClient()
            .addInterceptor(userAgent)
            .addInterceptor(authorization)
            .addInterceptor(logging)
            .addInterceptor(serverClock)
            .build()

        /**
         * PRODUCT_SPEC 10.3 / PLAY-006 — a clone preserves authentication and shared transport
         * infrastructure while changing only the endpoint-lifetime policy.
         */
        @Provides
        @Singleton
        @MediaStreamingClient
        fun providesMediaStreamingClient(
            @AuthenticatedClient authenticatedClient: OkHttpClient,
        ): OkHttpClient = longLivedClient(authenticatedClient)

        /** PRODUCT_SPEC 10.3 / DL-001 — downloads have the same long-lived response-body policy. */
        @Provides
        @Singleton
        @DownloadStreamingClient
        fun providesDownloadStreamingClient(
            @AuthenticatedClient authenticatedClient: OkHttpClient,
        ): OkHttpClient = longLivedClient(authenticatedClient)

        /**
         * PRODUCT_SPEC 9.4 / AUTH-001 — the client the authentication endpoints use.
         *
         * Every credential those endpoints need is passed explicitly by the caller: the password in
         * the login body, the refresh token in `x-refresh-token`, the access token in an explicit
         * `Authorization` on sign-out. Nothing here should be able to attach an ambient one.
         *
         * The connection pool is not shared with the authenticated client, which is the cost of the
         * separation. It is a small one — sign-in happens once per profile — and the alternative is a
         * single client whose behaviour depends on which interceptor ran first.
         */
        @Provides
        @Singleton
        @UnauthenticatedClient
        fun providesUnauthenticatedClient(
            userAgent: UserAgentInterceptor,
            logging: RedactingHttpLoggingInterceptor,
            serverClock: ServerClockInterceptor,
        ): OkHttpClient = baseClient()
            .addInterceptor(userAgent)
            .addInterceptor(logging)
            .addInterceptor(serverClock)
            .build()

        private fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        /**
         * `newBuilder` deliberately shares the connection pool, dispatcher and interceptor instances.
         * Connect/read/write remain bounded so a dead peer still fails; only the complete-call deadline is
         * inappropriate for a body whose useful lifetime is measured in minutes or hours.
         */
        private fun longLivedClient(authenticatedClient: OkHttpClient): OkHttpClient = authenticatedClient
            .newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
