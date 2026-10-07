package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.garmin.GarminDeviceRepository
import com.example.shelfplayer.garmin.GarminDeviceUi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GarminDeviceViewModel @Inject internal constructor(private val repository: GarminDeviceRepository) : ViewModel() {
    val state = repository.observe().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        GarminDeviceUi(),
    )
    private val mutableMessage = MutableStateFlow<GarminDeviceMessage?>(null)
    val message = mutableMessage.asStateFlow()
    fun pair() = act(GarminDeviceMessage.ConfirmWatch, repository::pair)
    fun forceSync() = act(GarminDeviceMessage.Queued, repository::forceSync)
    fun download(id: String) = act(GarminDeviceMessage.Queued) { repository.queueDownload(id) }
    fun refresh() = act(null, repository::refresh)
    fun dismissMessage() {
        mutableMessage.value = null
    }
    private fun act(success: GarminDeviceMessage?, action: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            mutableMessage.value =
                if (action() is AppResult.Success) success else GarminDeviceMessage.Failed
        }
    }
    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

enum class GarminDeviceMessage { Queued, ConfirmWatch, Failed }
