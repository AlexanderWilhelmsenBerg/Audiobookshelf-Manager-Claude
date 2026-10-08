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
    fun cancelPairing() = act(null, action = repository::cancelPairing)
    fun configure(profile: String?, device: String?, url: String) =
        act(GarminDeviceMessage.Configured, setup = true) { repository.configure(profile, device, url) }
    fun pair() = act(GarminDeviceMessage.ConfirmWatch, action = repository::pair)
    fun forceSync() = act(GarminDeviceMessage.Queued, action = repository::forceSync)
    fun download(id: String) = act(GarminDeviceMessage.Queued) { repository.queueDownload(id) }
    fun refresh() = act(null, action = repository::refresh)
    fun dismissMessage() {
        mutableMessage.value = null
    }
    private fun act(success: GarminDeviceMessage?, setup: Boolean = false, action: suspend () -> AppResult<Unit>) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                mutableMessage.value = when (val result = action()) {
                    is AppResult.Success -> success
                    is AppResult.Failure -> garminFailureMessage(result.error, setup)
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

enum class GarminDeviceMessage {
    Queued,
    ConfirmWatch,
    Failed,
    Configured,
    LoginRejected,
    WatchLoginRequired,
    ContentType,
    SetupUnavailable,
    InvalidSetup,
    IncompatibleSidecar,
    AccountMismatch,
    PairWatch,
    UpgradeWatch,
}

internal fun garminFailureMessage(error: AppError, setup: Boolean): GarminDeviceMessage = when (error) {
    is AppError.Authentication -> GarminDeviceMessage.LoginRejected

    is AppError.ApiCompatibility -> setupCompatibilityMessage(error.missingCapability)

    is AppError.Authorization -> setupAuthorizationMessage(error.missingPermission)

    is AppError.Network, is AppError.Timeout -> if (setup) {
        GarminDeviceMessage.SetupUnavailable
    } else {
        GarminDeviceMessage.Failed
    }

    is AppError.Validation -> if (setup) GarminDeviceMessage.InvalidSetup else GarminDeviceMessage.Failed

    is AppError.Server, is AppError.Storage, is AppError.Download, is AppError.Playback,
    is AppError.Security, is AppError.Conflict, is AppError.Canceled, is AppError.Unknown,
    -> GarminDeviceMessage.Failed
}

private fun setupCompatibilityMessage(reason: String?): GarminDeviceMessage = when (reason) {
    "sidecar_content_type" -> GarminDeviceMessage.ContentType
    "sidecar_schema" -> GarminDeviceMessage.IncompatibleSidecar
    "provider_setup" -> GarminDeviceMessage.UpgradeWatch
    else -> GarminDeviceMessage.Failed
}

private fun setupAuthorizationMessage(reason: String?): GarminDeviceMessage = when (reason) {
    "sidecar_account" -> GarminDeviceMessage.AccountMismatch
    "provider_login" -> GarminDeviceMessage.WatchLoginRequired
    "provider_pairing" -> GarminDeviceMessage.PairWatch
    else -> GarminDeviceMessage.Failed
}
