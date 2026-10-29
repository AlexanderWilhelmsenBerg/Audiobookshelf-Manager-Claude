package com.example.shelfplayer.playback

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SyncOutcome
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.testing.TestAppClock
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.DownloadRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.RememberedBookRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.usecase.ObserveHomeShelvesUseCase
import com.example.shelfplayer.domain.usecase.OpenPlaybackSessionUseCase
import com.example.shelfplayer.domain.usecase.RenewProfileSessionUseCase
import com.example.shelfplayer.domain.usecase.RequireProfileReauthenticationUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Selection-specific callback, journal and session ports composed around the shared service fixture. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackServiceSelectionFixtureSupport(
    private val context: android.content.Context,
    private val dispatcher: TestDispatcher,
    private val applicationScope: CoroutineScope,
    private val clock: TestAppClock,
    private val logger: com.example.shelfplayer.core.common.log.Logger,
    private val service: PlaybackService,
    private val player: androidx.media3.exoplayer.ExoPlayer,
    private val volumeEffects: PlaybackVolumeEffects,
    private val selectCar: () -> Unit,
) {
    var postConnectSessionOpens = 0
        private set
    var capturedJournalBookId: LibraryItemId? = null
        private set
    var capturedJournalOwner: ProfileId? = null
        private set
    var capturedJournalPosition: Duration? = null
        private set
    val pauseSyncTriggers = mutableListOf<SyncTrigger>()
    val syncedPositions = mutableListOf<Duration>()

    suspend fun configureCapturedPauseSync(
        recordEntered: CompletableDeferred<Unit>,
        releaseRecord: CompletableDeferred<Unit>,
    ) {
        setPausedBook()
        service.playbackRepository = recordingPlaybackRepository(recordEntered, releaseRecord)
        val sync = SessionSyncCoordinator(
            repository = capturedPauseSyncRepository(),
            baseline = service.resumeBaseline,
            clock = clock,
            logger = logger,
            volumeEffects = volumeEffects,
            applicationScope = applicationScope,
            mainDispatcher = dispatcher,
        )
        service.sessionSync = sync
        sync.attach(player) { 12.seconds }
        sync.onSessionOpened(capturedPauseSession())
    }

    private fun setPausedBook() {
        val owner = ProfileId("pause-profile")
        val extras = Bundle().apply { putString(MediaItems.KEY_OWNER_PROFILE_ID, owner.value) }
        player.setMediaItem(
            androidx.media3.common.MediaItem.Builder().setMediaId("original").setUri("file:///fixture")
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setExtras(extras).build()).build(),
            12_000L,
        )
    }

    private fun recordingPlaybackRepository(
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ): PlaybackRepository = object : PlaybackRepository by selectionFixtureUnused() {
        override suspend fun recordPosition(
            bookId: LibraryItemId,
            position: Duration,
            duration: Duration,
            owner: ProfileId?,
        ): AppResult<Unit> {
            capturedJournalBookId = bookId
            capturedJournalPosition = position
            capturedJournalOwner = owner
            entered.complete(Unit)
            release.await()
            return AppResult.Success(Unit)
        }
    }

    private fun capturedPauseSyncRepository(): SessionSyncRepository =
        object : SessionSyncRepository by selectionFixtureUnused() {
            override suspend fun openSession(session: PlaybackSession, startedAt: Instant): AppResult<String> =
                AppResult.Success("pause-sync-session")

            override suspend fun syncOpenSession(
                sessionId: String,
                progress: SessionProgress,
                updatedAt: Instant,
                trigger: SyncTrigger,
            ): AppResult<SyncOutcome> {
                pauseSyncTriggers += trigger
                syncedPositions += progress.position
                return AppResult.Success(SyncOutcome.Accepted)
            }
        }

    private fun capturedPauseSession() = PlaybackSession(
        id = "pause-sync-session",
        profileId = ProfileId("pause-profile"),
        bookId = LibraryItemId("original"),
        title = "Fixture",
        author = null,
        coverUrl = null,
        startAt = Duration.ZERO,
        duration = 1.seconds,
        tracks = emptyList(),
        chapters = emptyList(),
    )

    fun dispatchPausedPlayerEvent() {
        val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "PlayerEvents" }
        val listener = type.getDeclaredConstructor(PlaybackService::class.java)
            .apply { isAccessible = true }.newInstance(service) as Player.Listener
        listener.onIsPlayingChanged(false)
    }

    fun invokePostConnect(selectOutput: Boolean) {
        player.clearMediaItems()
        val callback = libraryCallback()
        val session = testSession()
        try {
            callback.onPostConnect(session, carController())
            if (selectOutput) selectCar()
        } finally {
            session.release()
        }
    }

    private fun libraryCallback(): MediaLibraryService.MediaLibrarySession.Callback {
        val type = PlaybackService::class.java.declaredClasses.single { it.simpleName == "LibraryCallback" }
        return type.getDeclaredConstructor(PlaybackService::class.java).apply { isAccessible = true }
            .newInstance(service) as MediaLibraryService.MediaLibrarySession.Callback
    }

    private fun testSession() = MediaLibraryService.MediaLibrarySession.Builder(
        context,
        player,
        object : MediaLibraryService.MediaLibrarySession.Callback {},
    ).build()

    private fun carController() = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.google.android.projection.gearhead",
        0,
        0,
        1,
        1,
        false,
        Bundle.EMPTY,
        false,
    )

    fun configurePostConnectRace() {
        val ports = postConnectRepositories()
        service.auto = createAutoLibrary(ports)
        service.lock = ProfileLockGuard { false }
        service.coldResumeStartPosition = ColdResumeStartPosition(DisabledColdResumeDiagnostic())
        service.devices = postConnectDeviceRepository()
        service.openPlaybackSession = createOpenPlaybackSession(ports)
    }

    private data class PostConnectRepositories(
        val profileId: ProfileId,
        val book: Book,
        val profiles: ProfileRepository,
        val library: LibraryRepository,
        val remembered: RememberedBookRepository,
    )

    private fun postConnectRepositories(): PostConnectRepositories {
        val profileId = ProfileId("post-connect-profile")
        val book = postConnectFixtureBook()
        val profiles = object : ProfileRepository by selectionFixtureUnused() {
            override suspend fun activeProfileId(): ProfileId? = profileId
            override fun activeProfileGeneration(): Long = 0L
        }
        val library = object : LibraryRepository by selectionFixtureUnused() {
            override fun observeAccessibleBooks(profileId: ProfileId) = flowOf(listOf(book))
            override fun observeBook(profileId: ProfileId, bookId: LibraryItemId) = flowOf(null)
        }
        val remembered = object : RememberedBookRepository by selectionFixtureUnused() {
            override suspend fun rememberedBook(profileId: ProfileId): LibraryItemId = book.id
        }
        return PostConnectRepositories(profileId, book, profiles, library, remembered)
    }

    private fun createAutoLibrary(ports: PostConnectRepositories) = AutoLibrary(
        context = context,
        profiles = ports.profiles,
        library = ports.library,
        downloads = selectionFixtureUnused<DownloadRepository>(),
        rememberedBooks = ports.remembered,
        homeShelves = ObserveHomeShelvesUseCase(ports.profiles, ports.library, dispatcher),
        activation = ProfileActivationGuard { true },
        artwork = AutoArtwork.None,
    )

    private fun postConnectDeviceRepository(): DeviceRepository =
        object : DeviceRepository by selectionFixtureUnused() {
            override suspend fun remember(device: KnownDevice): AppResult<Unit> = AppResult.Success(Unit)
            override suspend fun policyFor(deviceId: String): DevicePolicy = DevicePolicy.AutoPlay
        }

    private fun createOpenPlaybackSession(ports: PostConnectRepositories) = OpenPlaybackSessionUseCase(
        ports.profiles,
        object : PlaybackRepository by selectionFixtureUnused() {
            override suspend fun openSession(bookId: LibraryItemId): AppResult<PlaybackSession> {
                postConnectSessionOpens += 1
                return AppResult.Failure(AppError.Network())
            }
        },
        ports.library,
        RenewProfileSessionUseCase(selectionFixtureUnused<AuthRepository>()),
        RequireProfileReauthenticationUseCase(selectionFixtureUnused<AuthRepository>()),
    )
}

private inline fun <reified T> selectionFixtureUnused(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("Unexpected test repository call: ${method.name}") } as T
