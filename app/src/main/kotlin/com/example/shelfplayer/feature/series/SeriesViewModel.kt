package com.example.shelfplayer.feature.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.usecase.ObserveSeriesUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** PRODUCT_SPEC LIB-003 / TC-16 — one series and its books, in order. */
@HiltViewModel
class SeriesViewModel @Inject constructor(savedStateHandle: SavedStateHandle, observeSeries: ObserveSeriesUseCase) :
    ViewModel() {

    private val seriesId = SeriesId(
        requireNotNull(savedStateHandle.get<String>(ShelfDestinations.ARG_SERIES_ID)) {
            "Series route is missing its ${ShelfDestinations.ARG_SERIES_ID} argument"
        },
    )

    val uiState: StateFlow<SeriesUiState> = observeSeries(seriesId)
        .map { shelf -> SeriesUiState(shelf = shelf, isLoading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SeriesUiState(),
        )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * `shelf == null` once [isLoading] is false is not an error state.
 *
 * It is a series the active profile can no longer see — every book in it revoked, or the profile
 * switched while the screen was open (PRODUCT_SPEC 5.2). The screen says so rather than showing a
 * title with nothing under it.
 */
data class SeriesUiState(val shelf: SeriesShelf? = null, val isLoading: Boolean = true)
