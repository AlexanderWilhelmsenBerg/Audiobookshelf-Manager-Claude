package com.example.shelfplayer.data.auth

import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.datastore.security.LockPreferences
import com.example.shelfplayer.core.datastore.security.PasscodeVerdict
import com.example.shelfplayer.core.datastore.security.ProfilePasscodeStore
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.asFailure
import com.example.shelfplayer.core.model.lock.PasscodeRejection
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.core.model.lock.RelockDelay
import com.example.shelfplayer.core.model.lock.UnlockFailure
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.lock.LockedProfileRecovery
import com.example.shelfplayer.domain.lock.ProfileActivationGuard
import com.example.shelfplayer.domain.lock.ProfileLockGate
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.repository.ProfileLockPreferences
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * AUTH-005 — the profile passcode lock, joined to the profile that is active.
 *
 * ### Two interfaces, one implementation
 *
 * [ProfileLockRepository] is the full surface the UI drives. [ProfileLockGuard] is the single question
 * `:playback` may ask. They are the same object because they answer from the same state, and separate
 * types because the media service must not be able to reach the rest — there is no window in
 * `OutputDeviceWatcher` to show a passcode field in.
 *
 * ### Where the "locked" answer comes from
 *
 * Three facts, and all three have to be true for the curtain to go up: there **is** an active profile,
 * it **has** a record on disk, and the gate holds **no live ticket** for it. The last is in memory and
 * dies with the process, which is what makes a cold start locked.
 *
 * ### Fail closed
 *
 * Every failure path here resolves to locked. [isActiveProfileLocked] returns `true` when it cannot
 * tell, and the flow emits `Locked(unreadable = true)` for a record that will not decrypt — a state
 * the curtain renders differently, because telling somebody "wrong passcode" when no passcode can work
 * would send them to keep trying.
 */
@Singleton
class DefaultProfileLockRepository @Inject constructor(
    private val passcodes: ProfilePasscodeStore,
    private val gate: ProfileLockGate,
    private val settings: AppSettingsDataSource,
    private val profileDao: ProfileDao,
    private val logger: Logger,
) : ProfileLockRepository,
    ProfileLockGuard,
    ProfileActivationGuard,
    LockedProfileRecovery {

    override fun observeLockState(): Flow<ProfileLockState> = combine(
        settings.activeProfileId,
        passcodes.observeProtectedProfiles(),
        gate.tickets,
    ) { activeId, protectedKeys, _ ->
        // `gate.tickets` is in the combine only so that granting or revoking one re-emits. Its value is
        // not read here: `isUnlocked` evaluates the relock delay against the clock at read time, and
        // reading the map directly would miss an expiry that no event announced.
        val profileId = activeId ?: return@combine ProfileLockState.Unlocked
        if (keyFor(profileId) !in protectedKeys) return@combine ProfileLockState.Unlocked
        if (gate.isUnlocked(profileId)) return@combine ProfileLockState.Unlocked
        lockedStateFor(profileId)
    }

    /**
     * Which profiles have a passcode, as profile ids rather than as opaque file keys.
     *
     * Resolved by hashing **every profile this device knows** and matching, not by inverting the hash —
     * which cannot be done — and not from a cache warmed by earlier calls. The cache version of this was
     * a real defect: at startup nothing had called `keyFor` yet, so the switcher would have drawn no lock
     * badges at all until something else happened to ask about each profile first.
     */
    override fun observeProtectedProfiles(): Flow<Set<ProfileId>> =
        combine(passcodes.observeProtectedProfiles(), profileDao.observeProfiles()) { keys, profiles ->
            profiles.map { entity -> ProfileId(entity.profileId) }
                .filter { profileId -> keyFor(profileId) in keys }
                .toSet()
        }

    /**
     * The locked state, plus whether the passcode field is worth showing.
     *
     * A record that has exhausted its attempts, or that cannot be decrypted at all, gets a curtain with
     * no passcode field — only re-authentication and the destructive fallback. Offering a field that
     * will refuse every value, including the correct one, is the specific dishonesty this avoids.
     */
    private suspend fun lockedStateFor(profileId: ProfileId): ProfileLockState.Locked {
        val preferences = passcodes.preferences(keyFor(profileId))
        return ProfileLockState.Locked(
            profileId = profileId,
            canUsePasscode = preferences != null,
            unreadable = preferences == null,
        )
    }

    override fun validate(passcode: CharArray): PasscodeRejection? = passcodes.validate(passcode)

    override suspend fun hasPasscode(profileId: ProfileId): Boolean = passcodes.hasPasscode(keyFor(profileId))

    /**
     * AUTH-005 — the switcher's side of [mayActivate].
     *
     * Deliberately the negation of that one call rather than a second reading of the same state. If the
     * switcher decided for itself whether to prompt, the two could disagree: the prompt would not appear
     * for a profile the use case then refused, which is the defect this exists to close.
     */
    override suspend fun isLocked(profileId: ProfileId): Boolean = !mayActivate(profileId)

    /**
     * AUTH-005 — clears the passcode of a profile that was locked, and only such a profile.
     *
     * The condition is the whole point: see [LockedProfileRecovery]. A profile that was already unlocked is
     * somebody re-authenticating after an expired session, and their lock is left exactly as they set it.
     */
    override suspend fun clearIfLocked(profileId: ProfileId) {
        if (mayActivate(profileId)) return
        forget(profileId)
        logger.info(
            LogCategory.Auth,
            "A locked profile was signed in to again, so its passcode was cleared",
            LogField.Identifier("profile", profileId.value),
        )
    }

    /**
     * AUTH-005 — whether [profileId] may become active.
     *
     * Fails closed for the reason the interface states: a refused switch is recoverable by typing the
     * passcode, and the alternative is opening an account because a disk read failed.
     */
    override suspend fun mayActivate(profileId: ProfileId): Boolean = resultOf {
        if (!passcodes.hasPasscode(keyFor(profileId))) return@resultOf true
        gate.isUnlocked(profileId)
    }.let { result ->
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> false
        }
    }

    override suspend fun preferences(profileId: ProfileId): ProfileLockPreferences? =
        passcodes.preferences(keyFor(profileId))?.let { stored ->
            ProfileLockPreferences(
                biometricUnlock = stored.biometricUnlock,
                relockDelay = RelockDelay.ofSeconds(stored.relockDelay.inWholeSeconds),
            )
        }

    override suspend fun setPasscode(profileId: ProfileId, passcode: CharArray, current: CharArray?): AppResult<Unit> {
        passcodes.validate(passcode)?.let { rejection -> return rejection.asError().asFailure() }
        val key = keyFor(profileId)
        if (passcodes.hasPasscode(key)) {
            val proof = current ?: return AppError.Security(summary = REQUIRES_CURRENT).asFailure()
            if (passcodes.verify(key, proof) != PasscodeVerdict.Correct) {
                return AppError.Security(summary = WRONG_CURRENT).asFailure()
            }
        }
        return resultOf(onError = ::storeFailure) {
            passcodes.setPasscode(key, passcode, passcodes.preferences(key))
            // The profile the user is holding stays unlocked: they just proved presence by typing the
            // new passcode. Revoking here would show a curtain to the person who set it, one gesture
            // after they set it.
            gate.grant(profileId, relockDelay = preferences(profileId)?.relockDelay?.delay ?: DEFAULT_DELAY)
            logger.info(
                LogCategory.Auth,
                "A profile passcode was set",
                LogField.Identifier("profile", profileId.value),
            )
        }
    }

    override suspend fun removePasscode(profileId: ProfileId, current: CharArray): AppResult<Unit> {
        val key = keyFor(profileId)
        if (passcodes.verify(key, current) != PasscodeVerdict.Correct) {
            return AppError.Security(summary = WRONG_CURRENT).asFailure()
        }
        return resultOf(onError = ::storeFailure) {
            passcodes.removePasscode(key)
            logger.info(
                LogCategory.Auth,
                "A profile passcode was removed",
                LogField.Identifier("profile", profileId.value),
            )
        }
    }

    override suspend fun submitPasscode(profileId: ProfileId, passcode: CharArray): UnlockFailure? {
        val key = keyFor(profileId)
        val verdict = passcodes.verify(key, passcode)
        // Logged as an outcome name, never as a value and never as an interpolated message (ADR-0004).
        logger.info(
            LogCategory.Auth,
            "A profile unlock was attempted",
            LogField.Identifier("profile", profileId.value),
            LogField.Public("outcome", verdict::class.simpleName.orEmpty()),
        )
        return when (verdict) {
            PasscodeVerdict.Correct -> {
                gate.grant(profileId, relockDelay = preferences(profileId)?.relockDelay?.delay ?: DEFAULT_DELAY)
                null
            }

            is PasscodeVerdict.Wrong -> UnlockFailure.Wrong(verdict.remainingBeforeBackoff)
            is PasscodeVerdict.BackingOff -> UnlockFailure.BackingOff(verdict.remainingMillis.milliseconds)
            PasscodeVerdict.Exhausted -> UnlockFailure.Exhausted
            PasscodeVerdict.Unreadable -> UnlockFailure.Unreadable
        }
    }

    override suspend fun acceptBiometricUnlock(profileId: ProfileId): AppResult<Unit> {
        val preferences = passcodes.preferences(keyFor(profileId))
            ?: return AppError.Security(summary = NO_PASSCODE).asFailure()
        if (!preferences.biometricUnlock) {
            return AppError.Security(summary = BIOMETRIC_OFF).asFailure()
        }
        gate.grant(profileId, relockDelay = preferences.relockDelay)
        logger.info(
            LogCategory.Auth,
            "A profile was unlocked with biometrics",
            LogField.Identifier("profile", profileId.value),
        )
        return AppResult.Success(Unit)
    }

    override suspend fun setBiometricUnlockEnabled(profileId: ProfileId, enabled: Boolean): AppResult<Unit> =
        updatePreferences(profileId) { it.copy(biometricUnlock = enabled) }

    override suspend fun setRelockDelay(profileId: ProfileId, delay: RelockDelay): AppResult<Unit> =
        updatePreferences(profileId) { it.copy(relockDelay = delay.delay) }

    private suspend fun updatePreferences(
        profileId: ProfileId,
        transform: (LockPreferences) -> LockPreferences,
    ): AppResult<Unit> = resultOf(onError = ::storeFailure) {
        val key = keyFor(profileId)
        val existing = passcodes.preferences(key) ?: return@resultOf
        passcodes.updatePreferences(key, transform(existing))
    }

    override suspend fun lockNow() {
        val profileId = settings.activeProfileId.first() ?: return
        gate.revoke(profileId)
    }

    override suspend fun forget(profileId: ProfileId) {
        passcodes.removePasscode(keyFor(profileId))
        gate.revoke(profileId)
    }

    /**
     * PRODUCT_SPEC ROUTE-002 — the one question `:playback` asks, answered the safe way round.
     *
     * `true` for every uncertainty: no way to read the store, an exception on the way, a profile whose
     * record will not decrypt. The cost of a wrong `true` is that a book does not start by itself,
     * which ROUTE-002 explicitly calls best-effort. The cost of a wrong `false` is somebody's book
     * playing out loud from a locked account.
     */
    override suspend fun isActiveProfileLocked(): Boolean = resultOf {
        val profileId = settings.activeProfileId.first() ?: return@resultOf false
        if (!passcodes.hasPasscode(keyFor(profileId))) return@resultOf false
        !gate.isUnlocked(profileId)
    }.let { result ->
        when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> true
        }
    }

    /**
     * The file-name key for a profile.
     *
     * Hashed for the reason `SessionTokenStore` hashes its own: a file name reaches `adb shell ls` and
     * any I/O error message, and a server-derived identifier does not belong in either. The mapping is
     * one-way, so [observeProtectedProfiles] matches by hashing the profiles that exist rather than by
     * trying to invert it.
     */
    private fun keyFor(profileId: ProfileId): String = keyCache.computeIfAbsent(profileId) {
        MessageDigest.getInstance(DIGEST)
            .digest(it.value.toByteArray(Charsets.UTF_8))
            .take(KEY_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Memoised because hashing is called per profile on every emission of the flow above, and a
     * `ConcurrentHashMap` because those emissions and the media service's `isActiveProfileLocked` run on
     * different threads.
     */
    private val keyCache = java.util.concurrent.ConcurrentHashMap<ProfileId, String>()

    private fun PasscodeRejection.asError(): AppError = AppError.Validation(
        summary = when (this) {
            PasscodeRejection.Length -> LENGTH
            PasscodeRejection.NotDigits -> NOT_DIGITS
            PasscodeRejection.TooSimple -> TOO_SIMPLE
        },
    )

    private fun storeFailure(throwable: Throwable): AppError {
        // Logged rather than discarded, and the message names nothing about the passcode itself.
        logger.warn(LogCategory.Auth, "A profile lock record could not be written", throwable = throwable)
        return AppError.Storage(summary = "The passcode could not be saved on this device.")
    }

    private companion object {
        const val DIGEST = "SHA-256"
        const val KEY_BYTES = 16
        val DEFAULT_DELAY: Duration = Duration.ZERO

        const val REQUIRES_CURRENT = "Enter the current passcode to change it."
        const val WRONG_CURRENT = "That is not the current passcode."
        const val NO_PASSCODE = "This account has no passcode."
        const val BIOMETRIC_OFF = "Fingerprint unlock is not turned on for this account."
        const val LENGTH = "A passcode is between 6 and 12 digits."
        const val NOT_DIGITS = "A passcode is digits only."
        const val TOO_SIMPLE = "Choose a passcode that is not a repeated or sequential run of digits."
    }
}
