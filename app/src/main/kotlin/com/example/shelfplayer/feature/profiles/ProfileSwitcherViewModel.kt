package com.example.shelfplayer.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.SwitchProfileUseCase
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
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val action = MutableStateFlow(ActionState())

    val uiState: StateFlow<ProfileSwitcherUiState> = combine(
        profileRepository.observeProfiles(),
        profileRepository.observeActiveProfile(),
        action,
    ) { profiles, active, current ->
        ProfileSwitcherUiState(
            profiles = profiles,
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

    fun onProfileSelected(profileId: ProfileId) = run(profileId) { switchProfile(it).discardValue() }

    /**
     * PRODUCT_SPEC AUTH-004 — signing out keeps the profile, its downloads and its local progress.
     *
     * The distinction from [onRemoveProfile] is the whole reason both exist, and the UI has to state it:
     * one ends a session, the other deletes an account's local data.
     */
    fun onSignOut(profileId: ProfileId) = run(profileId) { authRepository.signOut(it) }

    fun onRemoveProfile(profileId: ProfileId) = run(profileId) { authRepository.removeProfile(it) }

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

    private fun <T> AppResult<T>.discardValue(): AppResult<Unit> = when (this) {
        is AppResult.Success -> AppResult.Success(Unit)
        is AppResult.Failure -> this
    }

    private data class ActionState(val inFlight: Boolean = false, val error: AppError? = null)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * PRODUCT_SPEC AUTH-002 — the switcher shows server name, username and role.
 *
 * The server name is not on [Profile]; the list renders `Profile.displayName` and `role`, and the server a
 * profile belongs to is identified by its `serverId`. Showing the server's own display name means joining
 * the two, which the repository does not expose yet — recorded here rather than faked, because a switcher
 * that showed the wrong server would be worse than one that shows none.
 */
data class ProfileSwitcherUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: ProfileId? = null,
    val isBusy: Boolean = false,
    val error: AppError? = null,
    val hasNoProfiles: Boolean = false,
)
