package com.example.shelfplayer.core.network.di

import com.example.shelfplayer.core.network.http.AuthorizationInterceptor
import com.example.shelfplayer.core.network.http.RedactingHttpLoggingInterceptor
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

/**
 * PRODUCT_SPEC 10.3 — one configured OkHttp stack per context.
 *
 * [AuthenticatedClient] is the ordinary API client. Long-lived transports (the websocket in Phase 1,
 * media streaming in Phase 2, downloads in Phase 3) get their own qualifiers and their own timeouts,
 * because a 30-second read timeout that is right for an API call would tear down a stream.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

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
        ): OkHttpClient = baseClient()
            .addInterceptor(userAgent)
            .addInterceptor(authorization)
            .addInterceptor(logging)
            .build()

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
        ): OkHttpClient = baseClient()
            .addInterceptor(userAgent)
            .addInterceptor(logging)
            .build()

        private fun baseClient(): OkHttpClient.Builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
    }
}
