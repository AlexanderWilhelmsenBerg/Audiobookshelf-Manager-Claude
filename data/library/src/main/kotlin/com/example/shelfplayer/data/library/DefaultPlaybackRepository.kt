package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-004 — the playback repository.
 *
 * Two responsibilities, and they are deliberately asymmetric:
 *
 *  - [openSession] needs the network and fails loudly when it cannot reach it, because the user has
 *    just pressed play and is waiting.
 *  - [recordPosition] never touches the network and never fails for a reason the user should see. It
 *    writes one row. Product priority 2 is "do not lose progress", and the way that is lost is a write
 *    path with something in it that can be unavailable.
 */
@Singleton
class DefaultPlaybackRepository @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val profileDao: ProfileDao,
    private val progressDao: ProgressDao,
    private val gateway: AudiobookshelfGateway,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : PlaybackRepository {

    override suspend fun openSession(bookId: LibraryItemId): AppResult<PlaybackSession> {
        val profileId = profileRepository.activeProfileId()
            ?: return AppResult.Failure(
                AppError.Authentication(
                    summary = "Sign in to a server before playing.",
                    requiresReauthentication = false,
                ),
            )
        return gateway.playback.openSession(profileId, bookId)
    }

    /**
     * PRODUCT_SPEC PLAY-004 — one row, written with the unsynced flag set.
     *
     * ### Why the flag matters more than the number
     *
     * `DefaultLibraryRepository.writeProgress` declines to overwrite a row whose `hasUnsyncedChanges` is
     * true. Setting it here is therefore what stops an account sync — which runs on a timer and carries
     * the server's *older* position — from rewinding a book the user is listening to right now.
     *
     * ### Never un-finishing
     *
     * [isFinished] is or-ed with what is already stored. A book the user marked finished, or that the
     * server reported finished, stays finished even though replaying the last minute puts the position
     * back below the threshold. Un-finishing is a thing the user does deliberately; it is not something
     * the last few seconds of audio should be able to do on its own.
     */
    override suspend fun recordPosition(
        bookId: LibraryItemId,
        position: Duration,
        duration: Duration,
        isFinished: Boolean,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val profileId = profileRepository.activeProfileId()
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        val profile = profileDao.findProfile(profileId.value)
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        val bookKey = EntityKey.of(profile.serverId, bookId.value)
        val progressKey = EntityKey.scoped(profileId.value, bookKey)
        val stored = progressDao.findProgress(profileId.value, bookKey)
        val now = clock.now()
        progressDao.upsertProgress(
            listOf(
                MediaProgressEntity(
                    progressKey = progressKey,
                    profileId = profileId.value,
                    bookKey = bookKey,
                    serverId = profile.serverId,
                    positionMillis = position.inWholeMilliseconds.coerceAtLeast(0),
                    // A duration the player does not know yet — a stream still buffering — must not
                    // overwrite one the library already stored, or the book's progress bar empties.
                    durationMillis = duration.inWholeMilliseconds.takeIf { it > 0 }
                        ?: stored?.durationMillis ?: 0,
                    isFinished = isFinished || stored?.isFinished == true,
                    updatedAt = now.toEpochMilli(),
                    hasUnsyncedChanges = true,
                ),
            ),
        )
        if (stored?.isFinished != true && isFinished) {
            logger.info(
                LogCategory.Playback,
                "A book reached the finished threshold",
                LogField.Millis("remaining", (duration - position).inWholeMilliseconds),
            )
        }
        AppResult.Success(Unit)
    }
}
