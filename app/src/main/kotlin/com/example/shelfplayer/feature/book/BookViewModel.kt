package com.example.shelfplayer.feature.book

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.usecase.ObserveBookDetailsUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration

/** PRODUCT_SPEC LIB-004 — the book detail screen, read entirely from cached state. */
@HiltViewModel
class BookViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeBookDetails: ObserveBookDetailsUseCase,
    private val playbackRepository: PlaybackRepository,
) : ViewModel() {

    private val bookId: LibraryItemId = LibraryItemId(
        requireNotNull(savedStateHandle.get<String>(ShelfDestinations.ARG_BOOK_ID)) {
            "Book route is missing its ${ShelfDestinations.ARG_BOOK_ID} argument"
        },
    )

    val uiState: StateFlow<BookUiState> = observeBookDetails(bookId)
        .map { book ->
            if (book == null) BookUiState.Missing else BookUiState.Loaded(book)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = BookUiState.Loading,
        )

    /**
     * PRODUCT_SPEC PLAY-004 — "marking finished is explicit", including un-marking it.
     *
     * The position comes from the row rather than from the caller, so un-marking leaves the listener where
     * they were. Marking finished ignores it — the repository moves the position to the end of the book,
     * which is the only position a finished book can honestly report.
     */
    fun onFinishedChanged(isFinished: Boolean) {
        viewModelScope.launch {
            val at = (uiState.first() as? BookUiState.Loaded)?.book?.progress?.position ?: Duration.ZERO
            playbackRepository.setFinished(bookId, isFinished, at)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * PRODUCT_SPEC 21 — loading and "not there" are different states.
 *
 * A screen that renders "book not available" while the query is still running is the reason this is
 * a sealed hierarchy rather than a nullable field.
 */
sealed interface BookUiState {
    data object Loading : BookUiState

    data object Missing : BookUiState

    data class Loaded(val book: Book) : BookUiState
}
