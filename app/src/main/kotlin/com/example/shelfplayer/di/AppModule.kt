package com.example.shelfplayer.di

import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.connectivity.AndroidNetworkMonitor
import com.example.shelfplayer.core.common.AppBuild
import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.common.log.LogSink
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.network.di.RemoteGateway
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.core.network.http.UserAgent
import com.example.shelfplayer.domain.download.DownloadScheduler
import com.example.shelfplayer.domain.download.SmartDownload
import com.example.shelfplayer.domain.playback.StartupPlayer
import com.example.shelfplayer.domain.sync.BackgroundSync
import com.example.shelfplayer.domain.usecase.SmartDownloadUseCase
import com.example.shelfplayer.download.WorkManagerDownloadScheduler
import com.example.shelfplayer.feature.lock.BiometricGateway
import com.example.shelfplayer.feature.lock.PlatformBiometricGateway
import com.example.shelfplayer.launcher.AndroidLauncherIcons
import com.example.shelfplayer.launcher.LauncherIcons
import com.example.shelfplayer.log.FanOutLogSink
import com.example.shelfplayer.playback.PlaybackController
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

    /**
     * PRODUCT_SPEC DL-001 / §12 — the download queue, which is WorkManager for the same reason the sync is:
     * the transfer has to outlive the screen that started it and survive the process being killed.
     */
    @Binds
    @Singleton
    fun bindsDownloadScheduler(impl: WorkManagerDownloadScheduler): DownloadScheduler

    /**
     * AUTH-005 — the platform's biometric prompt, and the only binding for it.
     *
     * `PlatformBiometricGateway` rather than an `androidx.biometric` wrapper: that library's API 26/27 path
     * builds an AppCompat dialog which throws under this app's platform theme, and there is no instrumented
     * tier here to catch it. ADR-0023 records the trade, including what API 26 and 27 lose.
     */
    @Binds
    @Singleton
    fun bindsBiometricGateway(impl: PlatformBiometricGateway): BiometricGateway

    /**
     * PRODUCT_SPEC 14.4 — `logcat` and the in-app event log, from one rendered line. See [FanOutLogSink].
     */
    @Binds
    @Singleton
    fun bindsLogSink(impl: FanOutLogSink): LogSink

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

    /**
     * PRODUCT_SPEC SET-003 — which launcher icon is enabled, which only `:app` can answer.
     *
     * The aliases are declared in this module's manifest and addressed by this module's application id,
     * so there is nowhere else the implementation could live.
     */
    @Binds
    @Singleton
    fun bindsLauncherIcons(impl: AndroidLauncherIcons): LauncherIcons

    // PRODUCT_SPEC AUTH-003: the TokenProvider binding moved to `:data:auth`. It is not final wiring —
    // it is the credential store answering the HTTP layer, and both ends of that seam are inside that
    // module. Keeping it here also required `:app` to be able to name the class holding a decrypted
    // token, which nothing outside `:data:auth` should be able to do.

    companion object {
        /**
         * PRODUCT_SPEC DL-005 — the halfway trigger, bound in `:app` because it is the only module that can
         * see both ends.
         *
         * `SmartDownloadUseCase` needs the library and the download use case; the progress journal that
         * calls it lives in `:data:library`, which those depend on. A direct injection would be a cycle, so
         * the journal takes the `SmartDownload` seam and this supplies the real one.
         */
        @Provides
        @Singleton
        fun providesSmartDownload(useCase: SmartDownloadUseCase): SmartDownload = SmartDownload(useCase::invoke)

        /**
         * PRODUCT_SPEC ROUTE-003 — the startup mode's hands, bound in `:app` for the same reason smart
         * download is: `:playback` depends on `:domain`, so the use case cannot name the controller.
         */
        @Provides
        @Singleton
        fun providesStartupPlayer(controller: PlaybackController): StartupPlayer = object : StartupPlayer {
            override suspend fun arm(bookId: LibraryItemId) {
                controller.arm(bookId)
            }

            override suspend fun play(bookId: LibraryItemId) {
                controller.play(bookId)
            }
        }

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

        private const val CLIENT_NAME = "BookWave"
    }
}
