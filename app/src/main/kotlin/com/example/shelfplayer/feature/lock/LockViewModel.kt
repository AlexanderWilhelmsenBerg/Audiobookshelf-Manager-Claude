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
    profiles: ProfileRepository,
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
            // `Resolving` is neither locked nor unlocked, and the shell draws nothing until it clears.
            isResolved = lockState != ProfileLockState.Resolving,
            // Named, so the curtain says *which* account is locked rather than showing an anonymous field.
            account = locked?.let { state -> allProfiles.firstOrNull { it.id == state.profileId } },
            // AUTH-005 — the other accounts on this device, so a forgotten passcode and an
            // unreachable server cannot leave the app unusable. Listing them exposes that these accounts
            // exist, which is disclosed rather than fought: the alternative is a brick.
            others = allProfiles.filter { it.id != locked?.profileId },
            // The address the recovery field signs in against, so the user does not retype it.
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

    /**
     * Whether this profile has turned biometric unlock on.
     *
     * Its own flow because the answer lives in the encrypted record rather than in the profile row, so it
     * needs a suspending read that [state]'s `combine` cannot perform inline.
     */
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

    fun onPasscodeSubmitted(passcode: CharArray) {
        val profileId = state.value.locked?.profileId ?: return
        viewModelScope.launch {
            _isChecking.value = true
            try {
                // The KDF is deliberately expensive, so this suspends for a noticeable moment on an older
                // phone. `isChecking` is what stops a second submission racing the first — and the store's
                // own mutex is what makes that safe rather than merely tidy.
                _failure.value = locks.submitPasscode(profileId, passcode)
            } finally {
                passcode.fill(' ')
                _isChecking.value = false
            }
        }
    }

    /** Called by the curtain after the platform accepted a fingerprint. */
    fun onBiometricAccepted() {
        val profileId = state.value.locked?.profileId ?: return
        viewModelScope.launch { locks.acceptBiometricUnlock(profileId) }
    }

    fun onFailureShown() {
        _failure.value = null
    }

    /**
     * AUTH-005 — signs in to the locked account again, which clears its passcode.
     *
     * The password is never stored and is wiped on the way out, like the passcode. A failure is reported
     * rather than thrown: the most likely one is that the server cannot be reached, and that is the honest
     * limit of this route — it is stated on the curtain before anybody needs it.
     */
    fun onReauthenticate(password: CharArray) {
        val current = state.value
        val account = current.account ?: return
        val address = current.serverAddress ?: return
        viewModelScope.launch {
            _recovery.value = RecoveryState.Working
            try {
                val result = signIn(
                    serverUrl = address,
                    username = account.username,
                    password = String(password),
                    // The one call site that clears a passcode, and the one screen that warns first.
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * @property locked `null` while nothing is locked. The curtain is not composed in that case, so this is
 *   only ever read as non-null by the surfaces that draw it.
 * @property account the locked profile, for its display name. `null` for a profile removed underneath the
 *   curtain, which the UI renders as an unnamed account rather than crashing.
 * @property others every other account on this device, so a forgotten passcode is recoverable.
 */
data class LockUiState(
    val locked: ProfileLockState.Locked? = null,
    /**
     * Whether the lock has been read from disk yet.
     *
     * `false` initially, and the shell composes neither the app nor the curtain while it is. Both
     * alternatives are visibly wrong: starting unlocked flashes the shelf — with a book title on it —
     * before the curtain arrives, and starting locked shows a passcode field to the majority of installs
     * that have no passcode at all. The same reasoning `AppUiState.isResolved` applies to the start
     * destination.
     */
    val isResolved: Boolean = false,
    val account: Profile? = null,
    val others: List<Profile> = emptyList(),
    val biometrics: BiometricAvailability = BiometricAvailability.UnsupportedAndroidVersion,
    /** The locked account's server, so the recovery field does not ask the user to retype it. */
    val serverAddress: String? = null,
)

/** Where the "sign in again" route has got to. */
sealed interface RecoveryState {
    data object Idle : RecoveryState
    data object Working : RecoveryState

    /** Wrong password, or — much more likely — the server could not be reached. */
    data object Failed : RecoveryState
}
