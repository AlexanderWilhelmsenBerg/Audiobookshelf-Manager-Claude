package com.example.shelfplayer.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerCandidate
import com.example.shelfplayer.core.model.ServerVersion
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.SignInIntent
import com.example.shelfplayer.domain.usecase.SignInUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    savedStateHandle: SavedStateHandle,
    profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val signIn: SignInUseCase,
) : ViewModel() {

    /**
     * PRODUCT_SPEC AUTH-004 — a profile that has to sign in again arrives with what it already knows.
     *
     * The switcher passes the server address and the username it has on file, so reauthenticating is a
     * password and a tap rather than retyping a host on a phone keyboard.
     *
     * The **password** is deliberately not among them, and never could be: nothing stores one
     * (PRODUCT_SPEC AUTH-003). The address still goes through the probe — the stage does not skip — so
     * PRODUCT_SPEC 6.1's guarantee that the version and the encryption line are seen before a password is
     * typed survives the shortcut.
     */
    private val state = MutableStateFlow(
        SignInUiState(
            serverUrl = savedStateHandle.get<String>(ARG_SERVER_URL).orEmpty(),
            username = savedStateHandle.get<String>(ARG_USERNAME).orEmpty(),
        ),
    )

    /**
     * PRODUCT_SPEC AUTH-001 / 6.1 — the servers this device already knows.
     *
     * Typing a host on a phone keyboard is the worst part of adding a second account, and the app already
     * has the address, the version it detected and whether the connection was encrypted. Offering them is
     * a shortcut into the *same* flow, not around it: picking one fills the field, and the probe still runs
     * before any password field appears, so what is shown was confirmed just now rather than remembered
     * from last time.
     */
    init {
        // PRODUCT_SPEC AUTH-004 — a reauthentication should ask for a password and nothing else.
        //
        // The probe is not skipped, it is simply run for the user: PRODUCT_SPEC 6.1 wants the version and
        // the encryption line seen before a password is typed, and the credentials stage shows both. What
        // is removed is the pointless tap on an address the app supplied itself.
        if (state.value.serverUrl.isNotBlank()) onServerSubmitted()
    }

    val uiState: StateFlow<SignInUiState> = combine(
        state,
        profileRepository.observeServers(),
    ) { current, servers ->
        current.copy(knownServers = servers.map(::KnownServer))
    }.stateIn(
        scope = viewModelScope,
        // `Eagerly`, not `WhileSubscribed`, and this is the one screen where that is right. The typed
        // address, the confirmed probe and the half-entered username live in `state`; a stop-and-restart
        // would replay `initialValue` — an empty address stage — as the screen came back, in front of a
        // user who had already got past it. Nothing here is expensive to keep: one Room query.
        started = SharingStarted.Eagerly,
        initialValue = SignInUiState(),
    )

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
                is AppResult.Success -> state.update { current ->
                    // PRODUCT_SPEC 24.4 / ADR-0024 — the version floor, enforced before a password is
                    // typed.
                    //
                    // This is the only honest place for it. Below 2.26.0 the server issues no refresh
                    // token, so `POST /login` would succeed, everything would appear to work, and
                    // AUTH-004's silent renewal would fail hours later on a device — reading to the user
                    // as a random sign-out with no cause they could name. A capability probe cannot catch
                    // that, because the absence only shows up at the first renewal.
                    if (!ServerVersion.isSupported(result.value.probe.serverVersion)) {
                        current.copy(
                            isBusy = false,
                            error = AppError.ApiCompatibility(
                                summary = "This server is too old for BookWave. It needs Audiobookshelf " +
                                    "${ServerVersion.Minimum} or newer.",
                                missingField = "serverVersion",
                            ),
                        )
                    } else {
                        current.copy(
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
    }

    /**
     * Returns to the address field, discarding the probe result, the password and the username with it.
     *
     * The username goes too, deliberately: the reason to come back here is to point at a *different*
     * server, and an account name carried across servers is more likely wrong than right. A prefilled
     * username from a reauthentication survives only as long as the server it belongs to.
     */
    fun onBackToServer() = state.update {
        SignInUiState(serverUrl = it.serverUrl)
    }

    fun onCredentialsSubmitted() {
        val current = state.value
        if (current.isBusy || current.candidate == null) return
        state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = signIn(
                serverUrl = current.candidate.serverUrl,
                username = current.username,
                password = current.password,
                // ADR-0026 decision 2 — this screen must never switch off a lock the user set. It
                // does not mention the passcode, so it has no business clearing one.
                intent = SignInIntent.Reauthenticate,
            )
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

    /**
     * Fills the address field from a known server and probes it.
     *
     * The probe is not skipped. What the app remembers about a server is what it was *last* time; the
     * version can have changed and the certificate can have expired, and PRODUCT_SPEC 6.1 wants the user
     * to see the current answer before typing a password.
     */
    fun onKnownServerSelected(serverUrl: String) {
        if (state.value.isBusy) return
        state.update { it.copy(serverUrl = serverUrl, error = null) }
        onServerSubmitted()
    }

    companion object {
        const val ARG_SERVER_URL = "serverUrl"
        const val ARG_USERNAME = "username"
    }
}

/**
 * A server this device has connected to before, as the address stage offers it.
 *
 * [isEncrypted] is derived from the stored address rather than remembered separately: the scheme *is* the
 * answer, and a second copy of it could disagree with the address next to it.
 */
data class KnownServer(val url: String, val detectedVersion: String?) {
    constructor(server: Server) : this(url = server.baseUrl, detectedVersion = server.detectedVersion)

    val isEncrypted: Boolean get() = url.startsWith("https://", ignoreCase = true)
}

/**
 * PRODUCT_SPEC 21 — every state the screen can be in, and they are distinguishable.
 *
 * @property candidate the probe result: normalized address, detected version, whether HTTPS was assumed
 *   and whether the connection is cleartext. `null` until the address is accepted.
 * @property knownServers servers this device has connected to before, offered under the address field.
 *   Picking one fills the field and re-probes; nothing about it is trusted without that.
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
    val knownServers: List<KnownServer> = emptyList(),
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
