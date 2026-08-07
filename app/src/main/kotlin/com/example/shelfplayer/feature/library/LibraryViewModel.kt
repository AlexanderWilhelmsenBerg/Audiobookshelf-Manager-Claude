package com.example.shelfplayer.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookFilter
import com.example.shelfplayer.domain.library.BookFocus
import com.example.shelfplayer.domain.library.BookGroup
import com.example.shelfplayer.domain.library.BookGroupKind
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.usecase.ObserveLibraryBooksUseCase
import com.example.shelfplayer.domain.usecase.ObserveLibraryGroupsUseCase
import com.example.shelfplayer.domain.usecase.ObserveLibrarySeriesUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC LIB-002 — browse, search and sort one library.
 *
 * The 300 ms search debounce comes straight from LIB-002. It is applied to the *query* flow rather
 * than inside the use case so that changing the sort order re-queries immediately: only typing needs
 * to settle.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeLibraryBooks: ObserveLibraryBooksUseCase,
    observeLibrarySeries: ObserveLibrarySeriesUseCase,
    private val observeLibraryGroups: ObserveLibraryGroupsUseCase,
    private val preferences: PreferencesRepository,
) : ViewModel() {

    private val libraryId: LibraryId = LibraryId(
        requireNotNull(savedStateHandle.get<String>(ShelfDestinations.ARG_LIBRARY_ID)) {
            "Library route is missing its ${ShelfDestinations.ARG_LIBRARY_ID} argument"
        },
    )

    private val controls = MutableStateFlow(LibraryControls())

    /**
     * Only the query is debounced. Changing the sort order re-queries immediately, because there is
     * nothing to settle: the user made one discrete choice, not a stream of keystrokes.
     *
     * `map { it.query }` rather than `map(LibraryControls::query)`: `Flow.map` takes a
     * `suspend (T) -> R`, and a property reference is not convertible to a suspending function type,
     * so the reference form fails to resolve and every downstream inference collapses with it.
     */
    private val debouncedQuery: Flow<String> = controls
        .map { it.query }
        .distinctUntilChanged()
        .debounce { query -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }

    /**
     * PRODUCT_SPEC SET-001 / LIB-002 — the order comes from this profile's preferences for *this*
     * library, and is written back there.
     *
     * Per library rather than one order for all of them: a podcast library and a novel library are not
     * usefully sorted the same way, and LIB-002 asks for the choice to persist per profile and library.
     */
    private val order: Flow<BookSortOrder> = preferences.observePreferences()
        .map { BookSortOrder.fromStoredName(it.orderName(libraryId)) }
        .distinctUntilChanged()

    private val tab: Flow<LibraryTab> = controls
        .map { it.tab }
        .distinctUntilChanged()

    /**
     * The inputs are combined into a named [LibraryQuery] rather than a tuple, so the downstream lambda
     * reads `request.query` instead of `first`.
     */
    private val books: Flow<List<Book>> = combine(
        debouncedQuery,
        order,
        controls.map { it.filter to it.focus }.distinctUntilChanged(),
    ) { query, currentOrder, (filter, focus) ->
        LibraryQuery(query, currentOrder, filter, focus)
    }.flatMapLatest { request ->
        observeLibraryBooks(
            libraryId = libraryId,
            query = request.query,
            order = request.order,
            filter = request.filter,
            focus = request.focus,
        )
    }

    /**
     * PRODUCT_SPEC LIB-002 — authors and genres, grouped from the same rows.
     *
     * Like the series axis, no sort order: a list of authors is alphabetical, and a sort chip over it
     * would be a control that does nothing.
     */
    private fun groups(kind: BookGroupKind): Flow<List<BookGroup>> = debouncedQuery
        .flatMapLatest { query -> observeLibraryGroups(libraryId = libraryId, kind = kind, query = query) }

    /**
     * PRODUCT_SPEC LIB-003 — the series axis takes no sort order, so it is kept out of [books]'
     * `combine` rather than folded into it. Changing the sort chips while the series tab is showing
     * would otherwise re-group the whole library to produce exactly the same list.
     */
    private val series: Flow<List<SeriesShelf>> = debouncedQuery
        .flatMapLatest { query -> observeLibrarySeries(libraryId = libraryId, query = query) }

    /**
     * Only the visible axis is collected. `flatMapLatest` on the tab cancels the others, so the library
     * is not grouped into series, authors and genres for a user looking at the book list.
     */
    private val content: Flow<LibraryContent> = tab.flatMapLatest { current ->
        when (current) {
            LibraryTab.Books -> books.map { LibraryContent.OfBooks(it) }
            LibraryTab.Series -> series.map { LibraryContent.OfSeries(it) }
            LibraryTab.Authors -> groups(BookGroupKind.Author).map { LibraryContent.OfGroups(it) }
            LibraryTab.Genres -> groups(BookGroupKind.Genre).map { LibraryContent.OfGroups(it) }
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        controls,
        order,
        content,
    ) { current, currentOrder, loaded ->
        LibraryUiState(
            query = current.query,
            order = currentOrder,
            tab = current.tab,
            filter = current.filter,
            focus = current.focus,
            books = (loaded as? LibraryContent.OfBooks)?.books.orEmpty(),
            series = (loaded as? LibraryContent.OfSeries)?.series.orEmpty(),
            groups = (loaded as? LibraryContent.OfGroups)?.groups.orEmpty(),
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LibraryUiState(),
    )

    fun onQueryChanged(query: String) {
        controls.update { it.copy(query = query) }
    }

    /** PRODUCT_SPEC SET-001 — persisted for this profile and this library; the list re-reads it. */
    fun onOrderChanged(order: BookSortOrder) {
        viewModelScope.launch { preferences.setSortOrder(libraryId = libraryId, order = order.name) }
    }

    /** Switching axis drops the focus: an author picked on the Authors tab is not a filter on Genres. */
    fun onTabChanged(tab: LibraryTab) {
        controls.update { it.copy(tab = tab, focus = null) }
    }

    fun onFilterChanged(filter: BookFilter) {
        controls.update { it.copy(filter = filter) }
    }

    /**
     * PRODUCT_SPEC LIB-002 — opening an author or a genre narrows the book list rather than pushing a
     * screen, so the search field and both chip rows keep working inside it.
     */
    fun onGroupSelected(group: BookGroup) {
        controls.update {
            it.copy(tab = LibraryTab.Books, focus = BookFocus(group.kind, group.key, group.label))
        }
    }

    fun onFocusCleared() {
        controls.update { it.copy(focus = null) }
    }

    /** One resolved request: what to search for, over what, and in what order. */
    private data class LibraryQuery(
        val query: String,
        val order: BookSortOrder,
        val filter: BookFilter,
        val focus: BookFocus?,
    )

    /** The sort order lives in preferences, not here — see [order]. */
    private data class LibraryControls(
        val query: String = "",
        val tab: LibraryTab = LibraryTab.Books,
        val filter: BookFilter = BookFilter.Default,
        val focus: BookFocus? = null,
    )

    /**
     * Whichever axis is being collected, carrying its own list.
     *
     * A sealed type rather than two nullable lists in the state, so "the series tab is showing and it
     * is empty" cannot be confused with "the series tab is showing and the books happen to be stale".
     */
    private sealed interface LibraryContent {
        data class OfBooks(val books: List<Book>) : LibraryContent

        data class OfSeries(val series: List<SeriesShelf>) : LibraryContent

        data class OfGroups(val groups: List<BookGroup>) : LibraryContent
    }

    private companion object {
        /** PRODUCT_SPEC LIB-002: search debounce is 300 ms. */
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class LibraryUiState(
    val query: String = "",
    val order: BookSortOrder = BookSortOrder.Default,
    val tab: LibraryTab = LibraryTab.Books,
    val filter: BookFilter = BookFilter.Default,
    /** PRODUCT_SPEC LIB-002 — the author or genre the book list has been narrowed to, if any. */
    val focus: BookFocus? = null,
    val books: List<Book> = emptyList(),
    val series: List<SeriesShelf> = emptyList(),
    val groups: List<BookGroup> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * PRODUCT_SPEC LIB-002 — which axis the library is being browsed along.
 *
 * Four axes, of which three list something other than books. Recently added, continue listening and
 * downloaded are *not* tabs: they are a sort and two filters over the book list, and making each its
 * own tab would mean four near-identical lists that cannot be combined — "downloaded, by title" would
 * become impossible to ask for.
 *
 * Collections are absent. PRODUCT_SPEC 3.2 makes them conditional on consistent server support and no
 * capability probe confirms them yet.
 */
enum class LibraryTab { Books, Series, Authors, Genres }
