package com.example.shelfplayer.di

import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.core.common.log.LogSink
import com.example.shelfplayer.core.network.fake.FakeAudiobookshelfGateway
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.http.TokenProvider
import com.example.shelfplayer.core.network.http.UserAgent
import com.example.shelfplayer.log.AndroidLogSink
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PRODUCT_SPEC 9.3 — `:app` performs the final wiring.
 *
 * Both bindings here are the ones a later phase replaces:
 *  - the gateway becomes the Retrofit-backed implementation in Phase 1 (AUTH-001/LIB-001);
 *  - nothing else changes, because every consumer depends on [AudiobookshelfGateway].
 */
@Module
@InstallIn(SingletonComponent::class)
interface AppModule {
    /**
     * PRODUCT_SPEC 20, Phase 0 — the fake gateway is bound in the application module, not inside
     * `:core:network`, so swapping it for the real one in Phase 1 touches one file and no library
     * module has to know a fixture ever existed.
     */
    @Binds
    @Singleton
    fun bindsGateway(impl: FakeAudiobookshelfGateway): AudiobookshelfGateway

    @Binds
    @Singleton
    fun bindsLogSink(impl: AndroidLogSink): LogSink

    /**
     * PRODUCT_SPEC AUTH-003 — the HTTP layer reads the active profile's token from here.
     *
     * Bound in `:app` rather than `:core:network` because the implementation needs the credential
     * store in `:core:datastore`, and binding it in the network module would make that module depend
     * on the store for a wiring reason alone.
     */
    @Binds
    @Singleton
    fun bindsTokenProvider(impl: SessionTokenProvider): TokenProvider

    companion object {
        /**
         * PRODUCT_SPEC 10.3 — the user agent carries the app version and nothing that identifies the
         * device, the installation or the user.
         */
        @Provides
        @Singleton
        fun providesUserAgent(): UserAgent = UserAgent("ShelfPlayer/${BuildConfig.VERSION_NAME}")
    }
}
