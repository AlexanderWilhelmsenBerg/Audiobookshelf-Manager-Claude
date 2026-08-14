package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.database.dao.LibraryDao
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.ProgressDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.MediaProgressEntity
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.FinishedThreshold
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
    private val libraryDao: LibraryDao,
    private val playbackSettings: PlaybackSettingsRepository,
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
        val opened = gateway.playback.openSession(profileId, bookId)
        if (opened is AppResult.Success) clearFinishedIfRestarting(bookId, opened.value.startAt)
        return opened
    }

    /**
     * PRODUCT_SPEC PLAY-004 — starting a finished book from the top un-finishes it.
     *
     * The owner's report: a book marked finished by the threshold had no way back, and "even just restarting
     * a book that is finished should put it into an unfinished state". This is that rule, and it is here
     * rather than in the player so that every route to playback gets it — the book screen, a media button, a
     * head unit later.
     *
     * **From the top**, not from anywhere. Replaying the last minute of a book you finished is a normal thing
     * to do — the "Listen again" shelf exists for it — and it must not silently mark the book unread. A
     * session that opens within the first minute is somebody starting over; one that opens at four hours is
     * somebody re-listening.
     *
     * Failure is swallowed on purpose: this is a side effect of pressing play, and the one thing that must
     * not happen is a book refusing to start because a flag could not be cleared (product priority 1). The
     * user can still un-finish it explicitly, which is the path that reports its errors.
     */
    private suspend fun clearFinishedIfRestarting(bookId: LibraryItemId, startAt: Duration) {
        if (startAt > RESTART_WITHIN) return
        val profileId = profileRepository.activeProfileId() ?: return
        val profile = profileDao.findProfile(profileId.value) ?: return
        val stored = progressDao.findProgress(profileId.value, EntityKey.of(profile.serverId, bookId.value))
        if (stored?.isFinished != true) return
        logger.info(LogCategory.Playback, "A finished book was restarted, so it is unfinished again")
        setFinished(bookId, isFinished = false, position = startAt)
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
     * ### Whether the book is finished is worked out here
     *
     * ADR-0013's rule has two halves — the listener's setting and the book's library's own
     * `markAsFinishedTimeRemaining` — and this is the only place that holds both. The caller is a media
     * service reacting to a timer; it has no library and no settings, and when it did have a rule the rule
     * was a hard-coded constant that ignored the server entirely.
     *
     * ### Never un-finishing
     *
     * The decision is or-ed with what is already stored. A book the user marked finished, or that the
     * server reported finished, stays finished even though replaying the last minute puts the position
     * back below the threshold. Un-finishing is a thing the user does deliberately; it is not something
     * the last few seconds of audio should be able to do on its own.
     */
    override suspend fun recordPosition(
        bookId: LibraryItemId,
        position: Duration,
        duration: Duration,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val profileId = profileRepository.activeProfileId()
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        val profile = profileDao.findProfile(profileId.value)
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        val bookKey = EntityKey.of(profile.serverId, bookId.value)
        val progressKey = EntityKey.scoped(profileId.value, bookKey)
        val stored = progressDao.findProgress(profileId.value, bookKey)
        val isFinished = thresholdFor(bookKey).isFinished(position = position, duration = duration)
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

    /**
     * PRODUCT_SPEC PLAY-004 — the explicit flag, in both directions.
     *
     * ### Local first, then the server
     *
     * The local row is what every screen reads, so it is written first and unconditionally. The server call
     * follows and its failure is **returned but not undone**: a user who ticked *Finished* on a train has
     * made a decision, and rolling it back because the network was unavailable would be the app overruling
     * them. The row carries `hasUnsyncedChanges`, which is the same mechanism that stops an account sync
     * from reverting a position, so the next successful sync carries the decision up.
     *
     * ### Why the position moves too
     *
     * The route is a progress PATCH and takes both. Marking finished sends the end of the book, so the
     * server and the app agree about a finished book's position instead of leaving it at 40% with a
     * finished flag. Un-marking sends the position the caller supplies, so a book that comes back from
     * finished does not also come back from the beginning.
     */
    override suspend fun setFinished(bookId: LibraryItemId, isFinished: Boolean, position: Duration): AppResult<Unit> =
        withContext(ioDispatcher) {
            val profileId = profileRepository.activeProfileId()
                ?: return@withContext AppResult.Failure(AppError.Authentication())
            val profile = profileDao.findProfile(profileId.value)
                ?: return@withContext AppResult.Failure(AppError.Authentication())
            val bookKey = EntityKey.of(profile.serverId, bookId.value)
            val stored = progressDao.findProgress(profileId.value, bookKey)
            val duration = stored?.durationMillis?.milliseconds ?: Duration.ZERO
            // Finished means the end of the book, when the book's length is known. A book whose duration the
            // app has never seen keeps the position it had rather than being sent to zero.
            val at = if (isFinished && duration > Duration.ZERO) duration else position.coerceAtLeast(Duration.ZERO)
            progressDao.upsertProgress(
                listOf(
                    MediaProgressEntity(
                        progressKey = EntityKey.scoped(profileId.value, bookKey),
                        profileId = profileId.value,
                        bookKey = bookKey,
                        serverId = profile.serverId,
                        positionMillis = at.inWholeMilliseconds,
                        durationMillis = duration.inWholeMilliseconds,
                        isFinished = isFinished,
                        updatedAt = clock.now().toEpochMilli(),
                        hasUnsyncedChanges = true,
                    ),
                ),
            )
            logger.info(
                LogCategory.Playback,
                "A book's finished flag was set by the user",
                LogField.Public("isFinished", isFinished),
            )
            gateway.playback.setFinished(profileId, bookId, isFinished, at)
        }

    /**
     * ADR-0013 — the rule in force for one book: the book's library's, or the listener's where it has none.
     *
     * Read on every journal write rather than cached. The library's value changes when the server's settings
     * change, the listener's changes from the settings screen, and both are one nullable number behind an
     * indexed key — a cache here would buy nothing measurable and would hold a stale rule for exactly as long
     * as nobody noticed.
     *
     * A book whose library predates version 14, or that has no row at all, reads `null` — so the listener's
     * setting applies. That is the honest answer rather than a guess: a library the app has never read
     * settings for has not asked for anything.
     */
    private suspend fun thresholdFor(bookKey: String): FinishedThreshold = FinishedThreshold(
        configured = playbackSettings.observeSettings().first().finishedThreshold,
        library = FinishedThreshold.libraryRule(libraryDao.finishedSecondsFor(bookKey)),
    )

    private companion object {
        /**
         * How far into a book still counts as "starting it again".
         *
         * A minute. Long enough to survive an auto-rewind and a stored position a few seconds off zero,
         * short enough that nobody re-listening to a chapter trips it.
         */
        val RESTART_WITHIN: Duration = 60.seconds
    }
}
