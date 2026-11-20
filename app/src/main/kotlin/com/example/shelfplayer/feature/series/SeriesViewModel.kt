package com.example.shelfplayer.feature.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.SeriesId
import com.example.shelfplayer.domain.library.SeriesOrder
import com.example.shelfplayer.domain.library.SeriesOrderMove
import com.example.shelfplayer.domain.library.SeriesOrderRow
import com.example.shelfplayer.domain.library.SeriesShelf
import com.example.shelfplayer.domain.repository.MetadataRepository
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
import kotlinx.coroutines.flow.first
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
    private val metadata: MetadataRepository,
) : ViewModel() {

    private val seriesId = SeriesId(
        requireNotNull(savedStateHandle.get<String>(ShelfDestinations.ARG_SERIES_ID)) {
            "Series route is missing its ${ShelfDestinations.ARG_SERIES_ID} argument"
        },
    )

    private val editing = MutableStateFlow<SeriesEditorState?>(null)
    private val refresh = MutableStateFlow<SeriesRefresh?>(null)

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

    val uiState: StateFlow<SeriesUiState> = combine(browse, editing, refresh) { context, editor, pending ->
        val refreshState = pending?.takeIf { it.owner == context.owner }?.state
        context.state.copy(
            canEdit = context.state.canEdit && refreshState == null,
            editor = editor?.takeIf { it.owner == context.owner && context.state.canEdit },
            orderRefresh = refreshState,
        )
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
        if (!context.state.canEdit || refresh.value?.owner == owner) return
        val order = SeriesOrder.from(shelf, context.state.missingBooks)
        editing.value = SeriesEditorState(owner, order, initialMissing = order.missingBooks)
    }

    fun cancelEditing() {
        if (editing.value?.saving != true) editing.value = null
    }

    fun insertMissing(number: Int) = changeOrder { it.insertMissing(number) }

    fun removeMissing(number: Int) = changeOrder { it.removeMissing(number) }

    fun ungroup(slotKey: String) = changeOrder { it.ungroup(slotKey) }

    fun undo() {
        editing.update { draft -> draft?.takeIf { it.owner == browse.value.owner }?.undo() ?: draft }
    }

    fun move(from: SeriesOrderRow, to: SeriesOrderRow, move: SeriesOrderMove) = changeOrder { it.move(from, to, move) }

    fun save() {
        val draft = editing.value ?: return
        if (draft.saving || !draft.hasChanges || browse.value.owner != draft.owner) return
        editing.value = draft.copy(saving = true, result = null)
        viewModelScope.launch {
            val result = saveOrder(draft.owner, seriesId, draft.order)
            recordPendingRefresh(draft, result)
            editing.update { active ->
                when {
                    active?.owner != draft.owner -> active
                    result.error == null -> null
                    else -> draft.copy(order = result.order, result = result)
                }
            }
        }
    }

    fun refreshSavedOrder() {
        val pending = refresh.value ?: return
        if (pending.state == SeriesOrderRefresh.Refreshing || browse.value.owner != pending.owner) return
        val running = pending.copy(state = SeriesOrderRefresh.Refreshing)
        refresh.value = running
        viewModelScope.launch {
            val remaining = pending.books.filterTo(mutableSetOf()) { id ->
                val owner = pending.owner
                profiles.observeActiveProfile().first()?.id != owner.profileId ||
                    profiles.activeProfileGeneration() != owner.generation ||
                    metadata.reload(owner.profileId, id) is AppResult.Failure
            }
            refresh.compareAndSet(running, pending.copy(books = remaining).takeIf { remaining.isNotEmpty() })
        }
    }

    private fun recordPendingRefresh(draft: SeriesEditorState, result: SeriesOrderSave) {
        if (browse.value.owner != draft.owner) return
        val confirmed = draft.order.changes.take(result.applied).mapTo(mutableSetOf()) { it.bookId }
        refresh.update { pending ->
            val previous = pending?.books.orEmpty().takeIf { pending?.owner == draft.owner }.orEmpty()
            val unresolved = (previous - confirmed) + result.unrefreshedBooks
            SeriesRefresh(draft.owner, unresolved).takeIf { unresolved.isNotEmpty() }
        }
    }

    private fun changeOrder(transform: (SeriesOrder) -> SeriesOrder) {
        editing.update { draft ->
            if (draft == null || draft.saving || draft.owner != browse.value.owner) {
                draft
            } else {
                draft.edit(transform(draft.order))
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
    val orderRefresh: SeriesOrderRefresh? = null,
)

enum class SeriesOrderRefresh { Required, Refreshing }

data class SeriesEditorState(
    val owner: SeriesOrderOwner,
    val order: SeriesOrder,
    val initialMissing: Set<Int>,
    val saving: Boolean = false,
    val result: SeriesOrderSave? = null,
    val history: List<SeriesOrder> = emptyList(),
) {
    val hasChanges: Boolean get() = order.changes.isNotEmpty() || order.missingBooks != initialMissing

    internal fun edit(next: SeriesOrder): SeriesEditorState =
        if (saving || next == order) this else copy(order = next, history = history + order, result = null)

    internal fun undo(): SeriesEditorState {
        val previous = history.lastOrNull() ?: return this
        return if (saving) {
            this
        } else {
            copy(
                order = order.restoreLayout(previous),
                history = history.dropLast(1),
                result = null,
            )
        }
    }
}

data class SeriesEditorActions(
    val start: () -> Unit = {},
    val cancel: () -> Unit = {},
    val save: () -> Unit = {},
    val move: (SeriesOrderRow, SeriesOrderRow, SeriesOrderMove) -> Unit = { _, _, _ -> },
    val insertMissing: (Int) -> Unit = {},
    val ungroup: (String) -> Unit = {},
    val undo: () -> Unit = {},
    val removeMissing: (Int) -> Unit = {},
    val refresh: () -> Unit = {},
)

private data class SeriesRefresh(
    val owner: SeriesOrderOwner,
    val books: Set<LibraryItemId>,
    val state: SeriesOrderRefresh = SeriesOrderRefresh.Required,
)

private data class SeriesContext(val owner: SeriesOrderOwner? = null, val state: SeriesUiState = SeriesUiState())
