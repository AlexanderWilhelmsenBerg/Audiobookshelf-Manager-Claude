package com.example.shelfplayer.core.network.di

import com.example.shelfplayer.core.network.http.AuthorizationInterceptor
import com.example.shelfplayer.core.network.http.NoTokenProvider
import com.example.shelfplayer.core.network.http.RedactingHttpLoggingInterceptor
import com.example.shelfplayer.core.network.http.TokenProvider
import com.example.shelfplayer.core.network.http.UserAgentInterceptor
import dagger.Binds
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

@Module
@InstallIn(SingletonComponent::class)
interface NetworkModule {
    @Binds
    @Singleton
    fun bindsTokenProvider(impl: NoTokenProvider): TokenProvider

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
        ): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(userAgent)
            .addInterceptor(authorization)
            .addInterceptor(logging)
            .build()
    }
}
