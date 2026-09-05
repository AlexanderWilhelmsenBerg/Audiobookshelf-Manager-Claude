package com.example.shelfplayer.feature.lock

import android.app.Activity
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ProfileRole
import com.example.shelfplayer.core.model.Server
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.lock.BiometricAvailability
import com.example.shelfplayer.core.model.lock.PasscodeRejection
import com.example.shelfplayer.core.model.lock.ProfileLockState
import com.example.shelfplayer.core.model.lock.RelockDelay
import com.example.shelfplayer.core.model.lock.UnlockFailure
import com.example.shelfplayer.core.testing.MainDispatcherRule
import com.example.shelfplayer.domain.lock.LockedProfileRecovery
import com.example.shelfplayer.domain.repository.AuthRepository
import com.example.shelfplayer.domain.repository.CapabilityRepository
import com.example.shelfplayer.domain.repository.ProfileLockPreferences
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.usecase.SignInUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LockRecoveryStateTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `missing recovery prerequisite never strands the curtain in Working`() = runTest {
        val profile = profile()
        val viewModel = LockViewModel(
            locks = LockedProfile(profile.id),
            profiles = ProfilesWithoutServer(profile),
            biometrics = FakeBiometrics,
            signIn = SignInUseCase(
                authRepository = neverCalled(),
                capabilityRepository = neverCalled(),
                lockRecovery = LockedProfileRecovery { },
            ),
        )
        val password = "secret".toCharArray()

        // Deliberately do not collect viewModel.state: action-time identity must come from repositories.
        // The locked profile exists but its server has disappeared, reproducing one of the old ?: returns.
        viewModel.onReauthenticate(password)
        advanceUntilIdle()

        assertEquals(RecoveryState.Idle, viewModel.recovery.value)
        assertTrue(password.all { it == ' ' }, "the password is wiped even when recovery cannot start")
    }

    private class ProfilesWithoutServer(private val profile: Profile) : ProfileRepository {
        override fun observeProfiles(): Flow<List<Profile>> = flowOf(listOf(profile))
        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())
        override fun observeActiveProfile(): Flow<Profile?> = flowOf(profile)
        override suspend fun activeProfileId(): ProfileId = profile.id
        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class LockedProfile(private val profileId: ProfileId) : ProfileLockRepository {
        override fun observeLockState(): Flow<ProfileLockState> = flowOf(ProfileLockState.Locked(profileId))
        override fun observeProtectedProfiles(): Flow<Set<ProfileId>> = flowOf(setOf(profileId))
        override fun validate(passcode: CharArray): PasscodeRejection? = null
        override suspend fun hasPasscode(profileId: ProfileId): Boolean = true
        override suspend fun isLocked(profileId: ProfileId): Boolean = true
        override suspend fun preferences(profileId: ProfileId): ProfileLockPreferences? = null

        override suspend fun setPasscode(
            profileId: ProfileId,
            passcode: CharArray,
            current: CharArray?,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun removePasscode(profileId: ProfileId, current: CharArray): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun submitPasscode(profileId: ProfileId, passcode: CharArray): UnlockFailure? = null
        override suspend fun acceptBiometricUnlock(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun setBiometricUnlockEnabled(profileId: ProfileId, enabled: Boolean): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun setRelockDelay(profileId: ProfileId, delay: RelockDelay): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun lockNow() = Unit
        override suspend fun forget(profileId: ProfileId) = Unit
    }

    private object FakeBiometrics : BiometricGateway {
        override fun availability(): BiometricAvailability = BiometricAvailability.Available
        override fun authenticate(activity: Activity, onSuccess: () -> Unit, onFailed: () -> Unit) = Unit
    }

    private fun profile() = Profile(
        id = ProfileId("profile-a"),
        serverId = ServerId("missing-server"),
        username = "reader",
        displayName = "Reader",
        role = ProfileRole.Listener,
        requiresReauthentication = false,
        lastUsedAt = Instant.EPOCH,
        isFixture = false,
    )

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> neverCalled(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        error("${method.name} should not be called")
    } as T
}
