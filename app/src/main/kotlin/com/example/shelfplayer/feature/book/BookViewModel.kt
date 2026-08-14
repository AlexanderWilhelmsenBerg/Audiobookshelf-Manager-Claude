package com.example.shelfplayer.feature.book

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.ObserveBookChaptersUseCase
import com.example.shelfplayer.domain.usecase.ObserveBookDetailsUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    observeChapters: ObserveBookChaptersUseCase,
    history: PlaybackHistoryRepository,
    profiles: ProfileRepository,
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
     * PRODUCT_SPEC PLAY-003 / 11.1 — what the overflow menu's sheets need beyond the book itself.
     *
     * One flow rather than three, for the reason `SettingsViewModel` gives: three flows into the same screen
     * are three chances for it to hold one value from before a change and two from after. The history and the
     * chapters travel together because the history sheet renders them together — a row's chapter label is what
     * turns "at 4:12:30" into a memory.
     */
    val menu: StateFlow<BookMenuState> = combine(
        history.observe(bookId),
        observeChapters(bookId),
        profiles.observeServers(),
        profiles.observeActiveProfile(),
        uiState,
    ) { entries, chapters, servers, profile, state ->
        val book = (state as? BookUiState.Loaded)?.book
        BookMenuState(
            history = entries,
            chapters = chapters,
            // PRODUCT_SPEC DL-001 — the server's grant, not a guess. `false` while no profile is loaded,
            // which is the same safe direction the column defaults to.
            canDownload = profile?.canDownload == true,
            // ADR note in `BookOverflowMenu`: the web client's own route, not an API endpoint.
            webUrl = book?.let { loaded ->
                servers.firstOrNull { it.id == loaded.serverId }
                    ?.baseUrl
                    ?.trimEnd('/')
                    ?.let { base -> "$base/item/${loaded.id.value}" }
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = BookMenuState(),
    )

    private val _message = MutableStateFlow<String?>(null)

    /** A one-line result for an action that reached the network, or `null`. Cleared when acknowledged. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onMessageShown() {
        _message.value = null
    }

    /**
     * PRODUCT_SPEC PLAY-004 — "marking finished is explicit", including un-marking it.
     *
     * The position comes from the row rather than from the caller, so un-marking leaves the listener where
     * they were. Marking finished ignores it — the repository moves the position to the end of the book,
     * which is the only position a finished book can honestly report.
     */
    fun onFinishedChanged(isFinished: Boolean) {
        viewModelScope.launch {
            val at = currentBook()?.progress?.position ?: Duration.ZERO
            report(playbackRepository.setFinished(bookId, isFinished, at))
        }
    }

    /**
     * PRODUCT_SPEC PLAY-004 / 21 — sends the book back to the beginning, deliberately.
     *
     * The one destructive action on this screen, and it is destructive in a specific and limited way: it
     * discards **where you had got to**, on this device and on the server, and nothing else. No audio file is
     * touched, no download is removed, and the book stays in the library. The confirmation says exactly that,
     * because "discard progress" on its own could mean any of them.
     *
     * Implemented as an un-finish to position zero — the same progress `PATCH` the *Finished* control already
     * uses, whose acceptance is a captured contract (`contracts/media-progress-set-unfinished.json`). There is
     * no separate discard route in the captures, and inventing one would be guessing at the server
     * (PRODUCT_SPEC 22.4).
     */
    fun onDiscardProgress() {
        viewModelScope.launch {
            report(playbackRepository.setFinished(bookId, isFinished = false, position = Duration.ZERO))
        }
    }

    private suspend fun currentBook(): Book? = (uiState.first() as? BookUiState.Loaded)?.book

    /**
     * Surfaces a failure and stays quiet about success.
     *
     * Both writes here are local-first: the row is already written by the time the network is attempted, so a
     * failure is "the server has not heard yet" rather than "nothing happened". Saying so is the honest
     * message; a success banner for something the screen already shows would be noise.
     */
    private fun report(result: com.example.shelfplayer.core.model.AppResult<Unit>) {
        if (result is com.example.shelfplayer.core.model.AppResult.Failure) _message.value = result.error.summary
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * PRODUCT_SPEC LIB-004 / PLAY-003 — what the overflow menu can show, beyond the book.
 *
 * @property webUrl this item in the server's own web client, or `null` when the server's address is not
 *   known — which is the state of a profile that has been signed out.
 */
data class BookMenuState(
    val history: List<PlaybackHistoryEntry> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    /**
     * PRODUCT_SPEC DL-001 — whether this account may download from its server.
     *
     * Decides *which* disabled state the download button shows, not whether it downloads: downloads are
     * Phase 3 and nothing here starts one. The distinction matters because "not built yet" will change and
     * "your account may not" will not, and a single message would leave someone waiting for a release that
     * cannot help them.
     */
    val canDownload: Boolean = false,
    val webUrl: String? = null,
)

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
