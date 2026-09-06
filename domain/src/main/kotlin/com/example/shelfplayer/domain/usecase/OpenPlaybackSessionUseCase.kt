package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Opens one playback session, renewing an expired active profile exactly once when the server returns 401.
 *
 * Playback is reached from the phone, a headset and Android Auto. Keeping the policy here gives all three
 * the same AUTH-004 behaviour instead of teaching each controller how refresh tokens work. The active
 * profile is captured before the first request and checked again before both renewal and retry so a profile
 * switch can never renew or replay a request on behalf of the account that replaced it.
 *
 * A successful session is also enriched with series/sequence from that session owner's cached library row.
 * Audiobookshelf's `/play` response does not carry series membership, while Android Auto's live player only
 * has the Media3 item to render. Reading Room here gives every opening surface the same truthful display
 * context without adding another network request or asking whichever profile happens to be active later.
 */
class OpenPlaybackSessionUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val playback: PlaybackRepository,
    private val library: LibraryRepository,
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
        if (!canRetry) return enrichDisplayMetadata(first)
        val retried = playback.openSession(bookId)
        if (retried is AppResult.Failure && retried.error is AppError.Authentication) {
            requireReauthentication(profileId)
        }
        return enrichDisplayMetadata(retried)
    }

    private suspend fun enrichDisplayMetadata(result: AppResult<PlaybackSession>): AppResult<PlaybackSession> {
        if (result !is AppResult.Success) return result
        val session = result.value
        if (!session.seriesLabel.isNullOrBlank()) return result
        val cached = library.observeBook(session.profileId, session.bookId).first() ?: return result
        val membership = cached.seriesMemberships
            .firstOrNull(SeriesMembership::isPrimary)
            ?: cached.seriesMemberships.firstOrNull()
        val label = membership?.let { series ->
            val sequence = series.sequence.raw.takeIf(String::isNotBlank)
            if (sequence == null) series.series.name else "${series.series.name} #$sequence"
        } ?: return result
        return AppResult.Success(session.copy(seriesLabel = label))
    }
}
