package com.example.shelfplayer.garmin

import com.example.shelfplayer.core.model.Profile
import com.example.shelfplayer.core.model.lock.ProfileLockState
import javax.inject.Inject

internal class GarminPrivacyPolicy @Inject constructor() {
    fun mayExpose(
        profile: Profile,
        lockState: ProfileLockState,
    ): Boolean = !profile.requiresReauthentication && lockState is ProfileLockState.Unlocked
}
