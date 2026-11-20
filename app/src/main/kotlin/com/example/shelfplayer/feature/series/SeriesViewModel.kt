package com.example.shelfplayer.feature.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesOrderMove
import com.example.shelfplayer.domain.library.SeriesOrderRow
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.SeriesOrderRepository
import com.example.shelfplayer.domain.usecase.ObserveSeriesUseCase
import com.example.shelfplayer.domain.usecase.SaveSeriesOrderUseCase
import com.example.shelfplayer.domain.usecase.SeriesOrderOwner
import com.example.shelfplayer.domain.usecase.SeriesOrderSave
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** PRODUCT_SPEC LIB-003 / TC-16 — one series and its books, in order. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeSeries: ObserveSeriesUseCase,
    private val profiles: ProfileRepository,
    orders: SeriesOrderRepository,
    private val saveOrder: SaveSeriesOrderUseCase,
) : ViewModel() {

    private val seriesId = SeriesId(
        requireNotNull(savedStateHandle.get<String>(ShelfDestinations.ARG_SERIES_ID)) {
            "Series route is missing its ${ShelfDestinations.ARG_SERIES_ID} argument"
        },
    )

    private val editing = MutableStateFlow<SeriesEditorState?>(null)

    private val browse = profiles.observeActiveProfile().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(SeriesContext(state = SeriesUiState(isLoading = false)))
        } else {
            val owner = SeriesOrderOwner(profile.id, profiles.activeProfileGeneration())
            combine(observeSeries.forProfile(profile.id, seriesId), orders.observeMissingBooks(profile.id, seriesId)) {
                    shelf,
                    missing,
                ->
                SeriesContext(
                    owner = owner,
                    state = SeriesUiState(
                        shelf = shelf,
                        isLoading = false,
                        missingBooks = missing,
                        canEdit = shelf != null && profile.canUpdate && !profile.requiresReauthentication,
                    ),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SeriesContext())

    val uiState: StateFlow<SeriesUiState> = combine(browse, editing) { context, editor ->
        context.state.copy(editor = editor?.takeIf { it.owner == context.owner && context.state.canEdit })
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SeriesUiState(),
        )

    fun startEditing() {
        val context = browse.value
        val owner = context.owner ?: return
        val shelf = context.state.shelf ?: return
        if (!context.state.canEdit) return
        val order = SeriesOrder.from(shelf, context.state.missingBooks)
        editing.value = SeriesEditorState(owner, order, initialMissing = order.missingBooks)
    }

    fun cancelEditing() {
        if (editing.value?.saving != true) editing.value = null
    }

    fun addMissing(number: Int) = changeOrder { it.addMissing(number) }

    fun removeMissing(number: Int) = changeOrder { it.removeMissing(number) }

    fun move(from: SeriesOrderRow, to: SeriesOrderRow, move: SeriesOrderMove) = changeOrder { it.move(from, to, move) }

    fun save() {
        val draft = editing.value ?: return
        if (draft.saving || !draft.hasChanges || browse.value.owner != draft.owner) return
        editing.value = draft.copy(saving = true, result = null)
        viewModelScope.launch {
            val result = saveOrder(draft.owner, seriesId, draft.order)
            editing.update { active ->
                when {
                    active?.owner != draft.owner -> active
                    result.error == null -> null
                    else -> draft.copy(order = result.order, result = result)
                }
            }
        }
    }

    private fun changeOrder(transform: (SeriesOrder) -> SeriesOrder) {
        editing.update { draft ->
            if (draft == null || draft.saving || draft.owner != browse.value.owner) {
                draft
            } else {
                draft.copy(order = transform(draft.order), result = null)
            }
        }
    }

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
data class SeriesUiState(
    val shelf: SeriesShelf? = null,
    val isLoading: Boolean = true,
    val missingBooks: Set<Int> = emptySet(),
    val canEdit: Boolean = false,
    val editor: SeriesEditorState? = null,
)

data class SeriesEditorState(
    val owner: SeriesOrderOwner,
    val order: SeriesOrder,
    val initialMissing: Set<Int>,
    val saving: Boolean = false,
    val result: SeriesOrderSave? = null,
) {
    val hasChanges: Boolean get() = order.changes.isNotEmpty() || order.missingBooks != initialMissing
}

data class SeriesEditorActions(
    val start: () -> Unit = {},
    val cancel: () -> Unit = {},
    val save: () -> Unit = {},
    val move: (SeriesOrderRow, SeriesOrderRow, SeriesOrderMove) -> Unit = { _, _, _ -> },
    val addMissing: (Int) -> Unit = {},
    val removeMissing: (Int) -> Unit = {},
)

private data class SeriesContext(val owner: SeriesOrderOwner? = null, val state: SeriesUiState = SeriesUiState())
