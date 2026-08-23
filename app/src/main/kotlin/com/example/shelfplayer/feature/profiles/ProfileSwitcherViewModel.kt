package com.example.shelfplayer.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.auth.SessionStatus
import com.example.shelfplayer.core.model.lock.UnlockFailure
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.RemoveProfileUseCase
import com.example.shelfplayer.domain.usecase.RestoreProfilePlaybackUseCase
import com.example.shelfplayer.domain.usecase.SwitchProfileUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PRODUCT_SPEC AUTH-002 / 6.5 — the profile switcher.
 *
 * Every action here goes through a use case or a repository; none of them decides policy. Which order a
 * switch happens in, and what a failed credential load means, are recorded on [SwitchProfileUseCase]
 * rather than here, so that a second entry point cannot answer them differently.
 */
@HiltViewModel
class ProfileSwitcherViewModel @Inject constructor(
    profileRepository: ProfileRepository,
    private val switchProfile: SwitchProfileUseCase,
    /**
     * PRODUCT_SPEC 6.5 step 6 — the incoming account's last book, restored paused.
     *
     * Called here rather than inside [SwitchProfileUseCase] because it must not be awaited: it opens a
     * session, and AUTH-002 gives the switch 500 ms. Both switch paths below call it, which is every path
     * that exists — see the note on [RestoreProfilePlaybackUseCase] about a future third one.
     */
    private val restorePlayback: RestoreProfilePlaybackUseCase,
    private val authRepository: AuthRepository,
    private val removeProfile: RemoveProfileUseCase,
    /**
     * AUTH-005 — which accounts carry a passcode, and the field that opens one.
     *
     * Two jobs, both forced by the lock. The card says a passcode is needed before it is tapped, because
     * `SwitchProfileUseCase` refuses a locked profile and a control that fails when used is worse than one
     * that explains itself first. And this screen is the **only** place a non-active profile's passcode can
     * be typed: the curtain draws for the active profile alone, so without the prompt below a locked
     * profile that is not the current one cannot be opened at all — the refusal names a field that exists
     * nowhere.
     */
    private val locks: ProfileLockRepository,
) : ViewModel() {

    private val action = MutableStateFlow(ActionState())

    /**
     * The passcode prompt, or `null` when none is open.
     *
     * Kept out of [uiState]'s `combine` on purpose: it is driven by a tap rather than by any of those
     * flows, and folding it in would mean a repository emission could dismiss a half-typed passcode.
     */
    private val unlock = MutableStateFlow<UnlockPrompt?>(null)

    val unlockPrompt: StateFlow<UnlockPrompt?> = unlock.asStateFlow()

    val uiState: StateFlow<ProfileSwitcherUiState> = combine(
        profileRepository.observeProfiles(),
        profileRepository.observeServers(),
        profileRepository.observeActiveProfile(),
        locks.observeProtectedProfiles(),
        action,
    ) { profiles, servers, active, protectedProfiles, current ->
        val byId = servers.associateBy(Server::id)
        ProfileSwitcherUiState(
            // Joined here rather than in the screen: a composable reaching into a second list to find its
            // row's server is how a list ends up rendering the wrong one during a recomposition. The
            // passcode flag joins for the same reason.
            profiles = profiles.map { profile ->
                ProfileRow(profile, byId[profile.serverId], hasPasscode = profile.id in protectedProfiles)
            },
            activeProfileId = active?.id,
            isBusy = current.inFlight,
            error = current.error,
            // Removing the last profile leaves nothing to show and nothing to switch to, which is the
            // signal the navigation graph uses to return to onboarding.
            hasNoProfiles = profiles.isEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ProfileSwitcherUiState(),
    )

    /**
     * AUTH-005 — a locked profile opens a passcode prompt instead of failing.
     *
     * The lock is asked *before* the switch rather than after it, because `SwitchProfileUseCase` reports
     * its refusal as an ordinary [AppError.Security] and keying the prompt off an error type would open it
     * for any other security failure in the switch path too.
     *
     * Asked per tap rather than read from [ProfileRow.hasPasscode]: a profile that carries a passcode may
     * already hold a live unlock ticket, and prompting somebody who is already unlocked is friction with
     * nothing behind it.
     */
    fun onProfileSelected(profileId: ProfileId) {
        if (action.value.inFlight) return
        action.update { it.copy(inFlight = true, error = null) }
        viewModelScope.launch {
            if (locks.isLocked(profileId)) {
                action.update { it.copy(inFlight = false) }
                unlock.value = UnlockPrompt(profileId = profileId)
                return@launch
            }
            val result = switchProfile(profileId)
            action.update { it.copy(inFlight = false, error = (result as? AppResult.Failure)?.error) }
            restoreAfter(profileId, result)
        }
    }

    /**
     * Checks the typed passcode and, if it is right, performs the switch that was refused.
     *
     * The array is wiped whatever happens. It is the caller's copy — `String` would leave the digits in the
     * heap until a garbage collection nobody controls, which is why the whole lock surface takes
     * `CharArray`.
     */
    fun onUnlockSubmitted(passcode: CharArray) {
        val target = unlock.value?.takeIf { !it.isChecking } ?: return
        unlock.value = target.copy(isChecking = true, failure = null)
        viewModelScope.launch {
            val failure = try {
                locks.submitPasscode(target.profileId, passcode)
            } finally {
                passcode.fill('\u0000')
            }
            if (failure != null) {
                unlock.value = target.copy(isChecking = false, failure = failure)
                return@launch
            }
            unlock.value = null
            val result = switchProfile(target.profileId)
            action.update { it.copy(error = (result as? AppResult.Failure)?.error) }
            restoreAfter(target.profileId, result)
        }
    }

    /**
     * PRODUCT_SPEC 6.5 step 6 — arms the incoming account's last book, once the switch has actually happened.
     *
     * Only for a switch that produced a **live session**. A profile that restored into
     * `ReauthenticationRequired` has no token to open a session with, so arming it would spend a network
     * round trip to be told what is already known — the same reasoning `SwitchProfileUseCase` applies before
     * refreshing permissions.
     *
     * Not awaited, and its failures are not shown. The switch has already succeeded and been reported; this
     * is the courtesy afterwards, and a listener does not need an error about a book they did not ask for
     * (product priority 1).
     */
    private fun restoreAfter(profileId: ProfileId, result: AppResult<SessionStatus>) {
        if (result !is AppResult.Success || result.value != SessionStatus.Active) return
        viewModelScope.launch { restorePlayback(profileId) }
    }

    fun onUnlockDismissed() {
        if (unlock.value?.isChecking == true) return
        unlock.value = null
    }

    /**
     * PRODUCT_SPEC AUTH-004 — signing out keeps the profile, its downloads and its local progress.
     *
     * The distinction from [onRemoveProfile] is the whole reason both exist, and the UI has to state it:
     * one ends a session, the other deletes an account's local data.
     */
    fun onSignOut(profileId: ProfileId) = run(profileId) { authRepository.signOut(it) }

    fun onRemoveProfile(profileId: ProfileId) = run(profileId) { removeProfile(it) }

    fun onErrorDismissed() = action.update { it.copy(error = null) }

    /**
     * One action at a time.
     *
     * Two switches racing would leave the selection and the loaded credential describing different
     * profiles, which is the state PRODUCT_SPEC 6.5's "atomically" rules out.
     */
    private fun run(profileId: ProfileId, block: suspend (ProfileId) -> AppResult<Unit>) {
        if (action.value.inFlight) return
        action.update { it.copy(inFlight = true, error = null) }
        viewModelScope.launch {
            val result = block(profileId)
            action.update {
                it.copy(inFlight = false, error = (result as? AppResult.Failure)?.error)
            }
        }
    }

    private data class ActionState(val inFlight: Boolean = false, val error: AppError? = null)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * PRODUCT_SPEC AUTH-002 — one saved account, with the server it belongs to.
 *
 * [server] is nullable because the join can miss: a profile row outlives its server row for the moment
 * between a cascade delete and the next emission. A card with no server line is right for that instant; a
 * card showing *some other* server would not be.
 */
data class ProfileRow(val profile: Profile, val server: Server?, val hasPasscode: Boolean = false) {
    /**
     * The address a profile that has to sign in again should be sent back to.
     *
     * `null` when the server row is missing, in which case the user types it — the alternative is sending
     * a password to a guessed host.
     */
    val serverUrl: String? get() = server?.baseUrl
}

/**
 * AUTH-005 — an open passcode prompt for a profile the switcher refused to open.
 *
 * Carries no passcode of its own. The digits live in the field's own state and reach the view model as a
 * `CharArray` that is wiped on arrival, so nothing that survives a recomposition ever holds them.
 *
 * @property isChecking a submission is in flight. Both the field and the dismiss are refused while it is
 *   true, because the derivation takes a noticeable moment on an older phone and a second submission would
 *   spend one of the four free attempts on the same passcode.
 */
data class UnlockPrompt(val profileId: ProfileId, val isChecking: Boolean = false, val failure: UnlockFailure? = null)

/** PRODUCT_SPEC AUTH-002 — the switcher shows server, username and role. */
data class ProfileSwitcherUiState(
    val profiles: List<ProfileRow> = emptyList(),
    val activeProfileId: ProfileId? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
    val hasNoProfiles: Boolean = false,
)
