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

/**
 * AUTH-005 / 3.3 — the Profiles settings group's passcode controls.
 *
 * ### Its own ViewModel, for the reason `AppearanceViewModel` has one
 *
 * `SettingsViewModel` is at nine constructor parameters and detekt's limit fails **at** ten, so a tenth
 * would have to be folded into a bundle it has nothing to do with. More to the point, the lock is not the
 * settings screen's state: `MainActivity` reads it to decide whether to draw the app at all. A setting whose
 * primary reader is the shell and whose writer is a tab is better off with its own holder.
 */
@HiltViewModel
class ProfileLockViewModel @Inject constructor(
    private val locks: ProfileLockRepository,
    profiles: ProfileRepository,
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
        // Read here rather than in a second flow: the preferences live in the record, so there is nothing
        // to read at all until one exists, and re-reading on every emission keeps the two in step.
        //
        // `hasPasscode` comes from the record's **existence**, never from whether its preferences could be
        // read, and the two are deliberately different. An unreadable record still means this profile has a
        // passcode; collapsing them would show the switch as *off* for a profile whose record is corrupt,
        // and turning it back on would then take the no-existing-passcode path — letting somebody set a new
        // passcode without proving the old one. The repository refuses that anyway, but a UI that invites a
        // refusal is a UI that has already misled.
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
     * [current] is required when one already exists. That is not ceremony: an unlocked phone left on a desk
     * is otherwise a passcode somebody else chose, and the person who set the original would find
     * themselves locked out of their own account.
     */
    fun onPasscodeSet(passcode: CharArray, current: CharArray?) {
        val profileId = state.value.profileId ?: return
        viewModelScope.launch {
            try {
                // Validated here so the refusal can name its reason. The repository validates again — it
                // must, because it is the boundary — but by the time a rejection has become an
                // `AppError.Validation` the *which* is gone.
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
        val profileId = state.value.profileId ?: return
        viewModelScope.launch {
            try {
                report(locks.removePasscode(profileId, current))
            } finally {
                current.fill(' ')
            }
        }
    }

    fun onBiometricToggled(enabled: Boolean) {
        val profileId = state.value.profileId ?: return
        viewModelScope.launch { report(locks.setBiometricUnlockEnabled(profileId, enabled)) }
    }

    fun onRelockDelayChanged(delay: RelockDelay) {
        val profileId = state.value.profileId ?: return
        viewModelScope.launch { report(locks.setRelockDelay(profileId, delay)) }
    }

    /** AUTH-005 — locks now without changing anything stored. */
    fun onLockNow() {
        viewModelScope.launch { locks.lockNow() }
    }

    fun onMessageShown() {
        _message.value = null
    }

    /**
     * Turns a result into something the row can say.
     *
     * The error's own `summary` is deliberately **not** shown for a validation failure: those summaries live
     * in `:data:auth`, which has no resources and therefore no Norwegian. The code is mapped to a
     * translated string instead, and a failure this maps no case for falls back to the generic one rather
     * than to an English sentence from a data module.
     */
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

/**
 * @property profileId `null` while no profile is active, which disables every control rather than hiding
 *   the section — a hidden row reads as an unbuilt feature, which a device run established the hard way.
 */
data class LockSettingsUiState(
    val profileId: ProfileId? = null,
    val hasPasscode: Boolean = false,
    val biometrics: BiometricAvailability = BiometricAvailability.UnsupportedAndroidVersion,
)

/** The lock's own preferences, meaningful only once a passcode exists. */
data class LockPreferencesUi(
    val biometricUnlock: Boolean = false,
    val relockDelay: RelockDelay = RelockDelay.Immediately,
)

/** What to tell the user after a write. Mapped to a translated string by the section. */
sealed interface LockSettingsMessage {
    data object Saved : LockSettingsMessage

    /**
     * The passcode was refused, and [reason] says which rule it broke.
     *
     * A reason rather than a flat "invalid": telling somebody who typed `111111` that a passcode must be
     * between six and twelve digits is both wrong and useless, and that is exactly what the first version
     * did.
     */
    data class Invalid(val reason: PasscodeRejection) : LockSettingsMessage

    data object WrongCurrent : LockSettingsMessage
    data object Failed : LockSettingsMessage
}
