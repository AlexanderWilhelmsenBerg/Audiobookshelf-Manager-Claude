package com.example.shelfplayer.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.repository.PreferencesRepository
import com.example.shelfplayer.domain.usecase.ObserveLibraryBooksUseCase
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
     * The two inputs are combined into a named [LibraryQuery] rather than a `Pair`, so the
     * downstream lambda reads `request.query` instead of `first`.
     */
    private val books: Flow<List<Book>> =
        combine(debouncedQuery, order) { query, order -> LibraryQuery(query, order) }
            .flatMapLatest { request ->
                observeLibraryBooks(
                    libraryId = libraryId,
                    query = request.query,
                    order = request.order,
                )
            }

    /**
     * PRODUCT_SPEC LIB-003 — the series axis takes no sort order, so it is kept out of [books]'
     * `combine` rather than folded into it. Changing the sort chips while the series tab is showing
     * would otherwise re-group the whole library to produce exactly the same list.
     */
    private val series: Flow<List<SeriesShelf>> = debouncedQuery
        .flatMapLatest { query -> observeLibrarySeries(libraryId = libraryId, query = query) }

    /**
     * Only the visible axis is collected. `flatMapLatest` on the tab cancels the other one, so the
     * library is not grouped into series for a user who is looking at the book list.
     */
    private val content: Flow<LibraryContent> = tab.flatMapLatest { current ->
        when (current) {
            LibraryTab.Books -> books.map { LibraryContent.OfBooks(it) }
            LibraryTab.Series -> series.map { LibraryContent.OfSeries(it) }
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
            books = (loaded as? LibraryContent.OfBooks)?.books.orEmpty(),
            series = (loaded as? LibraryContent.OfSeries)?.series.orEmpty(),
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

    fun onTabChanged(tab: LibraryTab) {
        controls.update { it.copy(tab = tab) }
    }

    /** One resolved request: what to search for and how to order the result. */
    private data class LibraryQuery(val query: String, val order: BookSortOrder)

    /** The order lives in preferences, not here — see [order]. */
    private data class LibraryControls(val query: String = "", val tab: LibraryTab = LibraryTab.Books)

    /**
     * Whichever axis is being collected, carrying its own list.
     *
     * A sealed type rather than two nullable lists in the state, so "the series tab is showing and it
     * is empty" cannot be confused with "the series tab is showing and the books happen to be stale".
     */
    private sealed interface LibraryContent {
        data class OfBooks(val books: List<Book>) : LibraryContent

        data class OfSeries(val series: List<SeriesShelf>) : LibraryContent
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
    val books: List<Book> = emptyList(),
    val series: List<SeriesShelf> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * PRODUCT_SPEC LIB-002 — which axis the library is being browsed along.
 *
 * Two entries today. LIB-002 also lists recently added, continue listening, downloaded, author and
 * genre, and this is the seam they arrive through; series is separate because it is the one axis whose
 * rows are not books.
 */
enum class LibraryTab { Books, Series }
