package com.example.shelfplayer.feature.home

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveSyncStateUseCase
import com.example.shelfplayer.domain.usecase.RefreshLibraryUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** PRODUCT_SPEC LIB-001 / 21 — home distinguishes loading, empty, content and error. */
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val libraries = FakeLibraries()

    private fun viewModel() = HomeViewModel(
        observeLibraries = ObserveLibrariesUseCase(profiles, libraries),
        observeSyncState = ObserveSyncStateUseCase(profiles, libraries),
        profileRepository = profiles,
        refreshLibrary = RefreshLibraryUseCase(profiles, libraries, NeverRenewingAuth()),
    )

    /**
     * The bug a real device found: signing in ran a sync, it failed, and home showed a generic empty
     * library. The failure was recorded in `sync_state` and never read.
     */
    @Test
    fun `a failed sync the user did not start is still shown`() = runTest {
        profiles.setActive(demoProfile)
        libraries.setSyncState(
            SyncState(
                serverId = ServerId("fixture-server"),
                profileId = demoProfile.id,
                status = SyncStatus.Failed,
                lastSuccessfulSyncAt = null,
                lastAttemptedAt = Instant.EPOCH,
                lastError = AppError.Timeout(),
            ),
        )

        viewModel().uiState.test {
            val state = awaitItem()
            assertEquals(SyncStatus.Failed, state.syncStatus)
            assertEquals("timeout", state.error?.code)
        }
    }

    /** PRODUCT_SPEC LIB-001 — a profile that has never synced gets one attempt without being asked. */
    @Test
    fun `a profile that has never synced is synced once on arrival`() = runTest {
        profiles.setActive(demoProfile)
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()
            viewModel.onVisible()
            viewModel.onVisible()

            assertEquals(1, libraries.refreshCount, "the initial sync must not be retried in a loop")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A library that already synced is not re-synced behind the user's back on every visit. */
    @Test
    fun `a profile that has already synced is left alone`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(demoLibrary))
        libraries.setSyncState(
            SyncState(
                serverId = ServerId("fixture-server"),
                profileId = demoProfile.id,
                status = SyncStatus.Succeeded,
                lastSuccessfulSyncAt = Instant.EPOCH,
                lastAttemptedAt = Instant.EPOCH,
                lastError = null,
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()
            viewModel.onVisible()

            assertEquals(0, libraries.refreshCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * PRODUCT_SPEC 21 — with no profile there is nothing loading, so the screen must not claim there is.
     *
     * This asserted the opposite while the demo-library bootstrapper guaranteed a profile would appear
     * shortly. Nothing produces one now without a sign-in, so a spinner here would never resolve.
     */
    @Test
    fun `no profile is an empty state, not a permanent spinner`() = runTest {
        val state = viewModel().uiState.value

        assertNull(state.profile)
        assertEquals(emptyList(), state.libraries)
        assertNull(state.error)
    }

    /**
     * PRODUCT_SPEC LIB-001 — reported from a device: the app made the user wait for the sync before
     * showing the library it already had.
     *
     * A refresh in flight is reported as refreshing, and the cached content stays in the state. The screen
     * renders it under a progress bar rather than replacing it with a spinner.
     */
    @Test
    fun `a sync in flight does not hide the cached library`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(demoLibrary))
        val gate = CompletableDeferred<Unit>()
        libraries.gate = gate

        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()
            viewModel.refresh()

            val syncing = awaitItem()
            assertEquals(true, syncing.isRefreshing)
            assertEquals(listOf("Fiction"), syncing.libraries.map { it.name })
            assertEquals(true, syncing.isLoaded, "the screen must not fall back to its blocking state")

            gate.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The one blocking state is Room answering, not the network. */
    @Test
    fun `the blocking state ends as soon as the database answers`() = runTest {
        profiles.setActive(demoProfile)
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.isLoaded, "the initial value is the pre-database frame")

        viewModel.uiState.test {
            assertEquals(true, awaitItem().isLoaded)
        }
    }

    @Test
    fun `shows cached libraries once they arrive`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(demoLibrary))

        viewModel().uiState.test {
            val state = awaitItem()
            assertEquals(listOf("Fiction"), state.libraries.map { it.name })
            assertEquals(SyncStatus.Succeeded, state.syncStatus)
        }
    }

    @Test
    fun `a refresh failure surfaces the typed error without clearing content`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(demoLibrary))
        libraries.refreshResult = AppResult.Failure(AppError.Network())

        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()

            viewModel.refresh()

            // PRODUCT_SPEC 21 — a refresh in flight is its own observable state, so one refresh
            // emits twice: syncing, then the outcome. Asserted rather than skipped, because the
            // spinner appearing at all is a requirement and this is the only test that can see it.
            val syncing = awaitItem()
            assertEquals(SyncStatus.Syncing, syncing.syncStatus)
            assertNull(syncing.error)

            val failed = awaitItem()
            assertEquals("network", failed.error?.code)
            assertEquals(SyncStatus.Failed, failed.syncStatus)
            // PRODUCT_SPEC LIB-001: cached content stays on screen.
            assertEquals(listOf("Fiction"), failed.libraries.map { it.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissing an error clears it`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(demoLibrary))
        libraries.refreshResult = AppResult.Failure(AppError.Network())

        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()

            viewModel.refresh()
            assertEquals(SyncStatus.Syncing, awaitItem().syncStatus)
            assertEquals("network", awaitItem().error?.code)

            viewModel.dismissError()
            assertNull(awaitItem().error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A double tap on refresh must not start two syncs (PRODUCT_SPEC MGR-004's debounce rule). */
    @Test
    fun `refresh is ignored while one is already running`() = runTest {
        profiles.setActive(demoProfile)
        val gate = CompletableDeferred<Unit>()
        libraries.gate = gate

        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()
            viewModel.refresh()
            viewModel.refresh()
            assertEquals(1, libraries.refreshCount)
            gate.complete(Unit)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private val demoProfile = Profile(
        id = ProfileId("fixture-profile"),
        serverId = ServerId("fixture-server"),
        username = "demo",
        displayName = "Demo listener",
        role = ProfileRole.Listener,
        requiresReauthentication = false,
        lastUsedAt = Instant.EPOCH,
        isFixture = true,
    )

    private val demoLibrary = Library(
        serverId = ServerId("fixture-server"),
        id = LibraryId("lib-fiction"),
        name = "Fiction",
        kind = LibraryKind.Book,
        displayOrder = 0,
        bookCount = 5,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
    )

    /**
     * PRODUCT_SPEC AUTH-004 — home's refresh path can renew a session, and none of these tests are
     * about that. Renewal reports "sign in again" so the use case takes its no-retry branch, keeping
     * every refresh here a single call.
     */
    private class NeverRenewingAuth : AuthRepository {
        override suspend fun renewSession(profileId: ProfileId): AppResult<SessionStatus> =
            AppResult.Success(SessionStatus.ReauthenticationRequired)

        override suspend fun probeServer(serverUrl: String): AppResult<ServerCandidate> = notUsed()

        override suspend fun signIn(serverUrl: String, username: String, password: String): AppResult<Profile> =
            notUsed()

        override suspend fun restoreSession(profileId: ProfileId): AppResult<SessionStatus> = notUsed()

        override suspend fun signOut(profileId: ProfileId): AppResult<Unit> = notUsed()

        override suspend fun removeProfile(profileId: ProfileId): AppResult<Unit> = notUsed()

        private fun <T> notUsed(): AppResult<T> =
            AppResult.Failure(AppError.ApiCompatibility(summary = "not part of this fake"))
    }

    private class FakeProfiles : ProfileRepository {
        private val active = MutableStateFlow<Profile?>(null)

        fun setActive(profile: Profile?) {
            active.value = profile
        }

        override fun observeProfiles(): Flow<List<Profile>> = active.map(::listOfNotNull)

        override fun observeActiveProfile(): Flow<Profile?> = active

        override suspend fun activeProfileId(): ProfileId? = active.first()?.id

        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeLibraries : LibraryRepository {
        private val stored = MutableStateFlow<List<Library>>(emptyList())

        var refreshResult: AppResult<Int> = AppResult.Success(0)

        /** Lets a test hold a refresh open long enough to observe the in-flight guard. */
        var gate: CompletableDeferred<Unit>? = null
        var refreshCount: Int = 0
            private set

        fun emit(libraries: List<Library>) {
            stored.value = libraries
        }

        override fun observeLibraries(profileId: ProfileId): Flow<List<Library>> = stored

        override fun observeLibrary(profileId: ProfileId, libraryId: LibraryId): Flow<Library?> =
            stored.map { libraries -> libraries.firstOrNull { it.id == libraryId } }

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            MutableStateFlow(emptyList())

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> = MutableStateFlow(null)

        private val syncState = MutableStateFlow<SyncState?>(null)

        fun setSyncState(state: SyncState) {
            syncState.value = state
        }

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
            syncState.map { it ?: SyncState.idle(ServerId("fixture-server"), profileId) }

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> {
            refreshCount++
            gate?.await()
            return refreshResult
        }
    }
}
