package com.example.shelfplayer.feature.settings

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCapabilities
import com.example.shelfplayer.core.model.ServerCapability
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.download.DownloadHousekeeping
import com.example.shelfplayer.core.model.download.NetworkPolicy
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.CarReadiness
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.DevicePolicy
import com.example.shelfplayer.core.model.playback.KnownDevice
import com.example.shelfplayer.core.model.playback.NotificationAccess
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SleepTimerSession
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveServerDiagnosticsUseCase
import com.example.shelfplayer.launcher.LauncherIcon
import com.example.shelfplayer.launcher.LauncherIcons
import com.example.shelfplayer.playback.CarReadinessReader
import com.example.shelfplayer.playback.NotificationAccessReader
import com.example.shelfplayer.testing.FakePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PRODUCT_SPEC SET-001 / SET-002 / SYNC-001 — both settings tabs, from the one ViewModel behind them.
 *
 * The diagnostics assertions came from `AboutViewModelTest` when the About destination folded into a
 * tab. They moved with the thing they cover, so the coverage did not quietly stay behind on a screen
 * that no longer exists.
 */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val libraries = FakeLibraries()
    private val preferences = FakePreferences()
    private val diagnostics = FakeDiagnostics()
    private val capabilities = FakeCapabilities()
    private val sleepTimer = FakeSleepTimers()
    private val sessionSync = FakeSessionSync()

    /** PRODUCT_SPEC PLAY-001 — a platform read, so the ViewModel test supplies the answer rather than a device. */
    private val notifications = NotificationAccessReader { NotificationAccess() }

    /**
     * PRODUCT_SPEC ROUTE-002 — the same trick for the car reading, which is four package-manager queries.
     *
     * A correctly declared build that no car has ever reached: the state the About tab is most often read
     * in, and the one the device reports have all been about.
     */
    private val car = CarReadinessReader {
        CarReadiness(isDeclared = true, hasBrowserService = true, isAndroidAutoInstalled = true)
    }
    private val playbackSettings = FakePlaybackSettings()

    private fun viewModel() = SettingsViewModel(
        observeLibraries = ObserveLibrariesUseCase(profiles, libraries),
        observeServerDiagnostics = ObserveServerDiagnosticsUseCase(profiles, capabilities, StubRealtime()),
        diagnostics = diagnostics,
        preferences = preferences,
        sleepTimer = sleepTimer,
        sessionSync = sessionSync,
        device = DeviceReaders(notifications = notifications, car = car, launcherIcons = launcherIcons),
        playbackSettings = playbackSettings,
        devices = knownDevices,
    )

    private val knownDevices = FakeDevices()

    /** PRODUCT_SPEC ROUTE-002 — the list is what the store says, ordered by the store. */
    @Test
    fun `the devices this app has seen are listed`() = runTest {
        knownDevices.emit(listOf(earbuds(), wired()))

        viewModel().knownDevices.test {
            assertEquals(
                listOf("bluetooth:earbuds", "wired"),
                (
                    awaitItem().takeIf { it.isNotEmpty() }
                        ?: awaitItem()
                    ).map { it.id },
            )
        }
    }

    /** `Auto-play` is chosen per device and nowhere else — this is the only path that sets it. */
    @Test
    fun `choosing a policy stores it against that device`() = runTest {
        knownDevices.emit(listOf(earbuds()))
        val viewModel = viewModel()

        viewModel.onDevicePolicyChanged("bluetooth:earbuds", DevicePolicy.AutoPlay)

        assertEquals(listOf("bluetooth:earbuds" to DevicePolicy.AutoPlay), knownDevices.policies)
    }

    @Test
    fun `forgetting a device removes it`() = runTest {
        knownDevices.emit(listOf(earbuds()))
        val viewModel = viewModel()

        viewModel.onDeviceForgotten("bluetooth:earbuds")

        assertEquals(listOf("bluetooth:earbuds"), knownDevices.forgotten)
    }

    private fun earbuds() = KnownDevice(
        id = "bluetooth:earbuds",
        displayName = "Earbuds",
        kind = DeviceKind.Bluetooth,
        policy = DevicePolicy.ArmOnly,
        lastSeenAt = Instant.EPOCH,
    )

    private fun wired() = KnownDevice(
        id = "wired",
        displayName = "Wired headphones",
        kind = DeviceKind.Wired,
        policy = DevicePolicy.ArmOnly,
        lastSeenAt = Instant.EPOCH,
    )

    private val launcherIcons = FakeLauncherIcons()

    /** PRODUCT_SPEC SET-003 — the picker reflects what the launcher would draw, not what was tapped. */
    @Test
    fun `choosing a launcher icon applies it and reports it back`() = runTest {
        val viewModel = viewModel()
        assertEquals(LauncherIcon.Default, viewModel.launcherIcon.value)

        viewModel.onLauncherIconChanged(LauncherIcon.Vintage)

        assertEquals(LauncherIcon.Vintage, viewModel.launcherIcon.value)
        assertEquals(listOf(LauncherIcon.Vintage), launcherIcons.applied)
    }

    /**
     * A package manager that refuses the write leaves the picker on the icon the home screen still
     * shows. Moving the tick anyway would be the settings screen lying about the device.
     */
    @Test
    fun `a refused change leaves the picker where it was`() = runTest {
        launcherIcons.refuse()
        val viewModel = viewModel()

        viewModel.onLauncherIconChanged(LauncherIcon.Crimson)

        assertEquals(LauncherIcon.Default, viewModel.launcherIcon.value)
    }

    /**
     * PRODUCT_SPEC SET-002 — browsing by library lives here, as a list rather than a switch.
     *
     * The switch it replaced turned the home screen into something else, which meant going to Settings,
     * flipping it, and navigating back to find out what it did.
     */
    @Test
    fun `the libraries this profile may open are listed`() = runTest {
        libraries.emit(listOf(library("lib-fiction", "Fiction", 12), library("lib-nonfiction", "Non-fiction", 3)))

        viewModel().uiState.test {
            val state = awaitItem()
            assertEquals(listOf("Fiction", "Non-fiction"), state.libraries.map { it.name })
            assertEquals(listOf(12, 3), state.libraries.map { it.bookCount })
        }
    }

    /** PRODUCT_SPEC 6.1 step 9 — starring a library records it as the one the app opens on. */
    @Test
    fun `choosing a default library stores it`() = runTest {
        libraries.emit(listOf(library("lib-fiction", "Fiction", 12)))
        val model = viewModel()

        model.uiState.test {
            assertNull(awaitItem().defaultLibraryId, "no default until one is chosen")
            model.onDefaultLibraryChanged(LibraryId("lib-fiction"))
            assertEquals(LibraryId("lib-fiction"), awaitItem().defaultLibraryId)
        }
    }

    /** Clearing it returns the shelf to every granted library, which is not the same as choosing one. */
    @Test
    fun `clearing the default library removes the choice entirely`() = runTest {
        libraries.emit(listOf(library("lib-fiction", "Fiction", 12)))
        val model = viewModel()
        model.onDefaultLibraryChanged(LibraryId("lib-fiction"))

        model.uiState.test {
            assertEquals(LibraryId("lib-fiction"), awaitItem().defaultLibraryId)
            model.onDefaultLibraryChanged(null)
            assertNull(awaitItem().defaultLibraryId)
        }
    }

    /**
     * PRODUCT_SPEC 5.2 — a default library the profile has lost is not shown as a default.
     *
     * The stored id survives the grant being revoked, and rendering it raw would put a filled star
     * beside nothing at all — the library is no longer in the list to draw it against.
     */
    @Test
    fun `a default library the profile can no longer see is not reported as the default`() = runTest {
        libraries.emit(listOf(library("lib-fiction", "Fiction", 12)))
        val model = viewModel()
        model.onDefaultLibraryChanged(LibraryId("lib-fiction"))

        libraries.emit(listOf(library("lib-nonfiction", "Non-fiction", 3)))

        model.uiState.test {
            assertNull(awaitItem().defaultLibraryId)
        }
    }

    /** Zeroes before the first read would look like facts, so the screen says it is still reading. */
    @Test
    fun `the libraries are not reported until they have been read`() = runTest {
        assertFalse(SettingsUiState().isLoaded)

        viewModel().uiState.test {
            assertTrue(awaitItem().isLoaded)
        }
    }

    // --- PRODUCT_SPEC SET-002 / SYNC-001: the About tab's readings -------------------------------

    /**
     * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — the counts that used to need `adb … sqlite3`.
     *
     * The pair that matters is stored-against-visible. "Unauthorized libraries never appear" is really
     * "unauthorized rows were never written", and a screen that hides a row looks exactly like one that
     * never had it — so the difference between the two numbers is the only thing that can tell them apart.
     */
    @Test
    fun `storage counts distinguish what is stored from what this profile can see`() = runTest {
        diagnostics.emit(
            StorageDiagnostics(
                serversStored = 1,
                profilesStored = 2,
                storedCredentials = 2,
                librariesStored = 2,
                librariesAccessible = 1,
                booksStored = 490,
                booksAccessible = 188,
                booksSoftDeleted = 3,
            ),
        )

        viewModel().uiState.test {
            val storage = awaitItem().storage
            assertEquals(1, storage.serversStored, "two accounts on one server count once")
            assertEquals(2, storage.librariesStored)
            assertEquals(1, storage.librariesAccessible)
            assertEquals(490, storage.booksStored)
            assertEquals(188, storage.booksAccessible)
        }
    }

    /**
     * PRODUCT_SPEC SYNC-001 — "the compatibility result is visible in diagnostics".
     *
     * The distinction the screen exists to draw: a handshake that confirmed nothing and a handshake
     * that never ran produce the same empty set, and only one of them means "this server cannot do it".
     */
    @Test
    fun `diagnostics distinguish an unchecked server from one that confirmed nothing`() = runTest {
        val model = viewModel()

        model.uiState.test {
            assertFalse(assertNotNull(awaitItem().server).hasHandshake, "no probe has run yet")

            capabilities.stored.value = ServerCapabilities(
                serverId = ServerId("srv_books"),
                serverVersion = "2.36.0",
                supported = setOf(ServerCapability.Websocket),
                authMethods = listOf("local"),
            )

            val server = assertNotNull(awaitItem().server)
            assertTrue(server.hasHandshake)
            assertEquals("2.36.0", server.reportedVersion)
            assertEquals(listOf("local"), server.authMethods)
            assertEquals(setOf(ServerCapability.Websocket), server.confirmed)
        }
    }

    /** Every capability is listed, so "not confirmed" is visible rather than merely absent. */
    @Test
    fun `every known capability is reported, confirmed or not`() = runTest {
        capabilities.stored.value = ServerCapabilities(
            serverId = ServerId("srv_books"),
            serverVersion = null,
            supported = setOf(ServerCapability.Websocket),
        )

        viewModel().uiState.test {
            val server = assertNotNull(awaitItem().server)
            assertEquals(ServerCapability.entries.size, server.allCapabilities.size)
            assertEquals(1, server.allCapabilities.count { (_, confirmed) -> confirmed })
        }
    }

    private fun library(id: String, name: String, bookCount: Int) = Library(
        serverId = ServerId("srv_books"),
        id = LibraryId(id),
        name = name,
        kind = LibraryKind.Book,
        displayOrder = 0,
        bookCount = bookCount,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
    )

    /** PRODUCT_SPEC SYNC-001 — a handshake the test dictates, including "there has not been one". */
    private class FakeCapabilities : CapabilityRepository {

        /** Nothing in these tests downloads a file, so nothing observes a capability. */
        override suspend fun record(
            serverId: ServerId,
            capability: ServerCapability,
            isSupported: Boolean,
        ): AppResult<Unit> = AppResult.Success(Unit)
        val stored = MutableStateFlow<ServerCapabilities?>(null)

        override fun observeCapabilities(serverId: ServerId): Flow<ServerCapabilities?> = stored

        override suspend fun capabilities(serverId: ServerId): ServerCapabilities? = stored.value

        override suspend fun handshake(profileId: ProfileId): AppResult<ServerCapabilities> =
            AppResult.Failure(AppError.Network())
    }

    /** The socket is not the subject here; the diagnostics row just has to read whatever it reports. */
    private class StubRealtime : RealtimeUpdates {
        override val status = MutableStateFlow(RealtimeStatus.Idle)

        override fun events(profileId: ProfileId): Flow<RealtimeEvent> = emptyFlow()
    }

    private class FakeDiagnostics : DiagnosticsRepository {
        private val storage = MutableStateFlow(StorageDiagnostics())

        fun emit(value: StorageDiagnostics) {
            storage.value = value
        }

        override fun observeStorage(): Flow<StorageDiagnostics> = storage
    }

    private class FakeProfiles : ProfileRepository {
        private val active = MutableStateFlow<Profile?>(
            Profile(
                id = ProfileId("prf_ada"),
                serverId = ServerId("srv_books"),
                username = "ada",
                displayName = "ada",
                role = ProfileRole.Listener,
                requiresReauthentication = false,
                lastUsedAt = Instant.EPOCH,
                isFixture = false,
            ),
        )

        override fun observeProfiles(): Flow<List<Profile>> = MutableStateFlow(emptyList())

        override fun observeServers(): Flow<List<Server>> = MutableStateFlow(emptyList())

        override fun observeActiveProfile(): Flow<Profile?> = active

        override suspend fun activeProfileId(): ProfileId? = active.value?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeLibraries : LibraryRepository {
        private val stored = MutableStateFlow<List<Library>>(emptyList())

        fun emit(libraries: List<Library>) {
            stored.value = libraries
        }

        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = stored

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> = MutableStateFlow(null)

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            MutableStateFlow(emptyList())

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = MutableStateFlow(emptyList())

        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId) =

            kotlinx.coroutines.flow.flowOf(emptyList<com.example.shelfplayer.core.model.library.Chapter>())

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = MutableStateFlow(null)

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
            MutableStateFlow(SyncState.idle(ServerId("srv_books"), profileId))

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> = AppResult.Success(0)

        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
            AppResult.Success(0)

        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> = AppResult.Success(0)
    }
}

/**
 * PRODUCT_SPEC PLAY-008 — the sleep timer's settings and history, in memory.
 *
 * A fake rather than a mock, per PRODUCT_SPEC 17.1. It stores what it is told, so a test can assert
 * that the ViewModel wrote what the user asked for rather than that a method was called.
 */
/** PRODUCT_SPEC PLAY-004 / PLAY-005 — the outbox's readings, as the About tab receives them. */
/** PRODUCT_SPEC PLAY-006 / PLAY-007 / PLAY-009 — the playback controls, as the Playback tab receives them. */
internal class FakePlaybackSettings : PlaybackSettingsRepository {
    private val controls = MutableStateFlow(PlaybackSettings.Default)
    private val network = MutableStateFlow(NetworkPolicy.Default)
    private val housekeeping = MutableStateFlow(DownloadHousekeeping.Default)

    override fun observeSettings(): Flow<PlaybackSettings> = controls

    override fun observeNetworkPolicy(): Flow<NetworkPolicy> = network

    override suspend fun setNetworkPolicy(policy: NetworkPolicy): AppResult<Unit> {
        network.value = policy
        return AppResult.Success(Unit)
    }

    override fun observeHousekeeping(): Flow<DownloadHousekeeping> = housekeeping

    override suspend fun setHousekeeping(housekeeping: DownloadHousekeeping): AppResult<Unit> {
        this.housekeeping.value = housekeeping
        return AppResult.Success(Unit)
    }

    override suspend fun setDefaultSpeed(speed: PlaybackSpeed): AppResult<Unit> {
        controls.value = controls.value.copy(defaultSpeed = speed)
        return AppResult.Success(Unit)
    }

    override suspend fun setSkipIntervals(skips: SkipIntervals): AppResult<Unit> {
        controls.value = controls.value.copy(skips = skips)
        return AppResult.Success(Unit)
    }

    override suspend fun setAutoRewind(rewind: AutoRewind): AppResult<Unit> {
        controls.value = controls.value.copy(autoRewind = rewind)
        return AppResult.Success(Unit)
    }

    override suspend fun setBufferPreset(preset: BufferPreset): AppResult<Unit> {
        controls.value = controls.value.copy(buffer = preset)
        return AppResult.Success(Unit)
    }

    override suspend fun setAutoPlayOnCarConnect(enabled: Boolean): AppResult<Unit> {
        controls.value = controls.value.copy(autoPlayOnCarConnect = enabled)
        return AppResult.Success(Unit)
    }

    override fun observeSpeedFor(bookId: com.example.shelfplayer.core.model.LibraryItemId): Flow<PlaybackSpeed?> =
        MutableStateFlow(null)

    override suspend fun speedFor(bookId: com.example.shelfplayer.core.model.LibraryItemId): PlaybackSpeed =
        controls.value.defaultSpeed

    override suspend fun setSpeedFor(
        bookId: com.example.shelfplayer.core.model.LibraryItemId,
        speed: PlaybackSpeed?,
    ): AppResult<Unit> = AppResult.Success(Unit)
}

internal class FakeSessionSync : SessionSyncRepository {
    private val diagnostics = MutableStateFlow(SessionSyncDiagnostics())

    override fun observeDiagnostics(): Flow<SessionSyncDiagnostics> = diagnostics

    fun emit(reading: SessionSyncDiagnostics) {
        diagnostics.value = reading
    }

    override suspend fun openSession(
        bookId: com.example.shelfplayer.core.model.LibraryItemId,
        remoteSessionId: String?,
        title: String,
        author: String?,
        position: kotlin.time.Duration,
        duration: kotlin.time.Duration,
        startedAt: java.time.Instant,
    ): AppResult<String> = AppResult.Success("session-1")

    override suspend fun syncOpenSession(
        sessionId: String,
        progress: SessionProgress,
        updatedAt: java.time.Instant,
        trigger: SyncTrigger,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun closeSession(
        sessionId: String,
        progress: SessionProgress,
        updatedAt: java.time.Instant,
        trigger: SyncTrigger,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun drainOutbox(): AppResult<Int> = AppResult.Success(0)
}

internal class FakeSleepTimers : SleepTimerRepository {
    private val settings = MutableStateFlow(SleepTimerSettings.Default)
    private val sessions = MutableStateFlow<List<SleepTimerSession>>(emptyList())

    override fun observeSettings(): Flow<SleepTimerSettings> = settings

    override suspend fun setDefaultLength(length: kotlin.time.Duration): AppResult<Unit> {
        settings.value = settings.value.copy(defaultLength = length)
        return AppResult.Success(Unit)
    }

    override suspend fun setFadeLength(length: kotlin.time.Duration): AppResult<Unit> {
        settings.value = settings.value.copy(fadeLength = length)
        return AppResult.Success(Unit)
    }

    override suspend fun setRewindOnStop(length: kotlin.time.Duration): AppResult<Unit> {
        settings.value = settings.value.copy(rewindOnStop = length)
        return AppResult.Success(Unit)
    }

    override suspend fun setShakeToRestart(enabled: Boolean): AppResult<Unit> {
        settings.value = settings.value.copy(shakeToRestart = enabled)
        return AppResult.Success(Unit)
    }

    override fun observeRecentSessions(limit: Int): Flow<List<SleepTimerSession>> = sessions

    fun emit(recorded: List<SleepTimerSession>) {
        sessions.value = recorded
    }

    override suspend fun recordStarted(
        bookId: com.example.shelfplayer.core.model.LibraryItemId,
        mode: SleepTimerMode,
    ): AppResult<String> = AppResult.Success("session-1")

    override suspend fun recordRestarted(sessionId: String): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun recordEnded(sessionId: String, outcome: SleepTimerOutcome): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun closeOrphanedSessions(): AppResult<Int> = AppResult.Success(0)
}

/**
 * PRODUCT_SPEC SET-003 — a package manager that records rather than one that exists.
 *
 * `AndroidLauncherIcons` is tested against the real merged manifest in `LauncherIconsTest`; what this
 * ViewModel has to get right is different — that it re-reads the state rather than assuming its own
 * write landed — so [refuse] gives it a device that says no.
 */
/** PRODUCT_SPEC ROUTE-002 — the known-device store, recording what the screen asked it to change. */
internal class FakeDevices : DeviceRepository {
    private val stored = MutableStateFlow<List<KnownDevice>>(emptyList())
    val policies = mutableListOf<Pair<String, DevicePolicy>>()
    val forgotten = mutableListOf<String>()

    fun emit(devices: List<KnownDevice>) {
        stored.value = devices
    }

    override fun observeDevices(): Flow<List<KnownDevice>> = stored

    override suspend fun remember(device: KnownDevice): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun setPolicy(deviceId: String, policy: DevicePolicy): AppResult<Unit> {
        policies += deviceId to policy
        return AppResult.Success(Unit)
    }

    override suspend fun forget(deviceId: String): AppResult<Unit> {
        forgotten += deviceId
        return AppResult.Success(Unit)
    }

    override suspend fun policyFor(deviceId: String): DevicePolicy =
        stored.value.firstOrNull { it.id == deviceId }?.policy ?: DevicePolicy.Default
}

internal class FakeLauncherIcons : LauncherIcons {
    val applied = mutableListOf<LauncherIcon>()
    private var stored = LauncherIcon.Default
    private var accepts = true

    fun refuse() {
        accepts = false
    }

    override fun current(): LauncherIcon = stored

    override fun apply(icon: LauncherIcon) {
        applied += icon
        if (accepts) stored = icon
    }
}
