package com.example.shelfplayer.feature.metadata

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ManagementAction
import com.example.shelfplayer.core.model.ManagementBlock
import com.example.shelfplayer.core.model.ManagementPermissions
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.BookMetadataEdit
import com.example.shelfplayer.core.model.library.BookMetadataError
import com.example.shelfplayer.core.model.library.BookMetadataField
import com.example.shelfplayer.core.model.library.CoverRejection
import com.example.shelfplayer.domain.repository.MetadataRepository
import com.example.shelfplayer.domain.usecase.ObserveManagementPermissionsUseCase
import com.example.shelfplayer.navigation.ShelfDestinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC MGR-001 — the metadata editor.
 *
 * ### Three versions of the same book, and why each exists
 *
 * - [EditMetadataUiState.form] is what the user is typing.
 * - [EditMetadataUiState.baseline] is what the book looked like when the editor opened. Dirtiness is
 *   measured against this, not against the server's current state, so that a change somebody else made
 *   while this screen was open does not silently become "your edit".
 * - The server's current state is fetched at save time and compared with the baseline. Where the two
 *   differ **and the user also changed the field**, there is a conflict.
 *
 * That third comparison is the whole of MGR-001's conflict handling, and it is the only kind available:
 * Audiobookshelf's metadata route has no `ETag` and honours no `If-Match`, so the server cannot be asked
 * to refuse a stale write. Detecting it here is not belt-and-braces; it is the mechanism.
 *
 * ### What is deliberately not here
 *
 * No offline queue. MGR-001 is explicit that privileged edits are not queued for blind offline execution
 * in version 1, so an edit made without a connection becomes a saved draft and stops there — visible,
 * named, and applied only when the user asks again.
 */
@HiltViewModel
class EditMetadataViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observePermissions: ObserveManagementPermissionsUseCase,
    private val metadata: MetadataRepository,
) : ViewModel() {

    private val bookId: LibraryItemId = LibraryItemId(
        requireNotNull(savedStateHandle.get<String>(ShelfDestinations.ARG_BOOK_ID)) {
            "Edit-metadata route is missing its ${ShelfDestinations.ARG_BOOK_ID} argument"
        },
    )

    private val _uiState = MutableStateFlow(EditMetadataUiState())
    val uiState: StateFlow<EditMetadataUiState> = _uiState.asStateFlow()

    private var profileId: ProfileId? = null

    init {
        viewModelScope.launch {
            val scope = observePermissions(bookId).first()
            profileId = scope?.profileId
            val book = scope?.book
            if (scope == null || book == null) {
                _uiState.update { it.copy(isLoading = false, isMissing = true) }
                return@launch
            }
            val fromBook = BookMetadataEdit.of(book)
            // A draft is offered rather than applied. MGR-001 wants an *explicit* unsaved draft, and a
            // form that silently opens with yesterday's abandoned edit is not explicit — the user cannot
            // tell it apart from what the server holds.
            val draft = metadata.observeDraft(scope.profileId, bookId).first()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    title = book.title,
                    permissions = scope.permissions,
                    baseline = fromBook,
                    form = fromBook,
                    draft = draft?.takeIf { saved -> saved.changesFrom(fromBook).isNotEmpty() },
                )
            }
        }
    }

    /**
     * PRODUCT_SPEC MGR-002 — the user picked an image; hold it for preview and say why if it cannot be used.
     *
     * Nothing is sent here. MGR-002 requires a preview before commit, so the picked image sits in state
     * until the user confirms — which is also what makes "I picked the wrong photo" recoverable.
     */
    fun coverPicked(picked: PickedCover) {
        val rejection = picked.candidate.rejection()
        _uiState.update {
            it.copy(
                pickedCover = if (rejection == null) picked else null,
                coverRejection = rejection,
                errorSummary = null,
            )
        }
    }

    fun coverPickFailed(summary: String) {
        _uiState.update { it.copy(pickedCover = null, coverRejection = null, errorSummary = summary) }
    }

    fun discardPickedCover() {
        _uiState.update { it.copy(pickedCover = null, coverRejection = null) }
    }

    /** PRODUCT_SPEC MGR-002 — the commit half, after the preview. */
    fun confirmCover() {
        val profile = profileId ?: return
        val picked = _uiState.value.pickedCover ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorSummary = null) }
            val result = metadata.uploadCover(profile, bookId, picked.bytes, picked.candidate.mimeType)
            onCoverResult(result)
        }
    }

    /** PRODUCT_SPEC MGR-002 — "removing a cover requires confirmation", which the screen has already taken. */
    fun removeCover() {
        val profile = profileId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorSummary = null) }
            onCoverResult(metadata.removeCover(profile, bookId))
        }
    }

    private fun onCoverResult(result: AppResult<Book>) {
        when (result) {
            is AppResult.Failure -> _uiState.update {
                it.copy(isSaving = false, errorSummary = result.error.summary)
            }
            is AppResult.Success -> {
                // Not `adopt`: a cover change must not discard the metadata the user is part-way through
                // typing. Only the cover and the baseline's identity moved.
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        pickedCover = null,
                        coverRejection = null,
                        coverVersion = it.coverVersion + 1,
                    )
                }
            }
        }
    }

    fun edit(transform: (BookMetadataEdit) -> BookMetadataEdit) {
        _uiState.update { state ->
            val form = transform(state.form)
            state.copy(form = form, savedAt = null, errorSummary = null)
        }
    }

    /** PRODUCT_SPEC MGR-001 — the offered draft is taken up, replacing the form. */
    fun applyDraft() {
        _uiState.update { state ->
            state.copy(form = state.draft ?: state.form, draft = null)
        }
    }

    /** PRODUCT_SPEC MGR-001 — "user may discard a draft". */
    fun discardDraft() {
        val profile = profileId ?: return
        _uiState.update { it.copy(draft = null) }
        viewModelScope.launch { metadata.discardDraft(profile, bookId) }
    }

    /** Called when the editor is left with unsaved changes, so the text survives the process. */
    fun keepDraft() {
        val profile = profileId ?: return
        val state = _uiState.value
        if (state.changed.isEmpty()) return
        viewModelScope.launch { metadata.saveDraft(profile, bookId, state.form) }
    }

    /** PRODUCT_SPEC MGR-001 — abandons the edit and the stored draft with it. */
    fun revert() {
        val profile = profileId ?: return
        _uiState.update { it.copy(form = it.baseline, conflicts = emptySet(), errorSummary = null) }
        viewModelScope.launch { metadata.discardDraft(profile, bookId) }
    }

    /** PRODUCT_SPEC MGR-001 — the conflict view's "reload": take the server's version, losing the edit. */
    fun reload() {
        val profile = profileId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val fresh = metadata.reload(profile, bookId)) {
                is AppResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, errorSummary = fresh.error.summary)
                }
                is AppResult.Success -> adopt(fresh.value)
            }
        }
    }

    /**
     * PRODUCT_SPEC MGR-001 — validate, load the latest item, check for a conflict, then send.
     *
     * @param overwrite the user has seen the conflicting fields and chosen to send anyway.
     */
    fun save(overwrite: Boolean = false) {
        val profile = profileId ?: return
        val state = _uiState.value
        val errors = state.form.validate()
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(fieldErrors = errors) }
            return
        }
        if (state.changed.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, fieldErrors = emptyMap(), errorSummary = null) }

            // MGR-001: "editor loads the latest item before save". The reload also writes it to Room, so
            // the rest of the app stops showing a stale book whether or not this save goes ahead.
            val latest = when (val reloaded = metadata.reload(profile, bookId)) {
                is AppResult.Failure -> {
                    onSaveFailed(profile, reloaded.error)
                    return@launch
                }
                is AppResult.Success -> BookMetadataEdit.of(reloaded.value)
            }
            val conflicts = if (overwrite) emptySet() else state.conflictsAgainst(latest)
            if (conflicts.isNotEmpty()) {
                _uiState.update { it.copy(isSaving = false, conflicts = conflicts, server = latest) }
                metadata.saveDraft(profile, bookId, state.form)
                return@launch
            }

            when (val saved = metadata.save(profile, bookId, state.form, state.changed)) {
                is AppResult.Failure -> onSaveFailed(profile, saved.error)
                is AppResult.Success -> adopt(saved.value)
            }
        }
    }

    /**
     * PRODUCT_SPEC MGR-001 — "network failure retains an explicit unsaved draft locally".
     *
     * Written on *every* failure rather than only on a network one. A `403` and a timeout leave the user
     * in the same position — their words are on screen and nowhere else — and the app cannot reliably tell
     * a refusal apart from a proxy that returned one.
     */
    private suspend fun onSaveFailed(profile: ProfileId, error: AppError) {
        metadata.saveDraft(profile, bookId, _uiState.value.form)
        _uiState.update { it.copy(isSaving = false, errorSummary = error.summary, hasStoredDraft = true) }
    }

    private fun adopt(book: Book) {
        val fresh = BookMetadataEdit.of(book)
        _uiState.update {
            it.copy(
                isSaving = false,
                title = book.title,
                baseline = fresh,
                form = fresh,
                server = null,
                conflicts = emptySet(),
                hasStoredDraft = false,
                savedAt = SaveOutcome.Saved,
            )
        }
    }
}

/** What happened to the last save, for the screen to acknowledge. */
enum class SaveOutcome { Saved, }

/**
 * PRODUCT_SPEC MGR-001 — the editor's whole state.
 *
 * @property baseline the book as the editor opened it. Dirtiness is measured against this.
 * @property server the server's current version, present only while a conflict is unresolved.
 * @property conflicts fields the user changed **and** somebody else changed. Not every difference — a
 *   field only they touched is not a conflict, and a field only the other person touched is already
 *   reconciled by the reload.
 * @property draft a stored draft the user has not yet accepted or discarded.
 */
data class EditMetadataUiState(
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val isSaving: Boolean = false,
    val title: String = "",
    val permissions: ManagementPermissions? = null,
    val baseline: BookMetadataEdit = Empty,
    val form: BookMetadataEdit = Empty,
    val server: BookMetadataEdit? = null,
    val draft: BookMetadataEdit? = null,
    val conflicts: Set<BookMetadataField> = emptySet(),
    val fieldErrors: Map<BookMetadataField, BookMetadataError> = emptyMap(),
    val errorSummary: String? = null,
    val hasStoredDraft: Boolean = false,
    val savedAt: SaveOutcome? = null,
    /** PRODUCT_SPEC MGR-002 — picked and previewed, not yet sent. */
    val pickedCover: PickedCover? = null,
    val coverRejection: CoverRejection? = null,
    /**
     * Increments on every successful cover change, so the preview stops showing the old image.
     *
     * The server's own cache key is the item's `updatedAt`, which the refresh has already moved. This is
     * the *local* equivalent: a value Compose can key on so the composable that draws the cover is
     * recomposed rather than reusing what it already drew.
     */
    val coverVersion: Int = 0,
) {
    val changed: Set<BookMetadataField> get() = form.changesFrom(baseline)

    val canEdit: Boolean get() = permissions?.isAvailable(ManagementAction.EditMetadata) == true

    val block: ManagementBlock? get() = permissions?.blockOn(ManagementAction.EditMetadata)

    val canSave: Boolean get() = canEdit && !isSaving && changed.isNotEmpty()

    /**
     * Fields this user changed that the server has also changed since the editor opened.
     *
     * The `&&` is the whole rule. A field only the other party touched needs no decision — the reload has
     * already taken their version, and the user never expressed an opinion about it.
     */
    fun conflictsAgainst(latest: BookMetadataEdit): Set<BookMetadataField> =
        changed intersect latest.changesFrom(baseline)

    private companion object {
        val Empty = BookMetadataEdit(
            title = "",
            subtitle = "",
            authors = emptyList(),
            narrators = emptyList(),
            series = emptyList(),
            genres = emptyList(),
            tags = emptyList(),
            publishedYear = "",
            publisher = "",
            description = "",
            isbn = "",
            asin = "",
            language = "",
            isExplicit = false,
            isAbridged = false,
        )
    }
}
