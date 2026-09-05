package com.example.shelfplayer.feature.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.lock.BiometricAvailability
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.core.model.lock.UnlockFailure
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.SignInIntent
import com.example.shelfplayer.domain.usecase.SignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * AUTH-005 — the curtain's state, and the two ways through it.
 *
 * ### Why the passcode is a `CharArray` all the way down
 *
 * So it can be wiped. A `String` cannot be, and would sit in the heap — and in any heap dump taken in
 * between — until the collector chose to move it. The field hands this class an array, this class hands it
 * to the repository, and it is cleared on the way out whatever the outcome was. It is a small mitigation
 * and it is the only one available above the KDF.
 */
@HiltViewModel
class LockViewModel @Inject constructor(
    private val locks: ProfileLockRepository,
    private val profiles: ProfileRepository,
    private val biometrics: BiometricGateway,
    /**
     * AUTH-005 — the route out of a lockout, and the reason the curtain is not a dead end.
     *
     * Signing in to the account again clears its passcode: `SignInUseCase` calls
     * `LockedProfileRecovery.clearIfLocked` on success. That is a feature rather than a bypass — AUTH-003
     * says this lock is not about server authentication, and somebody holding the account password has
     * cleared a strictly higher bar than a six-digit local code.
     *
     * It is done **inline on the curtain** rather than by navigating to the sign-in screen, because the
     * curtain is composed outside the navigation host: there is nowhere to navigate to from here. The first
     * version shipped the sentence without the field, which left ten wrong attempts or an unreadable record
     * with no exit but Android's "clear storage" — losing every profile's downloads and sign-ins.
     */
    private val signIn: SignInUseCase,
) : ViewModel() {

    private val _failure = MutableStateFlow<UnlockFailure?>(null)
    val failure: StateFlow<UnlockFailure?> = _failure.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _recovery = MutableStateFlow<RecoveryState>(RecoveryState.Idle)
    val recovery: StateFlow<RecoveryState> = _recovery.asStateFlow()

    val state: StateFlow<LockUiState> = combine(
        locks.observeLockState(),
        profiles.observeProfiles(),
        profiles.observeServers(),
    ) { lockState, allProfiles, servers ->
        val locked = lockState as? ProfileLockState.Locked
        LockUiState(
            locked = locked,
            isResolved = lockState != ProfileLockState.Resolving,
            account = locked?.let { state -> allProfiles.firstOrNull { it.id == state.profileId } },
            others = allProfiles.filter { it.id != locked?.profileId },
            serverAddress = locked?.let { state ->
                val profile = allProfiles.firstOrNull { it.id == state.profileId }
                servers.firstOrNull { it.id == profile?.serverId }?.baseUrl
            },
            biometrics = biometrics.availability(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = LockUiState(),
    )

    val isBiometricEnabled: StateFlow<Boolean> = locks.observeLockState()
        .map { lockState ->
            val profileId = (lockState as? ProfileLockState.Locked)?.profileId ?: return@map false
            locks.preferences(profileId)?.biometricUnlock == true
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = false,
        )

    /**
     * Uses the repository's lock state rather than [state].value.
     *
     * [state] is `WhileSubscribed`; without a screen collector its value may still be [LockUiState]'s
     * initial value. An unlock action must never lose the profile identity merely because no UI is collecting.
     */
    fun onPasscodeSubmitted(passcode: CharArray) {
        viewModelScope.launch {
            _isChecking.value = true
            try {
                val profileId = lockedProfileId() ?: return@launch
                _failure.value = locks.submitPasscode(profileId, passcode)
            } finally {
                passcode.fill(' ')
                _isChecking.value = false
            }
        }
    }

    /** Called by the curtain after the platform accepted a fingerprint. */
    fun onBiometricAccepted() {
        viewModelScope.launch {
            val profileId = lockedProfileId() ?: return@launch
            locks.acceptBiometricUnlock(profileId)
        }
    }

    fun onFailureShown() {
        _failure.value = null
    }

    /**
     * AUTH-005 — signs in to the locked account again, which clears its passcode.
     *
     * Resolve the locked account and its server from repositories at action time. Those sources continue to
     * represent the current profile context even when the public `WhileSubscribed` UI state is not collected.
     * The password is never stored and is wiped on every exit, including a disappearing profile/server.
     */
    fun onReauthenticate(password: CharArray) {
        viewModelScope.launch {
            try {
                // Do not enter Working until every prerequisite exists. Working disables the recovery field;
                // stranding it on an early return would turn the curtain's escape route into a dead end.
                val profileId = lockedProfileId() ?: return@launch
                val account = profiles.observeProfiles().first().firstOrNull { it.id == profileId }
                    ?: return@launch
                val address = profiles.observeServers().first().firstOrNull { it.id == account.serverId }?.baseUrl
                    ?: return@launch

                _recovery.value = RecoveryState.Working
                val result = signIn(
                    serverUrl = address,
                    username = account.username,
                    password = String(password),
                    intent = SignInIntent.RecoverLockedProfile,
                )
                _recovery.value = when (result) {
                    is AppResult.Success -> RecoveryState.Idle
                    is AppResult.Failure -> RecoveryState.Failed
                }
            } finally {
                password.fill(' ')
            }
        }
    }

    fun onRecoveryDismissed() {
        _recovery.value = RecoveryState.Idle
    }

    private suspend fun lockedProfileId() = (locks.observeLockState().first() as? ProfileLockState.Locked)?.profileId

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class LockUiState(
    val locked: ProfileLockState.Locked? = null,
    val isResolved: Boolean = false,
    val account: Profile? = null,
    val others: List<Profile> = emptyList(),
    val biometrics: BiometricAvailability = BiometricAvailability.UnsupportedAndroidVersion,
    val serverAddress: String? = null,
)

sealed interface RecoveryState {
    data object Idle : RecoveryState
    data object Working : RecoveryState
    data object Failed : RecoveryState
}
