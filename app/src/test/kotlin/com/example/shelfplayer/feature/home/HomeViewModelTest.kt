package com.example.shelfplayer.feature.home

import app.cash.turbine.test
import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.common.log.DefaultRedactor
import com.example.shelfplayer.core.common.log.RedactingLogger
import com.example.shelfplayer.core.common.log.RedactionPolicy
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
import com.example.shelfplayer.core.model.auth.AccountBookmark
import com.example.shelfplayer.core.model.auth.AccountProgress
import com.example.shelfplayer.core.model.auth.AccountState
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.EmbedRequest
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.core.model.library.LibraryKind
import com.example.shelfplayer.core.model.library.LocalAvailability
import com.example.shelfplayer.core.model.library.MatchCandidate
import com.example.shelfplayer.core.model.library.MediaProgress
import com.example.shelfplayer.core.model.library.MetadataProvider
import com.example.shelfplayer.core.model.realtime.RealtimeEvent
import com.example.shelfplayer.core.model.realtime.RealtimeStatus
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.core.testing.RecordingLogSink
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.realtime.RealtimeUpdates
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.MetadataRepository
import com.example.shelfplayer.domain.repository.MetadataSaveResult
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.BrowseUseCases
import com.example.shelfplayer.domain.usecase.BulkEditGenresUseCase
import com.example.shelfplayer.domain.usecase.ObserveAccessibleBooksUseCase
import com.example.shelfplayer.domain.usecase.ObserveBookGroupsUseCase
import com.example.shelfplayer.domain.usecase.ObserveHomeShelvesUseCase
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveRealtimeUpdatesUseCase
import com.example.shelfplayer.domain.usecase.ObserveSeriesShelvesUseCase
import com.example.shelfplayer.domain.usecase.ObserveSyncStateUseCase
import com.example.shelfplayer.domain.usecase.RefreshLibraryUseCase
import com.example.shelfplayer.domain.usecase.SearchServerUseCase
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import com.example.shelfplayer.testing.FakePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** PRODUCT_SPEC LIB-001 / 21 — home distinguishes loading, empty, content and error. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val profiles = FakeProfiles()
    private val libraries = FakeLibraries()

    /**
     * PRODUCT_SPEC LIB-002 — a monitor the test drives, because "offline" is a state the UI has to be
     * shown in and a device is the one thing a unit test cannot have.
     */
    private val network = MutableStateFlow(true)

    private val networkMonitor = object : NetworkMonitor {
        /** Not exercised here: metering governs downloads, not metadata updates. */
        override val isUnmetered: Flow<Boolean> = flowOf(true)
        override val isOnline: Flow<Boolean> = network
    }

    /** PRODUCT_SPEC SET-001 — the shelf's sort order and default library live here now, not in the ViewModel. */
    private val preferences = FakePreferences()
    private val metadata = RecordingMetadata(libraries)

    /**
     * Home opens on the three shelves, so a test about the flat list has to ask for it.
     *
     * Only the visible axis is collected — that is the point of the `flatMapLatest` on the view — so
     * `uiState.books` is deliberately empty while the shelves are showing. A test that asserted on it
     * anyway would be asserting that the app does work nobody asked for.
     */
    private fun listViewModel() = viewModel().also { it.onBooksViewChanged(BooksView.List) }

    private fun viewModel() = HomeViewModel(
        browse = BrowseUseCases(
            books = ObserveAccessibleBooksUseCase(profiles, libraries, mainDispatcherRule.testDispatcher),
            shelves = ObserveHomeShelvesUseCase(profiles, libraries, mainDispatcherRule.testDispatcher),
            series = ObserveSeriesShelvesUseCase(profiles, libraries, mainDispatcherRule.testDispatcher),
            groups = ObserveBookGroupsUseCase(profiles, libraries, mainDispatcherRule.testDispatcher),
            searchServer = SearchServerUseCase(profiles, libraries),
        ),
        observeLibraries = ObserveLibrariesUseCase(profiles, libraries),
        observeSyncState = ObserveSyncStateUseCase(profiles, libraries),
        profileRepository = profiles,
        preferences = preferences,
        networkMonitor = networkMonitor,
        syncAccount = SyncAccountUseCase(profiles, NeverRenewingAuth(), libraries, StubBookmarks()),
        // A connection that never connects, which is the case PRODUCT_SPEC LIB-001 requires to be
        // uneventful: everything the socket would deliver also arrives over REST.
        observeRealtimeUpdates = ObserveRealtimeUpdatesUseCase(
            realtime = object : RealtimeUpdates {
                override val status = MutableStateFlow(RealtimeStatus.Idle)

                override fun events(profileId: ProfileId): Flow<RealtimeEvent> = emptyFlow()
            },
            libraryRepository = libraries,
            logger = RecordingLogSink().let { RedactingLogger(it, DefaultRedactor(RedactionPolicy.Default)) },
        ),
        refreshLibrary = RefreshLibraryUseCase(profiles, libraries, NeverRenewingAuth()),
        bulkEditGenres = BulkEditGenresUseCase(
            profiles = profiles,
            libraries = libraries,
            metadata = metadata,
            auth = NeverRenewingAuth(),
            network = networkMonitor,
        ),
    )

    /**
     * PRODUCT_SPEC LIB-002 — "empty, loading, error, and offline states are distinct".
     *
     * Distinct in the state, not only on the screen: a composable that inferred offline from the error
     * code would be guessing, and there is no error code for "the user walked into a lift".
     */
    @Test
    fun `losing the network is visible as offline rather than as an error`() = runTest {
        profiles.setActive(demoProfile)
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertFalse(awaitItem().isOffline)

            network.value = false

            assertEquals(true, awaitItem().isOffline)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * PRODUCT_SPEC LIB-002 / 6.3 — the network came back, so the failed attempt is retried.
     *
     * Asked for from a device in as many words: "when getting connectability when the server goes online
     * after being off". Without it the user is left holding an error until they think to pull down.
     */
    @Test
    fun `a failed profile refreshes itself when the network returns`() = runTest {
        profiles.setActive(demoProfile)
        libraries.setSyncState(
            SyncState(
                serverId = ServerId("fixture-server"),
                profileId = demoProfile.id,
                status = SyncStatus.Failed,
                lastSuccessfulSyncAt = null,
                lastAttemptedAt = Instant.EPOCH,
                lastError = AppError.Network(),
            ),
        )
        network.value = false
        val viewModel = viewModel()

        viewModel.uiState.test {
            awaitItem()
            runCurrent()
            val before = libraries.refreshCount

            network.value = true
            runCurrent()

            assertEquals(before + 1, libraries.refreshCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * …and a profile that is up to date is left alone.
     *
     * A phone hopping between Wi-Fi and mobile would otherwise re-sync the whole library on every hop,
     * which on the 490-book library a device run used is 491 requests for nothing. The radio changing is
     * not news about the library.
     */
    @Test
    fun `a healthy profile does not resync every time the radio changes`() = runTest {
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
            runCurrent()
            val before = libraries.refreshCount

            network.value = false
            runCurrent()
            network.value = true
            runCurrent()

            assertEquals(before, libraries.refreshCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

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

    /**
     * PRODUCT_SPEC LIB-001 / 5.2 — a past success is not a reason to skip this launch's sync.
     *
     * This test asserted the opposite, and the assertion was the defect: two device runs found that
     * switching to an account which had ever synced showed that account's old library until the user
     * found the refresh button. It matters more since item visibility became per profile — what an
     * account may see is established by its own sync, so an account that never syncs has an empty shelf
     * rather than merely a stale one.
     *
     * What still bounds it is [HomeViewModel.syncAttemptedFor]: once per profile per process, proved by
     * the test above.
     */
    @Test
    fun `a profile that synced on an earlier launch syncs again on this one`() = runTest {
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
            viewModel.onVisible()

            assertEquals(1, libraries.refreshCount, "synced once for this launch, and only once")
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

        val viewModel = listViewModel()
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

        listViewModel().uiState.test {
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

        listViewModel().uiState.test {
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

        val viewModel = listViewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect { } }
        assertEquals(2, viewModel.uiState.value.books.size)

        viewModel.onQueryChanged("weather")

        assertEquals("weather", viewModel.uiState.value.query, "the field must not wait for the debounce")

        // Comfortably past LIB-002's 300 ms debounce, so the assertion is not about the exact boundary.
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("The Weather Glass"), viewModel.uiState.value.books.map { it.title })
    }

    /**
     * PRODUCT_SPEC LIB-002 — "local cached results appear immediately; server search may enrich results".
     *
     * The server is asked once, for the settled query, not once per keystroke. Both halves matter: a
     * request per keystroke is four requests to answer one question, and no request at all means a book
     * added since the last sync cannot be found.
     */
    @Test
    fun `the settled query is the one the server is asked`() = runTest {
        profiles.setActive(demoProfile)
        val viewModel = listViewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect { } }

        viewModel.onQueryChanged("s")
        viewModel.onQueryChanged("sa")
        viewModel.onQueryChanged("salt")
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("salt"), libraries.serverQueries)
    }

    /** Clearing the field is not a search: an empty query would ask the server for the whole library. */
    @Test
    fun `closing the search field does not ask the server for everything`() = runTest {
        profiles.setActive(demoProfile)
        val viewModel = listViewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect { } }

        viewModel.onSearchToggled()
        viewModel.onQueryChanged("salt")
        advanceTimeBy(400)
        runCurrent()
        viewModel.onSearchToggled()
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("salt"), libraries.serverQueries, "the clear must not become a query")
    }

    @Test
    fun `a refresh failure surfaces the typed error without clearing content`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emitBooks(listOf(book("cached", "A cached book")))
        libraries.refreshResult = AppResult.Failure(AppError.Network())

        val viewModel = listViewModel()
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

    /** PRODUCT_SPEC MGR-008 — the UI hands one explicit profile and one confirmed genre edit to domain. */
    @Test
    fun `confirmed genre replacement invokes the use case and exposes its full result`() = runTest {
        val updateProfile = demoProfile.copy(canUpdate = true)
        val matching = book("space", "A Space Opera").copy(genres = listOf("Adventure", "Sci fi"))
        val outsideCurrentView = book("history", "A History of Mars").copy(genres = listOf(" SCI FI "))
        profiles.setActive(updateProfile)
        libraries.emitBooks(listOf(matching, outsideCurrentView))
        val viewModel = viewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect { } }
        runCurrent()
        viewModel.onAxisChanged(HomeAxis.Genres)
        runCurrent()

        viewModel.onGenreEditRequested(
            BookGroup(
                kind = BookGroupKind.Genre,
                key = "sci fi",
                label = "Sci fi",
                books = listOf(matching),
            ),
        )
        runCurrent()

        val confirming = viewModel.uiState.value.genreEdit as GenreEditUiState.Confirming
        assertEquals(updateProfile.id, confirming.request.profileId)
        assertEquals("Sci fi", confirming.request.sourceGenre)
        assertEquals(2, confirming.request.cachedMatchCount, "confirmation counts the profile, not a scoped card")

        viewModel.onGenreEditReplacementChanged("Science Fiction, Fantasy")
        viewModel.onGenreEditConfirmed()
        runCurrent()

        assertEquals(2, metadata.saves.size)
        assertEquals(
            setOf(updateProfile.id),
            metadata.saves.map { it.profileId }.toSet(),
            "every privileged write must keep its originating profile",
        )
        assertEquals(
            setOf(setOf(BookMetadataField.Genres)),
            metadata.saves.map { it.changed }.toSet(),
        )
        assertEquals(
            listOf("Adventure", "Science Fiction", "Fantasy"),
            metadata.saves.single { it.bookId == matching.id }.edit.genres,
        )
        val complete = viewModel.uiState.value.genreEdit as GenreEditUiState.Complete
        assertEquals(2, complete.summary.matchedCount)
        assertEquals(2, complete.summary.updatedCount)
        assertEquals(0, complete.summary.unprocessedCount)
    }

    /** An account-scoped confirmation must never follow the user across a profile switch. */
    @Test
    fun `switching profiles dismisses an unconfirmed genre edit`() = runTest {
        val updateProfile = demoProfile.copy(canUpdate = true)
        val matching = book("space", "A Space Opera").copy(genres = listOf("Sci fi"))
        profiles.setActive(updateProfile)
        libraries.emitBooks(listOf(matching))
        val viewModel = viewModel()
        val emissions = mutableListOf<HomeUiState>()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect(emissions::add) }
        runCurrent()
        viewModel.onAxisChanged(HomeAxis.Genres)
        runCurrent()
        viewModel.onGenreEditRequested(
            BookGroup(BookGroupKind.Genre, "sci fi", "Sci fi", listOf(matching)),
        )
        runCurrent()
        assertEquals(true, viewModel.uiState.value.genreEdit is GenreEditUiState.Confirming)

        val otherProfile = updateProfile.copy(id = ProfileId("other-profile"), username = "other")
        profiles.setActive(otherProfile)
        runCurrent()

        assertEquals(GenreEditUiState.Hidden, viewModel.uiState.value.genreEdit)
        assertEquals(emptyList(), metadata.saves)
        assertEquals(
            emptyList(),
            emissions.filter { state ->
                state.profile?.id == otherProfile.id && state.genreEdit != GenreEditUiState.Hidden
            },
            "no emitted frame may pair the new profile with the old account's genre operation",
        )
    }

    /** PRODUCT_SPEC 5.2 — switching accounts cancels a suspended privileged write and its draft recovery. */
    @Test
    fun `switching profiles cancels an in flight genre write without a draft`() = runTest {
        val updateProfile = demoProfile.copy(canUpdate = true)
        val matching = book("space", "A Space Opera").copy(genres = listOf("Sci fi"))
        profiles.setActive(updateProfile)
        libraries.emitBooks(listOf(matching))
        metadata.saveGate = CompletableDeferred()
        val viewModel = viewModel()
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { viewModel.uiState.collect { } }
        runCurrent()
        viewModel.onAxisChanged(HomeAxis.Genres)
        runCurrent()
        viewModel.onGenreEditRequested(
            BookGroup(BookGroupKind.Genre, "sci fi", "Sci fi", listOf(matching)),
        )
        runCurrent()
        viewModel.onGenreEditReplacementChanged("Science Fiction")
        viewModel.onGenreEditConfirmed()
        metadata.saveStarted.await()

        profiles.setActive(updateProfile.copy(id = ProfileId("other-profile"), username = "other"))
        runCurrent()
        metadata.saveGate?.complete(Unit)
        runCurrent()

        assertEquals(GenreEditUiState.Hidden, viewModel.uiState.value.genreEdit)
        assertTrue(metadata.saveWasCanceled)
        assertEquals(emptyList(), metadata.saves)
        assertEquals(emptyList(), metadata.savedDrafts)
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

    /**
     * PRODUCT_SPEC SET-001 — the order survives the ViewModel, because it no longer lives in it.
     *
     * The assertion is on the shelf rather than on a stored string on purpose: reading back what was
     * written proves only that the fake works. What matters is that the books come out in the order the
     * *preference* names, which is what a relaunch will show.
     */
    @Test
    fun `the shelf is ordered by the profile's stored preference`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emitBooks(
            listOf(
                book("z", "Zenith", playedAt = Instant.EPOCH.plusSeconds(20)),
                book("a", "Anchor"),
            ),
        )
        preferences.setSortOrder(libraryId = null, order = BookSortOrder.TitleAscending.name)

        listViewModel().uiState.test {
            val state = awaitItem()
            assertEquals(BookSortOrder.TitleAscending, state.order)
            assertEquals(listOf("Anchor", "Zenith"), state.books.map { it.title })
        }
    }

    /** A profile that has never chosen opens on what it was listening to, not on A–Z. */
    @Test
    fun `a profile with no stored order opens on last played`() = runTest {
        profiles.setActive(demoProfile)

        viewModel().uiState.test {
            assertEquals(BookSortOrder.LastPlayed, awaitItem().order)
        }
    }

    /**
     * The chip moves because the write landed, which is the whole point of the order living in the
     * store: there is no local copy to move on its own.
     *
     * Asserted on `order` rather than on the book list because the two arrive in separate emissions —
     * the preference reaches the state before the re-queried shelf does, and waiting for the shelf here
     * would be asserting on `combine`'s scheduling. The ordering itself is covered above.
     */
    @Test
    fun `changing the order persists it`() = runTest {
        profiles.setActive(demoProfile)
        val model = viewModel()

        model.uiState.test {
            assertEquals(BookSortOrder.LastPlayed, awaitItem().order)
            model.onOrderChanged(BookSortOrder.TitleAscending)
            assertEquals(BookSortOrder.TitleAscending, awaitItem().order)
        }
    }

    /** PRODUCT_SPEC 6.1 step 9 — with a default library chosen, the shelf is that library. */
    @Test
    fun `a default library narrows the shelf and names itself`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(library("lib-fiction", "Fiction"), library("lib-nonfiction", "Non-fiction")))
        libraries.emitBooks(
            listOf(
                book("f", "A novel"),
                book("n", "A history", libraryId = LibraryId("lib-nonfiction")),
            ),
        )
        preferences.setDefaultLibrary(LibraryId("lib-nonfiction"))

        listViewModel().uiState.test {
            val state = awaitItem()
            assertEquals(listOf("A history"), state.books.map { it.title })
            assertEquals("Non-fiction", state.scopedTo?.name, "the shelf says which library it is showing")
        }
    }

    /**
     * PRODUCT_SPEC 5.2 / 6.1 step 9 — losing access to the default library widens the shelf, never
     * empties it.
     *
     * The stored id outlives the grant. Trusting it would leave the user staring at an empty shelf with
     * the explanation two screens away in Settings, which is indistinguishable from a failed sync.
     */
    @Test
    fun `a default library the profile can no longer see falls back to every library`() = runTest {
        profiles.setActive(demoProfile)
        libraries.emit(listOf(library("lib-fiction", "Fiction")))
        libraries.emitBooks(
            listOf(book("f", "A novel"), book("n", "A history", libraryId = LibraryId("lib-nonfiction"))),
        )
        preferences.setDefaultLibrary(LibraryId("lib-nonfiction"))

        listViewModel().uiState.test {
            val state = awaitItem()
            assertEquals(listOf("A history", "A novel"), state.books.map { it.title }.sorted())
            assertNull(state.scopedTo)
        }
    }

    private fun library(id: String, name: String) = Library(
        serverId = ServerId("fixture-server"),
        id = LibraryId(id),
        name = name,
        kind = LibraryKind.Book,
        displayOrder = 0,
        bookCount = 0,
        remoteUpdatedAt = null,
        lastFetchedAt = Instant.EPOCH,
    )

    private fun book(
        id: String,
        title: String,
        playedAt: Instant? = null,
        libraryId: LibraryId = LibraryId("lib-fiction"),
    ) = Book(
        serverId = ServerId("fixture-server"),
        id = LibraryItemId(id),
        libraryId = libraryId,
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
        isbn = null,
        asin = null,
        isExplicit = false,
        isAbridged = false,
        coverPath = null,
        trackCount = 1,
        sizeBytes = 0,
        remoteUpdatedAt = null,
        addedAt = null,
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

        override suspend fun refreshPermissions(profileId: ProfileId): AppResult<AccountState> = notUsed()

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

    private data class SavedMetadataEdit(
        val profileId: ProfileId,
        val bookId: LibraryItemId,
        val edit: BookMetadataEdit,
        val changed: Set<BookMetadataField>,
    )

    /** The real bulk use case is under test; only its repository edge is recorded. */
    private class RecordingMetadata(private val libraries: FakeLibraries) : MetadataRepository {
        val saves = mutableListOf<SavedMetadataEdit>()
        val savedDrafts = mutableListOf<Pair<ProfileId, LibraryItemId>>()
        val saveStarted = CompletableDeferred<Unit>()
        var saveGate: CompletableDeferred<Unit>? = null
        var saveWasCanceled = false

        override fun observeDraft(profileId: ProfileId, bookId: LibraryItemId): Flow<BookMetadataEdit?> = flowOf(null)

        override suspend fun saveDraft(profileId: ProfileId, bookId: LibraryItemId, edit: BookMetadataEdit) {
            savedDrafts += profileId to bookId
        }

        override suspend fun discardDraft(profileId: ProfileId, bookId: LibraryItemId) = Unit

        override suspend fun reload(profileId: ProfileId, bookId: LibraryItemId): AppResult<Book> =
            libraries.book(bookId)?.let { AppResult.Success(it) }
                ?: AppResult.Failure(AppError.ApiCompatibility(summary = "Test book was not cached."))

        override suspend fun save(
            profileId: ProfileId,
            bookId: LibraryItemId,
            edit: BookMetadataEdit,
            changed: Set<BookMetadataField>,
        ): AppResult<MetadataSaveResult> {
            saveStarted.complete(Unit)
            try {
                saveGate?.await()
            } catch (cancellation: CancellationException) {
                saveWasCanceled = true
                throw cancellation
            }
            val current = libraries.book(bookId)
                ?: return AppResult.Failure(AppError.ApiCompatibility(summary = "Test book was not cached."))
            saves += SavedMetadataEdit(profileId, bookId, edit, changed)
            val updated = current.copy(genres = edit.genres)
            libraries.updateBook(updated)
            return AppResult.Success(MetadataSaveResult(book = updated, isLocalCopyStale = false))
        }

        override suspend fun uploadCover(
            profileId: ProfileId,
            bookId: LibraryItemId,
            bytes: ByteArray,
            mimeType: String,
        ): AppResult<Book> = notUsed()

        override suspend fun removeCover(profileId: ProfileId, bookId: LibraryItemId): AppResult<Book> = notUsed()

        override suspend fun findCandidates(
            profileId: ProfileId,
            provider: String,
            title: String,
            author: String,
        ): AppResult<List<MatchCandidate>> = notUsed()

        override suspend fun metadataProviders(profileId: ProfileId): AppResult<List<MetadataProvider>> = notUsed()

        override suspend fun scanItem(profileId: ProfileId, bookId: LibraryItemId): AppResult<String> = notUsed()

        override suspend fun removeFromDatabase(profileId: ProfileId, bookId: LibraryItemId): AppResult<Unit> =
            notUsed()

        override suspend fun embedMetadata(profileId: ProfileId, bookId: LibraryItemId): AppResult<EmbedRequest> =
            notUsed()

        private fun <T> notUsed(): AppResult<T> =
            AppResult.Failure(AppError.ApiCompatibility(summary = "Not part of this test fake."))
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

        fun book(bookId: LibraryItemId): Book? = storedBooks.value.firstOrNull { it.id == bookId }

        fun updateBook(book: Book) {
            storedBooks.value = storedBooks.value.map { cached -> if (cached.id == book.id) book else cached }
        }

        override fun observeBooks(profileId: ProfileId, libraryId: LibraryId): Flow<List<Book>> =
            storedBooks.map { all -> all.filter { it.libraryId == libraryId } }

        override fun observeAccessibleBooks(profileId: ProfileId): Flow<List<Book>> = storedBooks

        override fun observeChapters(profileId: ProfileId, bookId: LibraryItemId) =

            kotlinx.coroutines.flow.flowOf(emptyList<com.example.shelfplayer.core.model.library.Chapter>())

        override fun observeBook(profileId: ProfileId, bookId: LibraryItemId): Flow<Book?> =
            storedBooks.map { books -> books.firstOrNull { it.id == bookId } }

        private val syncState = MutableStateFlow<SyncState?>(null)

        fun setSyncState(state: SyncState) {
            syncState.value = state
        }

        override fun observeSyncState(profileId: ProfileId): Flow<SyncState> =
            syncState.map { it ?: SyncState.idle(ServerId("fixture-server"), profileId) }

        override suspend fun writeProgress(profileId: ProfileId, progress: List<AccountProgress>): AppResult<Int> =
            AppResult.Success(0)

        /** Every query that reached the server, in order, so a test can pin what the debounce sent. */
        val serverQueries = mutableListOf<String>()

        override suspend fun searchServer(profileId: ProfileId, query: String): AppResult<Int> {
            serverQueries += query
            return AppResult.Success(0)
        }

        override suspend fun refresh(profileId: ProfileId): AppResult<Int> {
            refreshCount++
            gate?.await()
            return refreshResult
        }
    }
}

/**
 * PRODUCT_SPEC 11.1 — bookmarks are written by the same account sync these tests drive.
 *
 * A stub rather than a fake: no test in this file asserts anything about bookmarks, and one that recorded
 * them would invite a reader to think it did.
 */
private class StubBookmarks : BookmarkRepository {
    override fun observe(bookId: LibraryItemId): Flow<List<Bookmark>> = flowOf(emptyList())

    override suspend fun add(bookId: LibraryItemId, at: Duration, title: String): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun rename(bookId: LibraryItemId, at: Duration, title: String): AppResult<Unit> =
        AppResult.Success(Unit)

    override suspend fun remove(bookId: LibraryItemId, at: Duration): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun writeAccountBookmarks(
        profileId: ProfileId,
        bookmarks: List<AccountBookmark>,
    ): AppResult<Int> = AppResult.Success(bookmarks.size)
}
