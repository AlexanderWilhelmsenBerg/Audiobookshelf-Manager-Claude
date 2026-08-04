package com.example.shelfplayer.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.LibraryId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.library.BookSortOrder
import com.example.shelfplayer.domain.usecase.ObserveLibraryBooksUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
     * The two inputs are combined into a named [LibraryQuery] rather than a `Pair`. A `Pair` here
     * left the compiler unable to resolve `component1`/`component2` against the many array and
     * collection overloads, and a request that names its fields reads better than `first`/`second`.
     */
    private val books = combine(
        controls
            .map(LibraryControls::query)
            .distinctUntilChanged()
            .debounce { query -> if (query.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS },
        controls.map(LibraryControls::order).distinctUntilChanged(),
    ) { query, order -> LibraryQuery(query, order) }
        .flatMapLatest { request ->
            observeLibraryBooks(
                libraryId = libraryId,
                query = request.query,
                order = request.order,
            )
        }

    val uiState: StateFlow<LibraryUiState> = combine(
        controls,
        books,
    ) { current, books ->
        LibraryUiState(
            query = current.query,
            order = current.order,
            books = books,
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

    fun onOrderChanged(order: BookSortOrder) {
        controls.update { it.copy(order = order) }
    }

    /** One resolved request: what to search for and how to order the result. */
    private data class LibraryQuery(val query: String, val order: BookSortOrder)

    private data class LibraryControls(val query: String = "", val order: BookSortOrder = BookSortOrder.Default)

    private companion object {
        /** PRODUCT_SPEC LIB-002: search debounce is 300 ms. */
        const val SEARCH_DEBOUNCE_MILLIS = 300L
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class LibraryUiState(
    val query: String = "",
    val order: BookSortOrder = BookSortOrder.Default,
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true,
)
