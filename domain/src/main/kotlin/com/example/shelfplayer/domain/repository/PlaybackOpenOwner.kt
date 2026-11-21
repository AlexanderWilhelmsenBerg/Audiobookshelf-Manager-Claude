package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.ProfileId

/** The active account and mutation generation that authorized one playback request. */
data class PlaybackOpenOwner(val profileId: ProfileId, val generation: Long) {
    suspend fun isCurrent(profiles: ProfileRepository): Boolean =
        profiles.activeProfileId() == profileId && profiles.stableActiveProfileGeneration() == generation
}

suspend fun ProfileRepository.capturePlaybackOpenOwner(): AppResult<PlaybackOpenOwner> {
    val generation = stableActiveProfileGeneration() ?: return playbackProfileChanged()
    val profileId = activeProfileId()
    if (stableActiveProfileGeneration() != generation) return playbackProfileChanged()
    return if (profileId == null) {
        AppResult.Failure(
            AppError.Authentication(
                summary = "Sign in to a server before playing.",
                requiresReauthentication = false,
            ),
        )
    } else {
        AppResult.Success(PlaybackOpenOwner(profileId, generation))
    }
}

fun playbackProfileChanged(): AppResult.Failure =
    AppResult.Failure(AppError.Conflict("The active profile changed before playback could start."))
