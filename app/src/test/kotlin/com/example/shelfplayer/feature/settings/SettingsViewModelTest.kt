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
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SleepTimerSession
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveServerDiagnosticsUseCase
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

    private fun viewModel() = SettingsViewModel(
        observeLibraries = ObserveLibrariesUseCase(profiles, libraries),
        observeServerDiagnostics = ObserveServerDiagnosticsUseCase(profiles, capabilities, StubRealtime()),
        diagnostics = diagnostics,
        preferences = preferences,
        sleepTimer = sleepTimer,
    )

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
