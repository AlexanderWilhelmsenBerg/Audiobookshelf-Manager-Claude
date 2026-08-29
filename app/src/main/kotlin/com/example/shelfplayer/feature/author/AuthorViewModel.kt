package com.example.shelfplayer.feature.author

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AuthorId
import com.example.shelfplayer.domain.library.AuthorShelf
import com.example.shelfplayer.domain.usecase.ObserveAuthorUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** PRODUCT_SPEC §62 "author view" — one author and their books. The same shape as `SeriesViewModel`. */
@HiltViewModel
class AuthorViewModel @Inject constructor(savedStateHandle: SavedStateHandle, observeAuthor: ObserveAuthorUseCase) :
    ViewModel() {

    private val authorId = AuthorId(
        requireNotNull(savedStateHandle.get<String>(ShelfDestinations.ARG_AUTHOR_ID)) {
            "Author route is missing its ${ShelfDestinations.ARG_AUTHOR_ID} argument"
        },
    )

    val uiState: StateFlow<AuthorUiState> = observeAuthor(authorId)
        .map { shelf -> AuthorUiState(shelf = shelf, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = AuthorUiState(),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * `shelf == null` once [isLoading] is false is not an error state.
 *
 * It is an author whose books the active profile can no longer see — every one revoked, or the profile
 * switched while the screen was open (PRODUCT_SPEC 5.2). The screen says so rather than showing a name
 * with nothing under it.
 */
data class AuthorUiState(val shelf: AuthorShelf? = null, val isLoading: Boolean = true)
