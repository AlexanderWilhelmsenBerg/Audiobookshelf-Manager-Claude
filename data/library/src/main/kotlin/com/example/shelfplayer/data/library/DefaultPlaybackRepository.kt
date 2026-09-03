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
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.core.model.playback.FinishedThreshold
import com.example.shelfplayer.core.model.playback.ServerProgress
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue
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
    private val gateway: AudiobookshelfGateway,
    private val downloads: DownloadSupport,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : PlaybackRepository {

    /**
     * PRODUCT_SPEC PLAY-001 / DL-001 — a session, from the server where it can be had and from the device
     * where it cannot.
     *
     * ### The server is still asked first, even for a downloaded book
     *
     * A server session is what makes listening time land in Audiobookshelf's own statistics and what lets the
     * web interface show the book as being listened to right now. A downloaded book that skipped the call
     * would play perfectly and quietly stop appearing on the server, which is a regression a user would only
     * notice weeks later.
     *
     * What the download changes is where the *bytes* come from: [OfflineSessionBuilder.localise] rewrites the
     * server's track URLs to the committed files. A downloaded book therefore never streams — which is the
     * whole point — while still being a real session.
     *
     * ### And when the server cannot be reached, the download is the session
     *
     * This is DL-001's exit criterion: a book downloaded on Wi-Fi plays on a plane. The local session carries
     * a blank id, which routes its listening through PLAY-005's outbox to be uploaded later
     * ([OfflineSessionBuilder]).
     *
     * The fallback is only taken when the *transport* failed. An authentication failure falls through as
     * itself: a user whose session expired needs to be told, and silently playing on would hide it until the
     * next sync lost their progress.
     */
    override suspend fun openSession(bookId: LibraryItemId): AppResult<PlaybackSession> {
        val profileId = profileRepository.activeProfileId()
            ?: return AppResult.Failure(
                AppError.Authentication(
                    summary = "Sign in to a server before playing.",
                    requiresReauthentication = false,
                ),
            )
        val serverId = profileDao.findProfile(profileId.value)?.serverId?.let(::ServerId)
        val manifest = serverId?.let { downloads.sessions.manifestFor(it, bookId) }

        val opened = gateway.playback.openSession(profileId, bookId)
        if (opened is AppResult.Success) {
            clearFinishedIfRestarting(bookId, opened.value.startAt)
            return AppResult.Success(downloads.sessions.localise(opened.value, manifest))
        }

        val offline = playableOffline(profileId, serverId, bookId, manifest, (opened as AppResult.Failure).error)
        return offline ?: opened
    }

    /**
     * The local session, when there is a complete download and the failure was one a download can answer.
     *
     * Only a transport failure qualifies. `AppError.Authentication` means the credential is the problem, and
     * a listener whose session has expired has to find out — playing on from disk would hide it until the
     * next sync could not upload their progress.
     */
    private suspend fun playableOffline(
        profileId: ProfileId,
        serverId: ServerId?,
        bookId: LibraryItemId,
        manifest: OfflineBook?,
        failure: AppError,
    ): AppResult<PlaybackSession>? {
        if (serverId == null || manifest?.isComplete != true) return null
        if (failure is AppError.Authentication || failure is AppError.Authorization) return null

        val session = downloads.sessions.build(profileId, serverId, bookId, manifest) ?: return null
        logger.info(LogCategory.Playback, "A downloaded book is playing without the server")
        return AppResult.Success(session)
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
     *
     * ### PRODUCT_SPEC 6.5 — [owner] wins over whoever is signed in
     *
     * A write named for a profile is written to that profile, even when the app has since switched away. That
     * is the point: the alternative — resolving the active profile here — is what let a journal tick that
     * began under one account land its position on the next account's row.
     */
    override suspend fun recordPosition(
        bookId: LibraryItemId,
        position: Duration,
        duration: Duration,
        owner: ProfileId?,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val profileId = owner ?: profileRepository.activeProfileId()
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        val profile = profileDao.findProfile(profileId.value)
            ?: return@withContext AppResult.Failure(AppError.Authentication())
        val bookKey = EntityKey.of(profile.serverId, bookId.value)
        val progressKey = EntityKey.scoped(profileId.value, bookKey)
        val stored = progressDao.findProgress(profileId.value, bookKey)
        val isFinished = thresholdFor(bookKey).isFinished(position = position, duration = duration)
        val now = clock.now()
        // PRODUCT_SPEC SYNC-002 — **the unsent decision is made inside the statement, not around it.**
        //
        // `ProgressDao.recordPosition` compares this position against the row's own and leaves the flag
        // alone when nothing moved, all under SQLite's write lock. That is what makes a duplicate record
        // of an already-acknowledged position safe rather than lucky: the earlier Kotlin-side version read
        // the row, decided, and then upserted, and `markProgressSynced` could clear the flag in the gap —
        // after which the already-decided write put it back over its own acknowledgement, and nothing was
        // left to take it down. `docs/risks.md` R-94.
        //
        // The row is written by `UPDATE` because that is the only form in which the comparison can see the
        // previous position at all; a first write for a book has nothing to compare against and is
        // inserted, unsent, which is the correct answer for a position the server has never been told.
        val written = progressDao.recordPosition(
            profileId = profileId.value,
            bookKey = bookKey,
            positionMillis = position.inWholeMilliseconds.coerceAtLeast(0),
            // A duration the player does not know yet — a stream still buffering — must not overwrite one
            // the library already stored, or the book's progress bar empties. Zero means "unknown", and the
            // statement keeps what is there.
            durationMillis = duration.inWholeMilliseconds.coerceAtLeast(0),
            isFinished = isFinished,
            updatedAt = now.toEpochMilli(),
            toleranceMillis = UNCHANGED_TOLERANCE_MS,
        )
        if (written == 0) {
            progressDao.upsertProgress(
                listOf(
                    MediaProgressEntity(
                        progressKey = progressKey,
                        profileId = profileId.value,
                        bookKey = bookKey,
                        serverId = profile.serverId,
                        positionMillis = position.inWholeMilliseconds.coerceAtLeast(0),
                        durationMillis = duration.inWholeMilliseconds.coerceAtLeast(0),
                        isFinished = isFinished,
                        updatedAt = now.toEpochMilli(),
                        hasUnsyncedChanges = true,
                    ),
                ),
            )
        }
        if (stored?.isFinished != true && isFinished) {
            logger.info(
                LogCategory.Playback,
                "A book reached the finished threshold",
                LogField.Millis("remaining", (duration - position).inWholeMilliseconds),
            )
        }
        // PRODUCT_SPEC DL-005 / 6.5 — the halfway mark, considered on the position that just moved past it.
        // It is the journal rather than the player that knows both the old position and the new one, and the
        // use case reads one boolean and returns when the feature is off, which is the usual case.
        //
        // Skipped for a write that belongs to an account the app has switched away from. `SmartDownloadUseCase`
        // resolves the *active* profile to decide what to queue, so a late journal tick from the outgoing
        // listener would otherwise start downloading the next book in a series onto the incoming listener's
        // storage and cellular allowance — the outgoing account's listening spending the incoming one's data.
        if (isStillActive(profileId)) {
            downloads.smartDownload(
                bookId = bookId,
                previousPosition = stored?.positionMillis ?: 0,
                position = position.inWholeMilliseconds,
                duration = duration.inWholeMilliseconds.takeIf { it > 0 } ?: stored?.durationMillis ?: 0,
            )
        }
        AppResult.Success(Unit)
    }

    /**
     * Whether [profileId] is still the account the app is acting as.
     *
     * Only asked by the side effects of a write, never by the write itself. A position belongs to whoever
     * listened and is stored regardless (product priority 2); a *download* it would trigger belongs to
     * whoever is using the app now, and starting one for somebody who has just been switched away from is
     * the cross-account behaviour product priority 4 rules out.
     */
    private suspend fun isStillActive(profileId: ProfileId): Boolean = profileRepository.activeProfileId() == profileId

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
     * PRODUCT_SPEC SYNC-002 — whether another device has moved this book since we last agreed with the
     * server about it.
     *
     * ### The question is asked against a baseline, and that is the rework
     *
     * [baseline] is an [AcknowledgedPause]: a position this device stopped at and Audiobookshelf answered
     * that it had taken. From that instant the two sides demonstrably held the same number. So the whole
     * decision is one comparison against a fact rather than an inference:
     *
     *  - **the server still holds the baseline position** → nothing happened while we were paused, resume
     *    locally ([ExternalSessionCheck.Current]);
     *  - **the server holds something else** → it can only have got there after our acknowledgement, so it
     *    is somebody else's more recent work and it is adopted ([ExternalSessionCheck.Ahead]);
     *  - **there is no acknowledged baseline** → the server's freshness is genuinely unknowable, so
     *    nothing is moved ([ExternalSessionCheck.Unavailable]).
     *
     * No timestamps are read on either side. The four earlier attempts each compared something that could
     * not answer the question — the server's `lastUpdate` against a row this app had written from the
     * server's own value (R-88), how long ago the listener pressed pause (R-89), and `hasUnsyncedChanges`
     * (R-92, R-93) — and each produced a device run where Play resumed on a stale position.
     *
     * ### `hasUnsyncedChanges` is deliberately not consulted
     *
     * It is persistence bookkeeping: it says whether this row still owes the server an upload, which is a
     * fact about a *queue*, not evidence about whether anybody else listened. Using it as a gate was the
     * defect the device log named — a `200` accepting `22461280ms` at 19:58:08, and `Skipped the freshness
     * check … stored=22461280ms player=22461280ms` at 19:58:54 — because a journal write that landed after
     * its own acknowledgement re-raised a flag nothing would lower, and the check then never asked the
     * server at all. The flag keeps its real job (stopping an account sync from rewinding a live position)
     * and loses this one. `docs/risks.md` R-95.
     *
     * ### Exactly one request, and only when it can be acted on
     *
     * With a baseline there is **always** a request: the answer is not derivable from anything local, and
     * the previous shortcuts are what hid remote activity. Without one there is **never** a request: the
     * outcome would be `Unavailable` whatever came back, so the round trip would be latency spent on an
     * answer that cannot move anything. `ProgressSyncChainTest` counts the reads.
     *
     * ### The tolerance
     *
     * [POSITION_TOLERANCE] exists because both sides round — the wire carries fractional seconds, the local
     * row milliseconds — and a resume must not seek by half a second and call it another device.
     *
     * ### The cap is the caller's, not this function's
     *
     * How long a tap may wait is a decision about the tap, so `PlayerViewModel` wraps this call in its own
     * timeout and treats a late answer as `Unavailable`. Putting it here would also have put a virtual
     * clock inside every test of this repository, which is a lot of machinery for a policy that belongs one
     * layer up.
     */
    override suspend fun checkServerPosition(
        bookId: LibraryItemId,
        baseline: AcknowledgedPause?,
    ): ExternalSessionCheck = withContext(ioDispatcher) {
        if (baseline == null || baseline.bookId != bookId) {
            // Logged because the difference between "asked and the server agreed" and "could not ask" is
            // invisible in the audio and is the first thing anyone wants to know afterwards.
            logger.info(
                LogCategory.Sync,
                "No acknowledged pause for this book, so the server's position cannot be judged",
            )
            return@withContext ExternalSessionCheck.Unavailable
        }
        val profileId = profileRepository.activeProfileId() ?: return@withContext ExternalSessionCheck.Unavailable
        when (val remote = gateway.playback.serverProgress(profileId, bookId)) {
            is AppResult.Failure -> ExternalSessionCheck.Unavailable
            is AppResult.Success -> outcomeOf(remote.value, baseline.position).also { outcome ->
                logger.info(
                    LogCategory.Sync,
                    "Compared the server's position with the pause it acknowledged",
                    LogField.Millis("server", remote.value.position.inWholeMilliseconds),
                    LogField.Millis("baseline", baseline.position.inWholeMilliseconds),
                    LogField.Public("generation", baseline.generation.toString()),
                    LogField.Public("verdict", if (outcome is ExternalSessionCheck.Ahead) "ahead" else "current"),
                )
            }
        }
    }

    /**
     * The comparison itself, split out so it can be read without the plumbing around it.
     *
     * [acknowledged] is the position the **server said it had taken**, not what the local row holds and
     * not where the player is. Those two can drift for reasons that have nothing to do with another
     * device — a server-sourced refresh moves the row without moving Media3; a journal tick moves neither —
     * and comparing against either of them is what made three earlier versions of this answer wrong.
     *
     * Magnitude is never compared — only distance. A rewind on another client is somebody else's decision
     * and it is adopted exactly like a fast-forward; `max(position)` would silently overrule the listener
     * who went back for a chapter they missed.
     *
     * `remote.updatedAt` is deliberately unread here. See the header for why the timestamp condition was
     * removed rather than repaired.
     */
    private fun outcomeOf(remote: ServerProgress, acknowledged: Duration): ExternalSessionCheck =
        if ((remote.position - acknowledged).absoluteValue > POSITION_TOLERANCE) {
            ExternalSessionCheck.Ahead(remote.position)
        } else {
            ExternalSessionCheck.Current
        }

    /**
     * ADR-0013 — the rule in force for one book: its own library's, read fresh on every write.
     *
     * Not cached. The value changes when the server's library settings change, and it is one nullable number
     * behind an indexed key — a cache would buy nothing measurable and would hold a stale rule for exactly as
     * long as nobody noticed.
     *
     * A book whose library predates database version 14, or that has no row at all, reads `null` and falls
     * back to `FinishedThreshold.Default`. That is the honest answer rather than a guess: a library the app
     * has never read settings for has not asked for anything.
     */
    private suspend fun thresholdFor(bookKey: String): FinishedThreshold =
        FinishedThreshold(library = FinishedThreshold.libraryRule(libraryDao.finishedSecondsFor(bookKey)))

    private companion object {
        /**
         * How far into a book still counts as "starting it again".
         *
         * A minute. Long enough to survive an auto-rewind and a stored position a few seconds off zero,
         * short enough that nobody re-listening to a chapter trips it.
         */
        val RESTART_WITHIN: Duration = 60.seconds

        /**
         * How far the two positions may differ before it counts as somebody else having moved the book.
         *
         * Five seconds. The wire carries fractional seconds and the local row carries milliseconds, so
         * they are never exactly equal; and the auto-rewind moves the player without anybody having
         * listened, which a tighter tolerance would report as remote activity on every resume.
         */
        val POSITION_TOLERANCE: Duration = 5.seconds

        /**
         * How far a re-recorded position may drift and still count as *the same* position, in milliseconds.
         *
         * A second, matching `DefaultSessionSyncRepository`'s and for the same reason: every write reads
         * the player afresh rather than remembering, so two records of a standing player land a few
         * milliseconds apart. Two consecutive syncs of a paused book logged `22256204ms` and `22256207ms`.
         *
         * Well below [POSITION_TOLERANCE], deliberately. This asks "did anything move at all"; that one
         * asks "did somebody else move it".
         */
        const val UNCHANGED_TOLERANCE_MS = 1_000L
    }
}
