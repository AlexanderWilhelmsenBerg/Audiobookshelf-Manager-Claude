package com.example.shelfplayer.feature.home

import app.cash.turbine.test
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveAccessibleBooksUseCase
import com.example.shelfplayer.domain.usecase.ObserveSyncStateUseCase
import com.example.shelfplayer.domain.usecase.RefreshLibraryUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** PRODUCT_SPEC LIB-001 / 21 — home distinguishes loading, empty, content and error. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val libraries = FakeLibraries()

    private fun viewModel() = HomeViewModel(
        observeAccessibleBooks = ObserveAccessibleBooksUseCase(profiles, libraries),
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

    /**
     * PRODUCT_SPEC LIB-001 — an abandoned sync is picked up rather than waited on forever.
     *
     * A `sync_state` row saying `Syncing` while nothing here is running one means the process that started
     * it is gone. Before this, home read that as "a sync is in progress", showed a progress bar for a sync
     * that would never finish, and refused to start its own because the status was not `NeverSynced`. That
     * is the "empty library until I pressed refresh" report.
     */
    @Test
    fun `a sync recorded as running but abandoned is restarted`() = runTest {
        profiles.setActive(demoProfile)
        libraries.setSyncState(
            SyncState(
                serverId = ServerId("fixture-server"),
                profileId = demoProfile.id,
                status = SyncStatus.Syncing,
                lastSuccessfulSyncAt = null,
                lastAttemptedAt = Instant.EPOCH,
                lastError = null,
            ),
        )
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()
            viewModel.onVisible()
            viewModel.onVisible()

            assertEquals(1, libraries.refreshCount, "adopted once, not on a loop")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A library that already synced is not re-synced behind the user's back on every visit. */
    @Test
    fun `a profile that has already synced is left alone`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emitBooks(listOf(book("cached", "A cached book")))
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
        assertEquals(emptyList(), state.books)
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
        libraries.emitBooks(listOf(book("cached", "A cached book")))
        val gate = CompletableDeferred<Unit>()
        libraries.gate = gate

        val viewModel = viewModel()
        viewModel.uiState.test {
            awaitItem()
            viewModel.refresh()

            val syncing = awaitItem()
            assertEquals(true, syncing.isRefreshing)
            assertEquals(listOf("A cached book"), syncing.books.map { it.title })
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
    fun `shows cached books once they arrive`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emitBooks(listOf(book("cached", "A cached book")))

        viewModel().uiState.test {
            val state = awaitItem()
            assertEquals(listOf("A cached book"), state.books.map { it.title })
            assertEquals(SyncStatus.Succeeded, state.syncStatus)
        }
    }

    /**
     * PRODUCT_SPEC LIB-002 — reported from a device: the app opened on a single library card with the
     * rest of the screen empty, and the books were one tap further in.
     *
     * The shelf is what the app opens on, ordered by what was played last.
     */
    @Test
    fun `the shelf opens on the books, most recently played first`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emitBooks(
            listOf(
                book("cold", "Never opened"),
                book("older", "Older", playedAt = Instant.EPOCH.plusSeconds(10)),
                book("newest", "Newest", playedAt = Instant.EPOCH.plusSeconds(20)),
            ),
        )

        viewModel().uiState.test {
            assertEquals(listOf("Newest", "Older", "Never opened"), awaitItem().books.map { it.title })
        }
    }

    /**
     * PRODUCT_SPEC LIB-002 — search narrows the shelf, and the 300 ms debounce applies to the *results*
     * rather than to the text field.
     *
     * The typed text has to appear at once — a field that lags behind the keyboard feels broken — while
     * the list settles before it re-queries. Both halves are asserted here because a naive fix to either
     * one breaks the other.
     */
    @Test
    fun `searching narrows the shelf without the text field lagging`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emitBooks(listOf(book("salt", "The Salt Harbour"), book("glass", "The Weather Glass")))

        val viewModel = viewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect { } }
        assertEquals(2, viewModel.uiState.value.books.size)

        viewModel.onQueryChanged("weather")

        assertEquals("weather", viewModel.uiState.value.query, "the field must not wait for the debounce")

        // Comfortably past LIB-002's 300 ms debounce, so the assertion is not about the exact boundary.
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("The Weather Glass"), viewModel.uiState.value.books.map { it.title })
    }

    @Test
    fun `a refresh failure surfaces the typed error without clearing content`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emitBooks(listOf(book("cached", "A cached book")))
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
            assertEquals(listOf("A cached book"), failed.books.map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissing an error clears it`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emitBooks(listOf(book("cached", "A cached book")))
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

    private fun book(id: String, title: String, playedAt: Instant? = null) = Book(
        serverId = ServerId("fixture-server"),
        id = LibraryItemId(id),
        libraryId = LibraryId("lib-fiction"),
        title = title,
        subtitle = null,
        authors = emptyList(),
        narrators = emptyList(),
        seriesMemberships = emptyList(),
        duration = Duration.ZERO,
        description = null,
        genres = emptyList(),
        tags = emptyList(),
        publishedYear = null,
        publisher = null,
        language = null,
        isExplicit = false,
        isAbridged = false,
        coverPath = null,
        trackCount = 1,
        sizeBytes = 0,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
        progress = playedAt?.let {
            MediaProgress(
                serverId = ServerId("fixture-server"),
                profileId = ProfileId("fixture-profile"),
                bookId = LibraryItemId(id),
                position = 1.minutes,
                duration = 10.minutes,
                isFinished = false,
                updatedAt = it,
                hasUnsyncedChanges = false,
            )
        },
        localAvailability = LocalAvailability.NotDownloaded,
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

        override fun observeServers(): Flow<List<Server>> = MutableStateFlow(emptyList())

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

        private val storedBooks = MutableStateFlow<List<Book>>(emptyList())

        fun emitBooks(books: List<Book>) {
            storedBooks.value = books
        }

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            storedBooks.map { all -> all.filter { it.libraryId == libraryId } }

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = storedBooks

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
