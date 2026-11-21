package com.example.shelfplayer.domain.usecase

import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.domain.library.SeriesDisplayLabel
import com.example.shelfplayer.domain.repository.LibraryRepository
import com.example.shelfplayer.domain.repository.PlaybackOpenOwner
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import com.example.shelfplayer.domain.repository.capturePlaybackOpenOwner
import com.example.shelfplayer.domain.repository.playbackProfileChanged
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
    suspend operator fun invoke(bookId: LibraryItemId): AppResult<PlaybackSession> =
        when (val owner = profiles.capturePlaybackOpenOwner()) {
            is AppResult.Failure -> owner
            is AppResult.Success -> invoke(owner.value, bookId)
        }

    suspend operator fun invoke(owner: PlaybackOpenOwner, bookId: LibraryItemId): AppResult<PlaybackSession> {
        if (!owner.isCurrent(profiles)) return playbackProfileChanged()
        val first = playback.openSession(owner, bookId)
        if (!owner.isCurrent(profiles)) return playbackProfileChanged()
        val result = if (first is AppResult.Failure && first.error is AppError.Authentication) {
            retryAfterRenewal(owner, bookId, first)
        } else {
            first
        }
        val enriched = enrichDisplayMetadata(result)
        return if (owner.isCurrent(profiles)) enriched else playbackProfileChanged()
    }

    private suspend fun retryAfterRenewal(
        owner: PlaybackOpenOwner,
        bookId: LibraryItemId,
        first: AppResult.Failure,
    ): AppResult<PlaybackSession> {
        if (!renewSession(owner.profileId) || !owner.isCurrent(profiles)) return first
        val retried = playback.openSession(owner, bookId)
        if (retried is AppResult.Failure && retried.error is AppError.Authentication && owner.isCurrent(profiles)) {
            requireReauthentication(owner.profileId)
        }
        return retried
    }

    private suspend fun enrichDisplayMetadata(result: AppResult<PlaybackSession>): AppResult<PlaybackSession> {
        // Only a successful session that has no label of its own is worth a cache read. Folded into one
        // expression because the three separate guards took this over detekt's return budget.
        val session = (result as? AppResult.Success)?.value?.takeIf { it.seriesLabel.isNullOrBlank() }
            ?: return result
        val label = cachedSeriesLabel(session) ?: return result
        return AppResult.Success(session.copy(seriesLabel = label))
    }

    /** The primary series and its sequence from the cached book, or `null` when there is nothing to add. */
    private suspend fun cachedSeriesLabel(session: PlaybackSession): String? {
        val cached = library.observeBook(session.profileId, session.bookId).first() ?: return null
        return SeriesDisplayLabel.primary(cached.seriesMemberships)
    }
}
