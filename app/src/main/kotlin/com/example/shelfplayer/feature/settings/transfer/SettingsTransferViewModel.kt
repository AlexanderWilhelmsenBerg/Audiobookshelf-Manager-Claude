package com.example.shelfplayer.feature.settings.transfer

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.settings.SettingsImport
import com.example.shelfplayer.domain.repository.SettingsTransferRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC SET-001 — the settings file, for both screens that touch it.
 *
 * One ViewModel rather than two halves, because the two screens ask the same two questions and the
 * *sign-in* screen needs the import before it has anything else: a fresh install has no profile, no
 * server and no settings screen to reach.
 *
 * The document itself never lives in this state. Export produces text and hands it straight to the file
 * the user picked; keeping it here would put every server address the app knows into a `StateFlow` that
 * outlives the screen for no reason at all.
 */
@HiltViewModel
class SettingsTransferViewModel @Inject constructor(private val transfer: SettingsTransferRepository) : ViewModel() {

    private val state = MutableStateFlow(SettingsTransferUiState())
    val uiState: StateFlow<SettingsTransferUiState> = state.asStateFlow()

    /**
     * Produces the document and hands it to [onReady], which writes it to the picked location.
     *
     * The callback shape is what keeps the `Uri` out of here: the screen owns the picker, this owns the
     * settings. It runs on the ViewModel's scope, so a screen that goes away mid-export does not leave a
     * half-written file — the write is one call and either happened or did not.
     */
    fun export(onReady: (document: String, suggestedFileName: String) -> Unit) {
        if (state.value.isBusy) return
        state.update { it.copy(isBusy = true, message = null, imported = null) }
        viewModelScope.launch {
            when (val result = transfer.export()) {
                is AppResult.Failure -> state.update { it.copy(isBusy = false, message = result.error.summary) }
                is AppResult.Success -> {
                    state.update { it.copy(isBusy = false) }
                    onReady(result.value.document, result.value.suggestedFileName)
                }
            }
        }
    }

    /**
     * Reports the outcome of the write the screen performed, so one message describes the whole action.
     *
     * The export is two halves in two places — this produces the text, the screen writes it to the picked
     * location — and only the screen knows whether the second half worked.
     */
    fun exportFinished(error: String?, savedMessage: String) = state.update { it.copy(message = error ?: savedMessage) }

    /** A failure the screen met before the repository was reached — a file that would not open. */
    fun failed(message: String) = state.update { it.copy(isBusy = false, message = message) }

    fun import(document: String, onImported: (SettingsImport) -> Unit = {}) {
        if (state.value.isBusy) return
        state.update { it.copy(isBusy = true, message = null, imported = null) }
        viewModelScope.launch {
            when (val result = transfer.import(document)) {
                is AppResult.Failure -> state.update { it.copy(isBusy = false, message = result.error.summary) }
                is AppResult.Success -> {
                    state.update { it.copy(isBusy = false, imported = result.value) }
                    onImported(result.value)
                }
            }
        }
    }

    /** Clears whatever was last said, so a message does not outlive the screen that explains it. */
    fun messageShown() = state.update { it.copy(message = null, imported = null) }
}

/**
 * @property message a sentence to show and then forget: a failure, or a confirmation.
 * @property imported what the last import did, which the screen turns into a sentence of its own. Kept
 *   apart from [message] because the counts need pluralisation and a ViewModel has no resources.
 */
@Immutable
data class SettingsTransferUiState(
    val isBusy: Boolean = false,
    val message: String? = null,
    val imported: SettingsImport? = null,
)
