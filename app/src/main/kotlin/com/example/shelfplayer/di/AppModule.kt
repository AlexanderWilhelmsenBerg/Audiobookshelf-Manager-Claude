package com.example.shelfplayer.di

import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.connectivity.AndroidNetworkMonitor
import com.example.shelfplayer.core.common.AppBuild
import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.common.log.LogSink
import com.example.shelfplayer.core.network.di.RemoteGateway
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.http.UserAgent
import com.example.shelfplayer.domain.sync.BackgroundSync
import com.example.shelfplayer.log.AndroidLogSink
import com.example.shelfplayer.sync.WorkManagerBackgroundSync
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
     * PRODUCT_SPEC 9.3 — `:app` chooses which gateway the app talks to, and it now chooses the real one.
     *
     * This is the binding Phase 0 existed to make swappable, and swapping it took one line. What changed
     * with it is not one line: the app no longer has a bundled library, so a build with no saved profile
     * has nothing to show until sign-in lands. `FakeAudiobookshelfGateway` is deliberately left in the
     * graph-less state — reachable from tests, bound nowhere — rather than deleted, because it still backs
     * the repository tests and PRODUCT_SPEC 17.1 prefers a hand-written fake to a mock.
     */
    @Binds
    @Singleton
    fun bindsGateway(@RemoteGateway impl: AudiobookshelfGateway): AudiobookshelfGateway

    @Binds
    @Singleton
    fun bindsLogSink(impl: AndroidLogSink): LogSink

    /**
     * PRODUCT_SPEC LIB-002 — the same seam shape as the log sink: a `:core:common` interface so that
     * domain and presentation code stays testable off-device, with the one class that touches
     * `ConnectivityManager` living here.
     */
    @Binds
    @Singleton
    fun bindsNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor

    /** PRODUCT_SPEC SYNC-003 — the scheduling policy is domain; the enqueueing is platform. */
    @Binds
    @Singleton
    fun bindsBackgroundSync(impl: WorkManagerBackgroundSync): BackgroundSync

    // PRODUCT_SPEC AUTH-003: the TokenProvider binding moved to `:data:auth`. It is not final wiring —
    // it is the credential store answering the HTTP layer, and both ends of that seam are inside that
    // module. Keeping it here also required `:app` to be able to name the class holding a decrypted
    // token, which nothing outside `:data:auth` should be able to do.

    companion object {
        /**
         * PRODUCT_SPEC 10.3 — the user agent carries the app version and nothing that identifies the
         * device, the installation or the user.
         */
        @Provides
        @Singleton
        fun providesUserAgent(): UserAgent = UserAgent("${CLIENT_NAME}/${BuildConfig.VERSION_NAME}")

        /**
         * PRODUCT_SPEC PLAY-001 — the same two facts the user agent carries, for the callers that need
         * them apart rather than joined into one string.
         *
         * `BuildConfig` is generated per module, so this is the only place in the app where `:app`'s
         * version name is readable.
         */
        @Provides
        @Singleton
        fun providesAppBuild(): AppBuild = AppBuild(CLIENT_NAME, BuildConfig.VERSION_NAME)

        private const val CLIENT_NAME = "ShelfPlayer"
    }
}
