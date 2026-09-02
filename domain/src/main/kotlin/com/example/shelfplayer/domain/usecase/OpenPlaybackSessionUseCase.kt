package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import javax.inject.Inject

/**
 * Opens one playback session, renewing an expired active profile exactly once when the server returns 401.
 *
 * Playback is reached from the phone, a headset and Android Auto. Keeping the policy here gives all three
 * the same AUTH-004 behaviour instead of teaching each controller how refresh tokens work. The active
 * profile is captured before the first request and checked again before both renewal and retry so a profile
 * switch can never renew or replay a request on behalf of the account that replaced it.
 */
class OpenPlaybackSessionUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val playback: PlaybackRepository,
    private val renewSession: RenewProfileSessionUseCase,
    private val requireReauthentication: RequireProfileReauthenticationUseCase,
) {
    suspend operator fun invoke(bookId: LibraryItemId): AppResult<PlaybackSession> {
        val profileId = profiles.activeProfileId()
        val first = playback.openSession(bookId)
        val renewed =
            profileId != null &&
                first is AppResult.Failure &&
                first.error is AppError.Authentication &&
                profiles.activeProfileId() == profileId &&
                renewSession(profileId)
        val canRetry = renewed && profiles.activeProfileId() == profileId
        if (!canRetry) return first
        val retried = playback.openSession(bookId)
        if (retried is AppResult.Failure && retried.error is AppError.Authentication) {
            requireReauthentication(profileId)
        }
        return retried
    }
}
