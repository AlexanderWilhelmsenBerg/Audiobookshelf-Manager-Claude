package com.example.shelfplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
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
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    init {
        viewModelScope.launch {
            state.collect {
                if (it.paired &&
                    mutableMessage.value == GarminDeviceMessage.ConfirmWatch
                ) {
                    mutableMessage.value = null
                }
            }
        }
    }
    fun cancelPairing() = act(null, repository::cancelPairing)
    fun configure(profile: String?, device: String?, url: String, user: String, password: String) =
        act(GarminDeviceMessage.Configured) { repository.configure(profile, device, url, user, password) }
    fun pair() = act(GarminDeviceMessage.ConfirmWatch, repository::pair)
    fun forceSync() = act(GarminDeviceMessage.Queued, repository::forceSync)
    fun download(id: String) = act(GarminDeviceMessage.Queued) { repository.queueDownload(id) }
    fun refresh() = act(null, repository::refresh)
    fun dismissMessage() {
        mutableMessage.value = null
    }
    private fun act(success: GarminDeviceMessage?, action: suspend () -> AppResult<Unit>) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                mutableMessage.value = when (val result = action()) {
                    is AppResult.Success -> success

                    is AppResult.Failure -> when (val error = result.error) {
                        is AppError.Authentication -> GarminDeviceMessage.LoginRejected

                        is AppError.ApiCompatibility -> if (error.summary.contains(
                                "content type",
                            )
                        ) {
                            GarminDeviceMessage.ContentType
                        } else {
                            GarminDeviceMessage.Failed
                        }

                        else -> GarminDeviceMessage.Failed
                    }
                }
            } finally {
                mutableBusy.value = false
            }
        }
    }
    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

enum class GarminDeviceMessage { Queued, ConfirmWatch, Failed, Configured, LoginRejected, ContentType }
