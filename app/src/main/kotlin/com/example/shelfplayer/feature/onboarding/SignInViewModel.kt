package com.example.shelfplayer.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.usecase.SignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC AUTH-001 / 6.1 — adding a server, in the order the journey prescribes.
 *
 * Two stages rather than two screens. PRODUCT_SPEC 6.1 wants the detected version and the connection
 * security *shown* before the password is typed, and a second route would either lose that information
 * or have to carry it as a navigation argument — a probe result in a back-stack argument is state that
 * can go stale while the user is looking at it.
 *
 * ### The password
 *
 * It is held in this state while the field has focus and dropped the moment sign-in returns, success or
 * failure. It is never written to a `SavedStateHandle`, which is the one thing that would put it in a
 * process-death bundle on disk (PRODUCT_SPEC AUTH-003: passwords are never stored). Losing a half-typed
 * password to process death is the correct trade.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val signIn: SignInUseCase,
) : ViewModel() {

    private val state = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = state.asStateFlow()

    fun onServerUrlChanged(value: String) = state.update {
        // Clearing the error as the user edits is what stops a stale "that is not a server" sitting under
        // a field they have already corrected.
        it.copy(serverUrl = value, error = null)
    }

    fun onUsernameChanged(value: String) = state.update { it.copy(username = value, error = null) }

    fun onPasswordChanged(value: String) = state.update { it.copy(password = value, error = null) }

    /**
     * PRODUCT_SPEC 6.1 steps 3-4 — normalize, check reachability, then report version and security.
     *
     * Reaching the credentials stage requires this to succeed, which is the point: a user who mistyped a
     * host learns it here rather than by sending a password to it.
     */
    fun onServerSubmitted() {
        if (state.value.isBusy) return
        state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.probeServer(state.value.serverUrl)) {
                is AppResult.Failure -> state.update { it.copy(isBusy = false, error = result.error) }
                is AppResult.Success -> state.update {
                    it.copy(
                        isBusy = false,
                        stage = SignInStage.Credentials,
                        candidate = result.value,
                        // The normalized address replaces what was typed, so the user can see what will
                        // actually be contacted before they authenticate to it.
                        serverUrl = result.value.serverUrl,
                    )
                }
            }
        }
    }

    /** Returns to the address field, discarding the probe result and the password with it. */
    fun onBackToServer() = state.update {
        SignInUiState(serverUrl = it.serverUrl)
    }

    fun onCredentialsSubmitted() {
        val current = state.value
        if (current.isBusy || current.candidate == null) return
        state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = signIn(current.candidate.serverUrl, current.username, current.password)
            state.update {
                when (result) {
                    // The password is dropped on both paths. A failed sign-in keeps the username, because
                    // the likely mistake is the password and retyping both is a needless annoyance.
                    is AppResult.Failure -> it.copy(isBusy = false, password = "", error = result.error)
                    is AppResult.Success -> it.copy(
                        isBusy = false,
                        password = "",
                        signedIn = SignedIn(result.value.profile.displayName, result.value.warning),
                    )
                }
            }
        }
    }

    /** Acknowledges the completed sign-in so navigation happens once rather than on every recomposition. */
    fun onSignedInHandled() = state.update { it.copy(signedIn = null) }
}

/**
 * PRODUCT_SPEC 21 — every state the screen can be in, and they are distinguishable.
 *
 * @property candidate the probe result: normalized address, detected version, whether HTTPS was assumed
 *   and whether the connection is cleartext. `null` until the address is accepted.
 * @property error the reason the last action failed. A wrong password, an unreachable host and an invalid
 *   certificate are different `AppError` cases and read differently, which is what PRODUCT_SPEC AUTH-001
 *   means by "a clear certificate error".
 * @property signedIn non-null exactly once, after a successful sign-in. It is a signal to navigate, not a
 *   place to keep a session.
 */
data class SignInUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val stage: SignInStage = SignInStage.Address,
    val candidate: ServerCandidate? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
    val signedIn: SignedIn? = null,
) {
    val canSubmitServer: Boolean get() = serverUrl.isNotBlank() && !isBusy

    /**
     * A blank password is refused before it reaches the network.
     *
     * Not a guess about the server's rules — Audiobookshelf may well reject it too — but a round trip that
     * can only fail is one the user waits through for nothing.
     */
    val canSubmitCredentials: Boolean get() = username.isNotBlank() && password.isNotBlank() && !isBusy
}

enum class SignInStage {
    Address,
    Credentials,
}

/**
 * @property warning what failed *after* the credentials were accepted — the capability handshake or the
 *   first sync. The profile is signed in regardless, so this is shown as a caveat on the way to home
 *   rather than as a failure (PRODUCT_SPEC 6.1).
 */
data class SignedIn(val profileName: String, val warning: AppError?)
