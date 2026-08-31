package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.domain.repository.ResumePolicyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Owns the one profile-scoped choice of whether resume follows Audiobookshelf across clients. */
@HiltViewModel
class CrossDeviceResumeViewModel @Inject constructor(
    private val policy: ResumePolicyRepository,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = policy.observeCrossDeviceResumeEnabled().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = true,
    )

    fun onEnabledChanged(enabled: Boolean) {
        viewModelScope.launch { policy.setCrossDeviceResumeEnabled(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
