package com.example.shelfplayer.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.common.connectivity.NetworkMonitor
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerStatus
import com.example.shelfplayer.core.model.SyncState
import com.example.shelfplayer.core.model.SyncStatus
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Library
import com.example.shelfplayer.domain.library.BookFilter
import com.example.shelfplayer.domain.library.BookFocus
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.library.HomeShelves
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.BrowseUseCases
import com.example.shelfplayer.domain.usecase.BulkEditGenresUseCase
import com.example.shelfplayer.domain.usecase.BulkGenreEditSummary
import com.example.shelfplayer.domain.usecase.ObserveLibrariesUseCase
import com.example.shelfplayer.domain.usecase.ObserveRealtimeUpdatesUseCase
import com.example.shelfplayer.domain.usecase.ObserveSyncStateUseCase
import com.example.shelfplayer.domain.usecase.RefreshLibraryUseCase
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-001 / LIB-002 — the shelf the app opens on.
 *
 * It lists **books**, not libraries: every book the active profile is granted, most recently played
 * first. With one library, a list of libraries is a single card between the user and their shelf; with
 * several it is a menu rather than a shelf. Browsing *by* library is still possible and lives in Settings
 * (PRODUCT_SPEC SET-002) — as a list of libraries to open, not as a switch that changes what this screen
 * is.
 *
 * The ViewModel never touches the gateway (PRODUCT_SPEC 22.8): it observes Room through use cases
 * and asks a use case to refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
@Suppress("LongParameterList") // Hilt dependencies remain grouped by architectural boundary, not hidden in a locator.
class HomeViewModel @Inject constructor(
    private val browse: BrowseUseCases,
    observeLibraries: ObserveLibrariesUseCase,
    observeSyncState: ObserveSyncStateUseCase,
    private val profileRepository: ProfileRepository,
    private val preferences: PreferencesRepository,
    private val networkMonitor: NetworkMonitor,
    private val syncAccount: SyncAccountUseCase,
    private val observeRealtimeUpdates: ObserveRealtimeUpdatesUseCase,
    private val refreshLibrary: RefreshLibraryUseCase,
    private val bulkEditGenres: BulkEditGenresUseCase,
) : ViewModel() {

    private val refreshState = MutableStateFlow(RefreshState())
    private val controls = MutableStateFlow(HomeControls())
    private val genreEditState = MutableStateFlow<GenreEditUiState>(GenreEditUiState.Hidden)
    private var genreEditJob: Job? = null

    /**
     * The controls the user is holding, the order this profile persisted, and the library it is scoped
     * to — resolved into one value.
     *
     * Combined here rather than added to the state `combine` below because that one is already at
     * `combine`'s five-flow limit, and because these three are read together anyway: between them they
     * are what the shelf *is*.
     *
     * PRODUCT_SPEC SET-001 — the sort order is read from preferences rather than held here, so the chip
     * moves because the write landed. The fallback is [BookSortOrder.LastPlayed]: what this shelf opens
     * on for a profile that has never chosen, not the global default.
     *
     * PRODUCT_SPEC 6.1 step 9 — the stored default library is resolved against the granted libraries
     * rather than trusted. One the profile has since lost must widen the shelf back to everything; a
     * shelf scoped to a library that no longer exists is an empty screen with no explanation on it.
     */
    private val view: Flow<ShelfView> = combine(
        controls,
        preferences.observePreferences(),
        observeLibraries(),
    ) { current, stored, libraries ->
        ShelfView(
            controls = current,
            order = BookSortOrder.fromStoredName(stored.orderName(libraryId = null), BookSortOrder.LastPlayed),
            library = stored.defaultLibraryId?.let { id -> libraries.firstOrNull { it.id == id } },
        )
    }

    /**
     * PRODUCT_SPEC LIB-002 — the 300 ms debounce applies to typing only.
     *
     * Changing an axis, a filter or the sort order re-queries immediately: each is one discrete choice,
     * not a stream of keystrokes, and there is nothing to settle.
     */
    private val debouncedQuery: Flow<String> = view
        .map { it.controls.query }
        .distinctUntilChanged()
        .debounce { query -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }

    /** Everything that decides *what* is queried, de-duplicated as one value. */
    private val scope: Flow<ShelfScope> = view
        .map { ShelfScope(it.order, it.library?.id, it.controls.filter, it.controls.focus) }
        .distinctUntilChanged()

    private val axis: Flow<HomeAxis> = view.map { it.controls.axis }.distinctUntilChanged()

    private val booksView: Flow<BooksView> = view.map { it.controls.effectiveView }.distinctUntilChanged()

    private val books: Flow<List<Book>> =
        combine(debouncedQuery, scope) { query, current -> query to current }
            .flatMapLatest { (query, current) ->
                browse.books(
                    query = query,
                    order = current.order,
                    libraryId = current.libraryId,
                    filter = current.filter,
                    focus = current.focus,
                )
            }

    private val shelves: Flow<HomeShelves> = scope
        .map { it.libraryId }
        .distinctUntilChanged()
        .flatMapLatest { libraryId -> browse.shelves(libraryId) }

    /**
     * Only the visible axis is collected. `flatMapLatest` cancels the others, so a user looking at the
     * book list is not paying to group 490 books into series, authors and genres — and a user on the
     * shelves is not paying to sort the flat list either.
     */
    /**
     * PRODUCT_SPEC LIB-002 / 16.2 — whether the other three axes are worth keeping loaded.
     *
     * A swipe reveals the next axis *while the finger is still down*, so it has to be composed with real
     * rows rather than an empty state — that is the difference between a page that follows the finger and
     * one that appears when it lifts. But collecting all four unconditionally would make every reader pay
     * the grouping, including the many who never swipe.
     *
     * So it latches on the first swipe and stays on. A reader who never swipes pays nothing, exactly as
     * before. A reader who swipes once pays it once, and only that first drag can catch an axis loading.
     * Following the drag instead was the other option and is worse: the same cost on every gesture, and a
     * flash on each one.
     */
    private val hasSwiped = MutableStateFlow(false)

    /** PRODUCT_SPEC 16.2 — a swipe has begun, so the neighbours are worth loading. One way, idempotent. */
    fun onSwipeStarted() {
        hasSwiped.value = true
    }

    /**
     * PRODUCT_SPEC LIB-002 / 16.2 — every axis whose rows are worth holding, keyed by axis.
     *
     * ### Why one map rather than "the current axis" plus "the neighbours"
     *
     * It was two flows, and the split had a hole a device found. The axis the user swipes to changes in
     * `controls` **immediately**, while its rows arrive one emission later — so for that gap the state said
     * *Series* while still carrying the book shelves, and the page rendered "No series here" for a fraction
     * of a second before the real rows landed. Reported from a device in exactly those words.
     *
     * One map closes it, because `flatMapLatest` **retains its previous value while the new one is being
     * assembled**. Once the neighbours are loaded that retained map already contains the axis being swiped
     * to, so the gap reads the right rows rather than an empty list. There is no moment at which an axis is
     * current and its rows are somebody else's.
     *
     * ### What is collected, and when
     *
     * Before the first swipe: the visible axis alone, which is what [content]'s `flatMapLatest` always did
     * and for the reason it documented — a reader on the book list is not paying to group 490 books into
     * series, authors and genres.
     *
     * After it: all four, so a drag reveals real rows from its first pixel and every axis change after that
     * is instant. See [hasSwiped] for why that latches rather than following the gesture.
     *
     * An axis **absent** from the map has not answered yet, which is how the screen tells "still loading"
     * from "loaded and empty" — the distinction that stops a reveal opening with "no series" about an axis
     * nobody has asked the database about.
     */
    private val axisRows: Flow<Map<HomeAxis, HomeAxisRows>> =
        combine(axis, booksView, hasSwiped) { current, mode, swiped -> Triple(current, mode, swiped) }
            .flatMapLatest { (current, mode, swiped) ->
                val wanted = if (swiped) HomeAxis.entries else listOf(current)
                combine(wanted.map { each -> contentFor(each, mode).map { each to it.asAxisRows() } }) { rows ->
                    rows.toMap()
                }
            }

    /** One axis's content, as a flow. */
    private fun contentFor(axis: HomeAxis, mode: BooksView): Flow<HomeContent> = when (axis) {
        HomeAxis.Books -> when (mode) {
            BooksView.Shelves -> shelves.map { HomeContent.OfShelves(it) }
            BooksView.List -> books.map { HomeContent.OfBooks(it) }
        }

        HomeAxis.Series -> groupedSeries().map { HomeContent.OfSeries(it) }
        HomeAxis.Authors -> groups(BookGroupKind.Author).map { HomeContent.OfGroups(it) }
        HomeAxis.Genres -> groups(BookGroupKind.Genre).map { HomeContent.OfGroups(it) }
    }

    private fun groupedSeries(): Flow<List<SeriesShelf>> =
        combine(debouncedQuery, scope.map { it.libraryId }.distinctUntilChanged()) { query, libraryId ->
            query to libraryId
        }.flatMapLatest { (query, libraryId) -> browse.series(libraryId = libraryId, query = query) }

    private fun groups(kind: BookGroupKind): Flow<List<BookGroup>> =
        combine(debouncedQuery, scope.map { it.libraryId }.distinctUntilChanged()) { query, libraryId ->
            query to libraryId
        }.flatMapLatest { (query, libraryId) ->
            browse.groups(kind = kind, libraryId = libraryId, query = query)
        }

    /**
     * PRODUCT_SPEC LIB-002 / MGR-008 — transient state read alongside the cached shelf.
     *
     * Bundled rather than added as sixth and seventh sources because typed `combine` stops at five flows.
     * The refresh and connection still travel together because a failed refresh means something different
     * depending on whether there was a network to fail on; the account-scoped genre dialog joins them only
     * as a transport into [HomeUiState].
     */
    private val attempt: Flow<Attempt> =
        combine(refreshState, networkMonitor.isOnline, genreEditState) { refresh, online, genreEdit ->
            Attempt(refresh, online, genreEdit)
        }

    val uiState: StateFlow<HomeUiState> = combine(
        profileRepository.observeActiveProfile(),
        axisRows,
        observeSyncState(),
        attempt,
        view,
    ) { profile, rows, syncState, (refresh, isOnline, genreEdit), current ->
        val loaded = rows[current.controls.axis]
        HomeUiState(
            isOffline = !isOnline,
            profile = profile,
            // The visible axis's rows come out of the same map the pages read, so the state cannot be
            // describing one axis and carrying another's — see `axisRows`.
            books = loaded?.books.orEmpty(),
            shelves = loaded?.shelves ?: HomeShelves.Empty,
            series = loaded?.series.orEmpty(),
            groups = loaded?.groups.orEmpty(),
            axisRows = rows,
            query = current.controls.query,
            isSearching = current.controls.isSearching,
            axis = current.controls.axis,
            booksView = current.controls.effectiveView,
            filter = current.controls.filter,
            focus = current.controls.focus,
            order = current.order,
            // PRODUCT_SPEC 6.1 step 9 / 21 — a shelf showing part of what the profile can see says which
            // part. A silently narrowed list reads as missing books.
            scopedTo = current.library,
            isRefreshing = refresh.inFlight,
            syncStatus = when {
                refresh.inFlight -> SyncStatus.Syncing
                // A failure from the user's own refresh outranks the persisted state, the same way the
                // error field does: it is the newer fact and the one they are waiting on.
                refresh.lastError != null -> SyncStatus.Failed
                // The persisted state comes next, because it is the only record of a sync this process
                // did not start — one abandoned by an earlier launch, or one another screen began.
                //
                // `NeverSynced` is the exception, and it has to be: the repository reports it both for
                // "no sync has run" and for "there is no row", and content on screen proves a sync ran.
                // Labelling a populated library "not synchronized yet" would be the app contradicting
                // what the user is looking at.
                syncState != null && syncState.status != SyncStatus.NeverSynced -> syncState.status
                loaded?.hasRows == true -> SyncStatus.Succeeded
                else -> SyncStatus.NeverSynced
            },
            // A live error from the user's own refresh wins: it is the newer fact, and it is the one they
            // are waiting for an answer to.
            error = refresh.lastError ?: syncState?.lastError,
            serverStatus = serverStatusOf(isOnline, refresh, syncState),
            genreEdit = genreEdit,
            // PRODUCT_SPEC LIB-001 — "the home screen can render partial cached content while sync
            // continues", and sync status is "visible but non-blocking".
            //
            // This is the only thing that blocks, and it blocks on Room rather than on the network: the
            // instant between the screen appearing and the first database emission. Blocking on the
            // *sync* was the previous behaviour and was reported from a device as having to wait for the
            // library to update before seeing it. A sync of a real library is an N+1 over every item; the
            // cached content is right there, and there is no reason to hide it meanwhile.
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        // PRODUCT_SPEC 16.3: no unbounded collection in a lifecycle owner. The upstream is
        // dropped five seconds after the last collector goes away, which survives a rotation
        // without keeping a DataStore read alive for a backgrounded process.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HomeUiState(),
    )

    /**
     * PRODUCT_SPEC LIB-001 — "initial synchronization", and picking up an abandoned one.
     *
     * This screen owns the first sync. `SignInUseCase` used to await it, in the sign-in screen's scope,
     * which a successful sign-in then cancels by popping the screen — leaving a half-finished sync and a
     * `sync_state` row stuck on [SyncStatus.Syncing]. That is the "empty library until I pressed refresh"
     * report from three device runs.
     *
     * ### The rule: one automatic sync per profile per process
     *
     * There used to be a second condition — only sync when the recorded status was [SyncStatus.NeverSynced]
     * or [SyncStatus.Syncing] — and it is gone. It meant a profile that had ever synced successfully never
     * synced again on its own, so switching to an account that was up to date last week showed last week's
     * library until the user found the refresh button. Two device runs reported it: "no sync initiated,
     * same server, different account", and progress played elsewhere not appearing until a manual refresh.
     *
     * It matters more since item visibility became per profile (PRODUCT_SPEC 5.2): what an account may see
     * is now something only its *own* sync establishes, so an account that never syncs is an account whose
     * shelf is not merely stale but empty.
     *
     * The cost is real and bounded: a sync is an N+1 over the library, and this runs it once per profile
     * per process launch rather than once per screen visit. A staleness window that skips the run when the
     * last one was minutes ago belongs with the rest of the foreground refresh policy, not here.
     *
     * Per profile, not per ViewModel: it was a single flag, so switching accounts synced the first one and
     * silently skipped every later one. A device run reported exactly that — "sync only starts on the
     * first user's login".
     */
    private val syncAttemptedFor = mutableSetOf<ProfileId>()

    /**
     * PRODUCT_SPEC LIB-002 / 6.3 — the network came back, so try again.
     *
     * A device run asked for this in as many words: "when getting connectability when the server goes
     * online after being off". Without it a user who refreshed in a lift is left holding the error until
     * they think to pull down again.
     *
     * Only a transition *into* online triggers anything, and only when the last attempt actually failed.
     * Refreshing on every connectivity change would re-sync the whole library each time a phone hops
     * between Wi-Fi and mobile, which on a 490-book library is 491 requests for nothing. A profile that
     * is up to date has no reason to care that the radio changed.
     */
    init {
        // MGR-008 — never let a confirmation or result from one account follow the user into another.
        // The use case independently re-checks the profile at every network boundary; this is the UI
        // half, keeping even a not-yet-confirmed operation explicitly scoped to its originating profile.
        viewModelScope.launch {
            profileRepository.observeActiveProfile()
                .map { it?.id }
                .distinctUntilChanged()
                .collect { activeProfileId ->
                    val requestedProfileId = genreEditState.value.requestOrNull()?.profileId
                    if (requestedProfileId != null && requestedProfileId != activeProfileId) {
                        // Cancellation is required in addition to hiding the dialog. Without it, a fast
                        // A -> B -> A switch can let the old coroutine pass the use case's next profile-id
                        // check and resume writes beside a newly started operation for A.
                        genreEditJob?.cancel()
                        genreEditJob = null
                        genreEditState.value = GenreEditUiState.Hidden
                    }
                }
        }
        viewModelScope.launch {
            networkMonitor.isOnline
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    val state = uiState.value
                    val failed = state.error != null || state.syncStatus == SyncStatus.Failed
                    if (failed && !state.isRefreshing && state.profile != null) refresh()
                }
        }
        // PRODUCT_SPEC LIB-002 — "local cached results appear immediately; server search may enrich
        // results". The cached half is the `books` flow, which narrows on the first keystroke. This is
        // the other half, and it runs off the *same* debounced query so the 300 ms rule governs the
        // request as well as the filter.
        //
        // `collectLatest` cancels an in-flight search when the query moves on: a user typing "sal" then
        // "salt" wants the answer to "salt", and the earlier request writing its hits afterwards would
        // be the older answer landing last. Nothing here touches the result — the hits reach the screen
        // by being written to Room, which the shelf is already observing.
        viewModelScope.launch {
            debouncedQuery.collectLatest { query ->
                if (query.isBlank()) return@collectLatest
                browse.searchServer(query)
            }
        }
    }

    fun onVisible() {
        val state = uiState.value
        val profileId = state.profile?.id ?: return

        // PRODUCT_SPEC SYNC-002 — and the connection, for as long as this screen is alive.
        //
        // Scoped to the ViewModel rather than to the process: a socket held open by a backgrounded app
        // is a wake lock with extra steps, and PRODUCT_SPEC SYNC-003 puts persistent background work
        // under WorkManager rather than under an open connection. `collectRealtime` guards against a
        // second collector for the same profile, because `onVisible` runs on every appearance.
        collectRealtime(profileId)

        // PRODUCT_SPEC LIB-001 / AUTH-004 — the cheap half, every time the screen appears.
        //
        // One request that brings back positions played elsewhere, a grant changed on the server, and
        // whether the account is still enabled. It is not bounded by `syncAttemptedFor` because it is
        // not the expensive one: the library sweep below is 491 requests, this is one, and the whole
        // reason it exists is so that coming back to the app does not have to cost the former.
        viewModelScope.launch { syncAccount(profileId) }

        if (profileId in syncAttemptedFor || state.isRefreshing) return
        syncAttemptedFor += profileId
        refresh()
    }

    /**
     * PRODUCT_SPEC SYNC-002 — one collector per profile, replaced when the profile changes.
     *
     * Without the guard, returning to the shelf would open a second socket beside the first: both
     * authenticated, both delivering the same events, both writing the same rows. Idempotent, and still
     * two connections to a server that only needed one.
     */
    private var realtimeJob: kotlinx.coroutines.Job? = null
    private var realtimeProfile: ProfileId? = null

    private fun collectRealtime(profileId: ProfileId) {
        if (realtimeProfile == profileId && realtimeJob?.isActive == true) return
        realtimeJob?.cancel()
        realtimeProfile = profileId
        realtimeJob = viewModelScope.launch { observeRealtimeUpdates(profileId) }
    }

    fun refresh() {
        if (refreshState.value.inFlight) return
        refreshState.update { it.copy(inFlight = true) }
        viewModelScope.launch {
            val result = refreshLibrary()
            refreshState.update {
                it.copy(
                    inFlight = false,
                    lastError = (result as? AppResult.Failure)?.error,
                    lastAttemptSucceeded = result is AppResult.Success,
                )
            }
        }
    }

    fun onQueryChanged(query: String) {
        controls.update { it.copy(query = query) }
    }

    /**
     * PRODUCT_SPEC LIB-002 / 21 — search is a button, and the field only exists once it is pressed.
     *
     * A permanent text field costs a row of vertical space on every screen to serve the one visit in
     * ten that is a search. Closing it clears the query, because a hidden field still filtering the
     * shelf is a list of missing books with no visible cause.
     */
    fun onSearchToggled() {
        controls.update {
            if (it.isSearching) it.copy(isSearching = false, query = "") else it.copy(isSearching = true)
        }
    }

    /** Switching axis drops the focus: an author picked on the Authors axis is not a filter on Genres. */
    fun onAxisChanged(axis: HomeAxis) {
        controls.update { it.copy(axis = axis, focus = null) }
    }

    fun onBooksViewChanged(view: BooksView) {
        controls.update { it.copy(booksView = view) }
    }

    fun onFilterChanged(filter: BookFilter) {
        controls.update { it.copy(filter = filter) }
    }

    /**
     * PRODUCT_SPEC LIB-002 — opening an author or a genre narrows the book list rather than pushing a
     * screen, so the search field, the sort chips and the filter chips keep working inside it.
     */
    fun onGroupSelected(group: BookGroup) {
        controls.update {
            it.copy(axis = HomeAxis.Books, focus = BookFocus(group.kind, group.key, group.label))
        }
    }

    /** PRODUCT_SPEC MGR-008 — begin from exactly one cached Genres-axis group. */
    fun onGenreEditRequested(group: BookGroup) {
        val state = uiState.value
        val profile = state.profile ?: return
        if (group.kind != BookGroupKind.Genre || !profile.canUpdate) {
            return
        }
        if (profile.requiresReauthentication || state.isOffline) {
            return
        }
        viewModelScope.launch {
            // The visible Genres axis may be narrowed to a default library. The write intentionally
            // repairs every accessible cached match, so confirmation must count the same profile-wide
            // set rather than repeat a smaller card count from the current view.
            val sourceGenre = group.label.trim()
            val cachedMatchCount = browse.books(
                query = "",
                libraryId = null,
                filter = BookFilter.All,
                focus = null,
            ).first().count { book ->
                book.genres.any { genre -> genre.trim().equals(sourceGenre, ignoreCase = true) }
            }
            val latest = uiState.value
            val latestProfile = latest.profile
            if (latestProfile == null || latestProfile.id != profile.id) {
                return@launch
            }
            if (!latestProfile.canUpdate || latestProfile.requiresReauthentication) {
                return@launch
            }
            if (latest.isOffline || latest.axis != HomeAxis.Genres) {
                return@launch
            }
            genreEditState.value = GenreEditUiState.Confirming(
                GenreEditRequest(
                    profileId = profile.id,
                    sourceGenre = sourceGenre,
                    cachedMatchCount = cachedMatchCount,
                ),
            )
        }
    }

    fun onGenreEditReplacementChanged(value: String) {
        genreEditState.update { state ->
            if (state is GenreEditUiState.Confirming) {
                state.copy(request = state.request.copy(replacementGenres = value))
            } else {
                state
            }
        }
    }

    /** The only path from the confirmation dialog to the privileged domain operation. */
    fun onGenreEditConfirmed() {
        val confirming = genreEditState.value as? GenreEditUiState.Confirming ?: return
        if (!confirming.request.hasReplacementGenres) return

        val running = GenreEditUiState.Running(confirming.request)
        genreEditState.value = running
        val job = viewModelScope.launch {
            val result = bulkEditGenres(
                profileId = running.request.profileId,
                sourceGenre = running.request.sourceGenre,
                targetGenres = running.request.replacementGenres,
            )
            // A profile switch deliberately hides this account-scoped operation. Do not let a late
            // network response bring its result back on the new account.
            if (genreEditState.value !== running) return@launch
            genreEditState.value = when (result) {
                is AppResult.Success -> GenreEditUiState.Complete(running.request, result.value)
                is AppResult.Failure -> GenreEditUiState.Failed(running.request, result.error)
            }
        }
        genreEditJob = job
        job.invokeOnCompletion {
            if (genreEditJob === job) genreEditJob = null
        }
    }

    /** Running writes cannot be dismissed: losing their partial summary would misrepresent the result. */
    fun onGenreEditDismissed() {
        genreEditState.update { state ->
            if (state is GenreEditUiState.Running) state else GenreEditUiState.Hidden
        }
    }

    fun onFocusCleared() {
        controls.update { it.copy(focus = null) }
    }

    /**
     * PRODUCT_SPEC SET-001 — the choice is written to the profile's preferences, and the shelf re-reads
     * it from there.
     *
     * A failure leaves the chip where it was, which is the honest outcome: the order did not change,
     * because it could not be saved. `DefaultPreferencesRepository` logs why.
     */
    fun onOrderChanged(order: BookSortOrder) {
        viewModelScope.launch { preferences.setSortOrder(libraryId = null, order = order.name) }
    }

    fun dismissError() {
        refreshState.update { it.copy(lastError = null) }
    }

    private data class RefreshState(
        val inFlight: Boolean = false,
        val lastError: AppError? = null,
        /** Distinct from `lastError == null`, which is also true before anything has been attempted. */
        val lastAttemptSucceeded: Boolean = false,
    )

    /** The in-memory controls alongside the persisted order and default library, resolved together. */
    private data class ShelfView(val controls: HomeControls, val order: BookSortOrder, val library: Library?)

    /** Everything that decides what is queried, so it can be de-duplicated as one value. */
    private data class ShelfScope(
        val order: BookSortOrder,
        val libraryId: LibraryId?,
        val filter: BookFilter,
        val focus: BookFocus?,
    )

    /**
     * Whichever axis is being collected, carrying its own rows.
     *
     * A sealed type rather than four nullable lists in the state, so "the Authors axis is showing and
     * it is empty" cannot be confused with "the Authors axis is showing and the books are stale".
     */
    private sealed interface HomeContent {
        /**
         * Whether this axis produced anything.
         *
         * Read by the sync-status branch, which treats content on screen as proof that a sync ran —
         * the repository reports `NeverSynced` both for "no sync has run" and for "there is no row".
         */
        val hasRows: Boolean

        data class OfShelves(val shelves: HomeShelves) : HomeContent {
            override val hasRows: Boolean get() = !shelves.isEmpty
        }

        data class OfBooks(val books: List<Book>) : HomeContent {
            override val hasRows: Boolean get() = books.isNotEmpty()
        }

        data class OfSeries(val series: List<SeriesShelf>) : HomeContent {
            override val hasRows: Boolean get() = series.isNotEmpty()
        }

        data class OfGroups(val groups: List<BookGroup>) : HomeContent {
            override val hasRows: Boolean get() = groups.isNotEmpty()
        }
    }

    /** The rows of one axis, in the four shapes [HomeUiState] already carries them in. */
    private fun HomeContent.asAxisRows(): HomeAxisRows = when (this) {
        is HomeContent.OfShelves -> HomeAxisRows(shelves = shelves)
        is HomeContent.OfBooks -> HomeAxisRows(books = books)
        is HomeContent.OfSeries -> HomeAxisRows(series = series)
        is HomeContent.OfGroups -> HomeAxisRows(groups = groups)
    }

    /** The non-catalogue inputs transported together into the five-source home-state combine. */
    private data class Attempt(val refresh: RefreshState, val isOnline: Boolean, val genreEdit: GenreEditUiState)

    /**
     * PRODUCT_SPEC LIB-002 / SYNC-001 — the reachability indicator, inferred from calls already made.
     *
     * There is deliberately no separate ping. A dedicated probe would answer a slightly different
     * question than the one the user cares about — "does the health endpoint respond" rather than "is my
     * library working" — and would cost a request per interval to do it. The outcome of the sync the app
     * just performed is both free and more truthful.
     *
     * Offline outranks everything: with no network there is nothing to say about the server, and showing
     * it as unreachable would blame the wrong thing. That is why this is three states rather than a
     * boolean — "unknown" is a real answer and the honest one before anything has been attempted.
     */
    private fun serverStatusOf(isOnline: Boolean, refresh: RefreshState, syncState: SyncState?): ServerStatus = when {
        !isOnline -> ServerStatus.Unknown
        refresh.lastError != null -> if (refresh.lastError.isReachability()) {
            ServerStatus.Unreachable
        } else {
            // A `401` or a `403` is a server that answered — clearly reachable, and saying otherwise
            // would send the user to check their network over a permissions problem.
            ServerStatus.Reachable
        }

        refresh.lastAttemptSucceeded -> ServerStatus.Reachable
        syncState?.status == SyncStatus.Failed ->
            if (syncState.lastError?.isReachability() == true) ServerStatus.Unreachable else ServerStatus.Reachable

        syncState?.lastSuccessfulSyncAt != null -> ServerStatus.Reachable
        else -> ServerStatus.Unknown
    }

    private fun AppError.isReachability(): Boolean = this is AppError.Network || this is AppError.Timeout

    private companion object {
        /** PRODUCT_SPEC LIB-002: search debounce is 300 ms. */
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** The immutable operation snapshot confirmed by the user before any server write. */
data class GenreEditRequest(
    val profileId: ProfileId,
    val sourceGenre: String,
    val cachedMatchCount: Int,
    val replacementGenres: String = "",
) {
    val hasReplacementGenres: Boolean
        get() = replacementGenres.split(',').any { it.isNotBlank() }

    val replacementSummary: String
        get() = replacementGenres.split(',').map(String::trim).filter(String::isNotEmpty).joinToString(", ")
}

/** PRODUCT_SPEC MGR-008 — confirmation, progress and the non-transactional final result. */
sealed interface GenreEditUiState {
    data object Hidden : GenreEditUiState

    data class Confirming(val request: GenreEditRequest) : GenreEditUiState

    data class Running(val request: GenreEditRequest) : GenreEditUiState

    data class Complete(val request: GenreEditRequest, val summary: BulkGenreEditSummary) : GenreEditUiState

    data class Failed(val request: GenreEditRequest, val error: AppError) : GenreEditUiState
}

private fun GenreEditUiState.requestOrNull(): GenreEditRequest? = when (this) {
    GenreEditUiState.Hidden -> null
    is GenreEditUiState.Confirming -> request
    is GenreEditUiState.Running -> request
    is GenreEditUiState.Complete -> request
    is GenreEditUiState.Failed -> request
}

/**
 * PRODUCT_SPEC 16.2 — one axis's rows, for an axis that is **not** the one on screen.
 *
 * The page a swipe is revealing has to render real content while the finger is still down, and it renders
 * it through the same `HomeContent` the visible axis uses — by copying this over a [HomeUiState]. Four
 * fields rather than a sealed type here, because that is the shape the state already has and the copy is
 * the point.
 *
 * Absence, not emptiness, is how "still loading" is said: see `HomeViewModel.axisRows`.
 */
data class HomeAxisRows(
    val books: List<Book> = emptyList(),
    val shelves: HomeShelves = HomeShelves.Empty,
    val series: List<SeriesShelf> = emptyList(),
    val groups: List<BookGroup> = emptyList(),
) {
    /**
     * Whether this axis produced anything.
     *
     * Read by the sync-status branch, which treats rows on screen as proof that a sync ran — the
     * repository reports `NeverSynced` both for "no sync has run" and for "there is no row". Exactly one
     * of the four fields is ever populated, since each is built from one axis's answer, so asking all four
     * asks the only one that could say yes.
     */
    val hasRows: Boolean
        get() = books.isNotEmpty() || !shelves.isEmpty || series.isNotEmpty() || groups.isNotEmpty()
}

/** PRODUCT_SPEC 21 — loading, empty, error and content are separate, observable states. */
data class HomeUiState(
    val profile: Profile? = null,
    val books: List<Book> = emptyList(),
    /** PRODUCT_SPEC LIB-002 — the three shelves, shown when the user has asked for nothing in particular. */
    val shelves: HomeShelves = HomeShelves.Empty,
    val series: List<SeriesShelf> = emptyList(),
    val groups: List<BookGroup> = emptyList(),
    /**
     * PRODUCT_SPEC 16.2 — every axis whose rows are loaded, for the pages a swipe reveals.
     *
     * The visible axis's rows above are this map's entry for [axis], so the two cannot disagree. An axis
     * absent from it has not answered yet and its page renders as loading; see `HomeViewModel.axisRows`.
     */
    val axisRows: Map<HomeAxis, HomeAxisRows> = emptyMap(),
    val query: String = "",
    val isSearching: Boolean = false,
    val axis: HomeAxis = HomeAxis.Books,
    val booksView: BooksView = BooksView.Shelves,
    val filter: BookFilter = BookFilter.Default,
    /** PRODUCT_SPEC LIB-002 — the author or genre the book list has been narrowed to, if any. */
    val focus: BookFocus? = null,
    val order: BookSortOrder = BookSortOrder.LastPlayed,
    /**
     * PRODUCT_SPEC 6.1 step 9 — the library this shelf is narrowed to, or `null` for all of them.
     *
     * The resolved library rather than the stored id: the screen names it, and a stored id that no
     * longer resolves has already widened the shelf back to everything by the time it gets here.
     */
    val scopedTo: Library? = null,
    val isRefreshing: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.NeverSynced,
    val error: AppError? = null,
    /** PRODUCT_SPEC MGR-008 — the account-scoped genre operation, if one is being confirmed or reported. */
    val genreEdit: GenreEditUiState = GenreEditUiState.Hidden,
    /**
     * PRODUCT_SPEC LIB-002 / SYNC-001 — whether the *server* is answering, as opposed to whether the
     * device has a network.
     *
     * Asked for from a device: "an icon on top to show that the server is reachable or not". The two
     * are genuinely different questions and both are shown — a LAN-only server is unreachable over a
     * perfectly good mobile connection, and reporting that as "offline" would send the user to check
     * their phone instead of their VPN.
     */
    val serverStatus: ServerStatus = ServerStatus.Unknown,
    /**
     * PRODUCT_SPEC LIB-002 — "empty, loading, error, and offline states are distinct".
     *
     * Distinct because they call for different sentences. An offline shelf is complete and correct as
     * far as it goes; a failed one may be missing something. Telling a user on a train that the server
     * refused their request sends them to check a server that is fine.
     *
     * It says nothing about the *server* being reachable — a LAN-only server is unreachable over a
     * mobile connection that reports online. See `NetworkMonitor`.
     */
    val isOffline: Boolean = false,
    /**
     * Whether Room has answered yet.
     *
     * `false` only for the instant between this screen appearing and its first database emission, and the
     * screen waits on it — otherwise a cold start with a saved profile flashes "No server connected"
     * before the profile arrives. It is deliberately *not* about the sync: a refresh in flight is
     * [isRefreshing], which the screen shows without hiding anything (PRODUCT_SPEC LIB-001).
     */
    val isLoaded: Boolean = false,
)

/**
 * Whether the axis currently on screen has nothing to show.
 *
 * A property on the state rather than a `when` in the composable: "is this empty" is answered
 * differently per axis, and a screen that got it wrong would render a `LazyColumn` of nothing instead
 * of the sentence explaining why.
 */
val HomeUiState.isAxisEmpty: Boolean
    get() = when (axis) {
        HomeAxis.Books -> if (booksView == BooksView.Shelves) shelves.isEmpty else books.isEmpty()
        HomeAxis.Series -> series.isEmpty()
        HomeAxis.Authors, HomeAxis.Genres -> groups.isEmpty()
    }
