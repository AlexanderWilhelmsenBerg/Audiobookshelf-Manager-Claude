package com.example.shelfplayer.feature.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.lock.BiometricAvailability
import com.example.shelfplayer.core.model.lock.PasscodeRejection
import com.example.shelfplayer.core.model.lock.RelockDelay
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** AUTH-005 / 3.3 — the Profiles settings group's passcode controls. */
@HiltViewModel
class ProfileLockViewModel @Inject constructor(
    private val locks: ProfileLockRepository,
    private val profiles: ProfileRepository,
    biometrics: BiometricGateway,
) : ViewModel() {

    private val _message = MutableStateFlow<LockSettingsMessage?>(null)
    val message: StateFlow<LockSettingsMessage?> = _message.asStateFlow()

    private val _preferences = MutableStateFlow(LockPreferencesUi())
    val preferences: StateFlow<LockPreferencesUi> = _preferences.asStateFlow()

    val state: StateFlow<LockSettingsUiState> = combine(
        profiles.observeActiveProfile(),
        locks.observeProtectedProfiles(),
    ) { profile, protectedProfiles ->
        val protectedId = profile?.id?.takeIf { it in protectedProfiles }
        _preferences.value = protectedId
            ?.let { id -> locks.preferences(id) }
            ?.let { stored -> LockPreferencesUi(stored.biometricUnlock, stored.relockDelay) }
            ?: LockPreferencesUi()
        LockSettingsUiState(
            profileId = profile?.id,
            hasPasscode = protectedId != null,
            biometrics = biometrics.availability(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LockSettingsUiState(),
    )

    /**
     * Turns the passcode on, or changes it.
     *
     * The active profile is resolved from [profiles] inside the action rather than from [state].value. The
     * public state is `WhileSubscribed`, so it can still contain its initial null profile when no screen is
     * collecting even though the repository already has an active profile.
     */
    fun onPasscodeSet(passcode: CharArray, current: CharArray?) {
        viewModelScope.launch {
            try {
                val profileId = profiles.activeProfileId() ?: return@launch
                val rejection = locks.validate(passcode)
                if (rejection != null) {
                    _message.value = LockSettingsMessage.Invalid(rejection)
                    return@launch
                }
                report(locks.setPasscode(profileId, passcode, current))
            } finally {
                passcode.fill(' ')
                current?.fill(' ')
            }
        }
    }

    fun onPasscodeRemoved(current: CharArray) {
        viewModelScope.launch {
            try {
                val profileId = profiles.activeProfileId() ?: return@launch
                report(locks.removePasscode(profileId, current))
            } finally {
                current.fill(' ')
            }
        }
    }

    fun onBiometricToggled(enabled: Boolean) {
        viewModelScope.launch {
            val profileId = profiles.activeProfileId() ?: return@launch
            report(locks.setBiometricUnlockEnabled(profileId, enabled))
        }
    }

    fun onRelockDelayChanged(delay: RelockDelay) {
        viewModelScope.launch {
            val profileId = profiles.activeProfileId() ?: return@launch
            report(locks.setRelockDelay(profileId, delay))
        }
    }

    /** AUTH-005 — locks now without changing anything stored. */
    fun onLockNow() {
        viewModelScope.launch { locks.lockNow() }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private fun report(result: AppResult<Unit>) {
        _message.value = when {
            result is AppResult.Success -> LockSettingsMessage.Saved
            result is AppResult.Failure && result.error is AppError.Validation ->
                LockSettingsMessage.Invalid(PasscodeRejection.Length)
            result is AppResult.Failure && result.error is AppError.Security -> LockSettingsMessage.WrongCurrent
            else -> LockSettingsMessage.Failed
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class LockSettingsUiState(
    val profileId: ProfileId? = null,
    val hasPasscode: Boolean = false,
    val biometrics: BiometricAvailability = BiometricAvailability.UnsupportedAndroidVersion,
)

data class LockPreferencesUi(
    val biometricUnlock: Boolean = false,
    val relockDelay: RelockDelay = RelockDelay.Immediately,
)

sealed interface LockSettingsMessage {
    data object Saved : LockSettingsMessage
    data class Invalid(val reason: PasscodeRejection) : LockSettingsMessage
    data object WrongCurrent : LockSettingsMessage
    data object Failed : LockSettingsMessage
}
