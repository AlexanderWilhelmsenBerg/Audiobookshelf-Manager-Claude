package com.example.shelfplayer.feature.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.BuildConfig
import com.example.shelfplayer.core.model.StorageDiagnostics
import com.example.shelfplayer.domain.repository.DiagnosticsRepository
import com.example.shelfplayer.domain.usecase.ObserveServerDiagnosticsUseCase
import com.example.shelfplayer.domain.usecase.ServerDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * PRODUCT_SPEC SYNC-001 / SET-002 — the readings, as opposed to the settings.
 *
 * These used to sit under the libraries list in Settings, which put a screenful of diagnostics between
 * the user and the one preference on that screen. They are here now, under a Testing heading, because
 * that is what they are for: making an acceptance case checkable on a device instead of over `adb`.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    diagnostics: DiagnosticsRepository,
    observeServerDiagnostics: ObserveServerDiagnosticsUseCase,
) : ViewModel() {

    val uiState: StateFlow<AboutUiState> = combine(
        diagnostics.observeStorage(),
        observeServerDiagnostics(),
    ) { storage, server ->
        AboutUiState(
            versionName = BuildConfig.VERSION_NAME,
            storage = storage,
            server = server,
            isLoaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        // PRODUCT_SPEC 16.3: no unbounded collection in a lifecycle owner.
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = AboutUiState(versionName = BuildConfig.VERSION_NAME),
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * @property server what the capability handshake learned, or `null` while no profile is active.
 * @property isLoaded whether the first read has arrived. Zeroes before it has would read as facts.
 */
data class AboutUiState(
    val versionName: String = "",
    val storage: StorageDiagnostics = StorageDiagnostics(),
    val server: ServerDiagnostics? = null,
    val isLoaded: Boolean = false,
)
