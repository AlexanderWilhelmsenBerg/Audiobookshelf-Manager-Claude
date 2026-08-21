package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.core.model.lock.RelockDelay
import com.example.shelfplayer.core.model.lock.UnlockFailure
import kotlinx.coroutines.flow.Flow

/**
 * AUTH-005 — the profile passcode lock.
 *
 * ### What this protects, and what it does not
 *
 * AUTH-003 is the constraint that shapes everything here: *"Optional biometric locking protects
 * profile selection, not server authentication semantics."* So this is a presence check against
 * somebody holding an already-unlocked phone. It is not a second factor to the Audiobookshelf server,
 * it does not encrypt the database or the downloaded audio, and it never stops playback that is
 * already running — product priorities 1 and 2 outrank it, and ADR-0023 says so rather than leaving
 * the conflict implicit.
 *
 * ### Why the passcode is a `CharArray`
 *
 * So the caller can wipe it. A `String` cannot be cleared and would sit in the heap, and in any heap
 * dump, until the garbage collector chose to move it. This is a small mitigation, it is the only one
 * available, and it costs one array.
 */
interface ProfileLockRepository {
    /**
     * The active profile's lock state.
     *
     * Starts at [ProfileLockState.Resolving] and never returns to it. That first value is what stops
     * the shelf — with a book title on it — being drawn for a frame before the curtain arrives.
     */
    fun observeLockState(): Flow<ProfileLockState>

    /** Which profiles have a passcode, for the switcher's lock badges and the settings row. */
    fun observeProtectedProfiles(): Flow<Set<ProfileId>>

    /**
     * Whether one profile has a passcode at all.
     *
     * A point read rather than a flow, because the caller that needs it — `SwitchProfileUseCase` — is
     * answering a question about a single gesture and must not wait on a subscription to do it.
     */
    suspend fun hasPasscode(profileId: ProfileId): Boolean

    /** The lock's own preferences for one profile, or `null` when it has no passcode. */
    suspend fun preferences(profileId: ProfileId): ProfileLockPreferences?

    /**
     * Sets or replaces a passcode.
     *
     * [current] must be supplied when one already exists: changing a passcode has to prove presence,
     * or an unlocked phone left on a desk is a passcode somebody else chose. It is ignored when there
     * is no existing passcode, because there is nothing to prove.
     */
    suspend fun setPasscode(profileId: ProfileId, passcode: CharArray, current: CharArray? = null): AppResult<Unit>

    /** Turns the passcode off. Requires the current one for the reason [setPasscode] does. */
    suspend fun removePasscode(profileId: ProfileId, current: CharArray): AppResult<Unit>

    /**
     * Checks a passcode and, on success, unlocks.
     *
     * Returns the failure rather than throwing, because every outcome is something the curtain has to
     * render differently — a wrong value, a backoff with a countdown, an exhausted lock, an unreadable
     * record. `null` means it worked.
     */
    suspend fun submitPasscode(profileId: ProfileId, passcode: CharArray): UnlockFailure?

    /**
     * Unlocks after a successful biometric authentication.
     *
     * **This is app-enforced policy, not cryptography.** The stored verifier is a one-way derivation,
     * so a fingerprint cannot produce it; what this does is trust the platform's answer and grant a
     * ticket. Anyone able to change a boolean inside this process bypasses it — as they equally bypass
     * the passcode. ADR-0023 states that, and the product does not claim otherwise.
     */
    suspend fun acceptBiometricUnlock(profileId: ProfileId): AppResult<Unit>

    suspend fun setBiometricUnlockEnabled(profileId: ProfileId, enabled: Boolean): AppResult<Unit>

    suspend fun setRelockDelay(profileId: ProfileId, delay: RelockDelay): AppResult<Unit>

    /** Revokes the active profile's unlock now, without changing anything stored. */
    suspend fun lockNow()

    /** Forgets a profile's passcode entirely. Called when the profile itself is removed. */
    suspend fun forget(profileId: ProfileId)
}

/** @property relockDelay how long after leaving the app the profile locks again. */
data class ProfileLockPreferences(
    val biometricUnlock: Boolean = false,
    val relockDelay: RelockDelay = RelockDelay.Immediately,
)
