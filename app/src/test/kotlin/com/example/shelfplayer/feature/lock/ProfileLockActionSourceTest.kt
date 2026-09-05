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
import com.example.shelfplayer.domain.repository.ProfileLockPreferences
import com.example.shelfplayer.domain.repository.ProfileLockRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileLockActionSourceTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `settings action uses repository profile even when public state has no subscriber`() = runTest {
        val profile = profile()
        val profiles = FakeProfiles(profile)
        val locks = RecordingLocks()
        val viewModel = ProfileLockViewModel(locks, profiles, FakeBiometrics)

        // Deliberately never collect viewModel.state. A WhileSubscribed StateFlow therefore remains at
        // its initial null profile, which is the condition the old action-time state.value read mishandled.
        viewModel.onBiometricToggled(true)
        advanceUntilIdle()

        assertEquals(profile.id, locks.biometricProfile)
    }

    private class FakeProfiles(profile: Profile) : ProfileRepository {
        private val active = MutableStateFlow(profile)

        override fun observeProfiles(): Flow<List<Profile>> = flowOf(listOf(active.value))
        override fun observeServers(): Flow<List<Server>> = flowOf(emptyList())
        override fun observeActiveProfile(): Flow<Profile?> = active
        override suspend fun activeProfileId(): ProfileId = active.value.id
        override suspend fun setActiveProfile(profileId: ProfileId): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class RecordingLocks : ProfileLockRepository {
        var biometricProfile: ProfileId? = null
            private set

        override fun observeLockState(): Flow<ProfileLockState> = flowOf(ProfileLockState.Unlocked)
        override fun observeProtectedProfiles(): Flow<Set<ProfileId>> = flowOf(emptySet())
        override fun validate(passcode: CharArray): PasscodeRejection? = null
        override suspend fun hasPasscode(profileId: ProfileId): Boolean = false
        override suspend fun isLocked(profileId: ProfileId): Boolean = false
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

        override suspend fun setBiometricUnlockEnabled(profileId: ProfileId, enabled: Boolean): AppResult<Unit> {
            biometricProfile = profileId
            return AppResult.Success(Unit)
        }

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
        serverId = ServerId("server-a"),
        username = "reader",
        displayName = "Reader",
        role = ProfileRole.Listener,
        requiresReauthentication = false,
        lastUsedAt = Instant.EPOCH,
        isFixture = false,
    )
}
