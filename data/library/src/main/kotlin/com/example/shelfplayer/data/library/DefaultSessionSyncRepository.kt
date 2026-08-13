package com.example.shelfplayer.data.library

import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.common.time.ServerClock
import com.example.shelfplayer.core.database.dao.ProfileDao
import com.example.shelfplayer.core.database.dao.SessionOutboxDao
import com.example.shelfplayer.core.database.entity.EntityKey
import com.example.shelfplayer.core.database.entity.PlaybackSessionEntity
import com.example.shelfplayer.core.database.entity.SessionOutboxState
import com.example.shelfplayer.core.datastore.AppSettingsDataSource
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.OfflineSession
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SessionSyncDiagnostics
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.network.gateway.AudiobookshelfGateway
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 — the session outbox, and the two routes out of it.
 *
 * ### Write first, send second, always
 *
 * Every method here updates the row before it touches the network, and none of them removes a row because a
 * request failed. That ordering is the whole design: a position that was attempted and lost is
 * indistinguishable from one that was never recorded, and product priority 2 does not allow either. The
 * consequence is that a failure is not an error the caller has to handle carefully — it means "still queued".
 *
 * ### What is deliberately absent
 *
 * **No clamping.** The position sent is the position observed, and `updatedAt` is the moment it was observed.
 * The server takes the newer timestamp and lets progress move *backwards*, which is exactly PLAY-004's "never
 * blindly chooses the maximum position" — implemented on the server, and the app's job is to feed it honest
 * input rather than to pre-empt it. Sending `max(local, remote)` here, or stamping `updatedAt` at upload time,
 * would defeat a rule the server already gets right.
 *
 * **No retry ceiling.** `attempts` climbs and the row stays. An outbox that gave up would be discarding
 * listening the user did.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultSessionSyncRepository @Inject constructor(
    private val settings: AppSettingsDataSource,
    private val profileDao: ProfileDao,
    private val outbox: SessionOutboxDao,
    private val gateway: AudiobookshelfGateway,
    private val serverClock: ServerClock,
    private val clock: AppClock,
    private val logger: Logger,
    @param:Dispatcher(ShelfDispatcher.Io) private val ioDispatcher: CoroutineDispatcher,
) : SessionSyncRepository {

    /**
     * Why the last sync ran, held in memory rather than in a column.
     *
     * It is a property of this *process*, not of a session: the row records that a position was accepted, and
     * the trigger records which of PLAY-004's moments asked for it. Persisting it would make the screen report
     * a reason from a previous run of the app as though it had just happened.
     */
    private val lastTrigger = MutableStateFlow<SyncTrigger?>(null)

    override suspend fun openSession(
        bookId: LibraryItemId,
        remoteSessionId: String?,
        title: String,
        author: String?,
        position: Duration,
        duration: Duration,
        startedAt: Instant,
    ): AppResult<String> = withContext(ioDispatcher) {
        val profile = activeProfile() ?: return@withContext AppResult.Failure(AppError.Authentication())
        // PRODUCT_SPEC PLAY-005 — "every offline listening session has a UUIDv4 identifier". Generated here,
        // for every session, online or not: an id that only exists when there is a connection is an id the
        // offline route cannot use.
        val sessionId = UUID.randomUUID().toString()
        outbox.upsert(
            PlaybackSessionEntity(
                sessionId = sessionId,
                profileId = profile.profileId,
                serverId = profile.serverId,
                bookKey = EntityKey.of(profile.serverId, bookId.value),
                remoteBookId = bookId.value,
                remoteSessionId = remoteSessionId,
                title = title,
                author = author,
                state = SessionOutboxState.OPEN,
                positionMillis = position.inWholeMilliseconds.coerceAtLeast(0),
                durationMillis = duration.inWholeMilliseconds.coerceAtLeast(0),
                timeListenedMillis = 0,
                startedAt = startedAt.toEpochMilli(),
                updatedAt = startedAt.toEpochMilli(),
                syncedAt = null,
                wasProgressApplied = null,
                attempts = 0,
                lastErrorCode = null,
            ),
        )
        logger.info(
            LogCategory.Playback,
            "Recorded the start of a listening session",
            LogField.Public("hasServerSession", remoteSessionId != null),
        )
        AppResult.Success(sessionId)
    }

    override suspend fun syncOpenSession(
        sessionId: String,
        progress: SessionProgress,
        updatedAt: Instant,
        trigger: SyncTrigger,
    ): AppResult<Unit> = send(sessionId, progress, updatedAt, trigger, closing = false)

    override suspend fun closeSession(
        sessionId: String,
        progress: SessionProgress,
        updatedAt: Instant,
        trigger: SyncTrigger,
    ): AppResult<Unit> = send(sessionId, progress, updatedAt, trigger, closing = true)

    /**
     * The shared path: store the position, then try to send it.
     *
     * [closing] chooses the state the row lands in and the route the request takes. Everything before that is
     * identical, which is the point — a close that fails must leave the same durable row a sync that fails
     * does, or the last position of a book would be the one most likely to be lost.
     */
    private suspend fun send(
        sessionId: String,
        progress: SessionProgress,
        updatedAt: Instant,
        trigger: SyncTrigger,
        closing: Boolean,
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val stored = outbox.find(sessionId)
            ?: return@withContext AppResult.Failure(
                AppError.Conflict(summary = "That listening session is no longer recorded on this device."),
            )
        // Stored before the request, and stored whatever the request does.
        outbox.upsert(
            stored.copy(
                positionMillis = progress.position.inWholeMilliseconds.coerceAtLeast(0),
                durationMillis = progress.duration.inWholeMilliseconds.takeIf { it > 0 } ?: stored.durationMillis,
                timeListenedMillis = progress.timeListened.inWholeMilliseconds.coerceAtLeast(0),
                updatedAt = updatedAt.toEpochMilli(),
                state = if (closing) SessionOutboxState.PENDING else SessionOutboxState.OPEN,
            ),
        )
        lastTrigger.value = trigger
        // A session that never reached the server has no id to sync against, so the offline route is the only
        // one open to it. Reporting success here is honest: the position is durably queued, and the drain is
        // what sends it.
        val remoteId = stored.remoteSessionId ?: return@withContext AppResult.Success(Unit)
        val profileId = ProfileId(stored.profileId)
        val result = if (closing) {
            gateway.playback.closeSession(profileId, remoteId, progress)
        } else {
            gateway.playback.syncSession(profileId, remoteId, progress)
        }
        when (result) {
            is AppResult.Failure -> {
                outbox.markAttempted(listOf(sessionId), result.error.code)
                AppResult.Failure(result.error)
            }

            is AppResult.Success -> {
                // Accepted against a server session, so the position *was* applied: the live route answers
                // `200` or it does not answer at all — there is no per-session verdict to read, unlike the
                // offline batch. See `docs/api-compatibility.md`.
                outbox.markSynced(
                    sessionId = sessionId,
                    syncedState = SessionOutboxState.SYNCED,
                    syncedAt = clock.now().toEpochMilli(),
                    wasProgressApplied = true,
                )
                logger.info(
                    LogCategory.Playback,
                    "The server accepted a position",
                    LogField.Public("trigger", trigger.name),
                    LogField.Millis("position", progress.position.inWholeMilliseconds),
                )
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun drainOutbox(): AppResult<Int> = withContext(ioDispatcher) {
        val profile = activeProfile() ?: return@withContext AppResult.Failure(AppError.Authentication())
        // A session left `Open` by a process death is finalized before the drain rather than skipped. Nobody
        // is coming back to it in this process, and leaving it open is how it would sit in the table forever.
        outbox.closeOpen(profile.profileId, SessionOutboxState.OPEN, SessionOutboxState.PENDING)
        val queued = outbox.pending(profile.profileId, SessionOutboxState.SYNCED, BATCH_SIZE)
        if (queued.isEmpty()) {
            compact()
            return@withContext AppResult.Success(0)
        }
        val uploaded = gateway.playback.syncOfflineSessions(
            profileId = ProfileId(profile.profileId),
            sessions = queued.map { row -> row.toOfflineSession() },
        )
        when (uploaded) {
            is AppResult.Failure -> {
                outbox.markAttempted(queued.map(PlaybackSessionEntity::sessionId), uploaded.error.code)
                AppResult.Failure(uploaded.error)
            }

            is AppResult.Success -> {
                val accepted = uploaded.value.filter { it.wasAccepted }
                val syncedAt = clock.now().toEpochMilli()
                accepted.forEach { result ->
                    outbox.markSynced(
                        sessionId = result.id,
                        syncedState = SessionOutboxState.SYNCED,
                        syncedAt = syncedAt,
                        // PRODUCT_SPEC PLAY-004 — recorded, not retried. The server held something newer, and
                        // arguing with that is the app choosing the maximum position by another route.
                        wasProgressApplied = result.wasProgressApplied,
                    )
                }
                // A session the batch did not mention, or reported as not accepted, keeps its row and is sent
                // again on the next drain. `success: false` with no id is the case that would otherwise vanish.
                val unresolved = queued.map(PlaybackSessionEntity::sessionId) - accepted.map { it.id }.toSet()
                if (unresolved.isNotEmpty()) outbox.markAttempted(unresolved, NOT_ACCEPTED)
                logger.info(
                    LogCategory.Playback,
                    "Drained the listening-session outbox",
                    LogField.Count("sent", queued.size),
                    LogField.Count("accepted", accepted.size),
                    LogField.Count("stillQueued", unresolved.size),
                )
                compact()
                AppResult.Success(accepted.size)
            }
        }
    }

    /** PRODUCT_SPEC PLAY-005 — "retained for seven days for diagnostics, then compacted". */
    private suspend fun compact() {
        val removed = outbox.compact(
            syncedState = SessionOutboxState.SYNCED,
            before = clock.now().minusMillis(RETENTION.inWholeMilliseconds).toEpochMilli(),
        )
        if (removed > 0) {
            logger.info(
                LogCategory.Playback,
                "Compacted uploaded listening sessions past their retention",
                LogField.Count("removed", removed),
            )
        }
    }

    override fun observeDiagnostics(): Flow<SessionSyncDiagnostics> = settings.activeProfileId
        .flatMapLatest { profileId ->
            if (profileId == null) {
                // The skew reading survives having no profile: it is a fact about this device's clock, and it
                // is measured by every request the app has ever made, including a sign-in that failed.
                serverClock.skew.map { reading -> SessionSyncDiagnostics(clockSkew = reading) }
            } else {
                diagnosticsFor(profileId.value)
            }
        }

    private fun diagnosticsFor(profileId: String): Flow<SessionSyncDiagnostics> = combine(
        outbox.observeCount(profileId),
        combine(
            outbox.observeCountIn(profileId, SessionOutboxState.OPEN),
            outbox.observeCountIn(profileId, SessionOutboxState.PENDING),
            outbox.observeCountIn(profileId, SessionOutboxState.SYNCED),
            outbox.observeProgressDeclined(profileId),
        ) { open, pending, synced, declined -> Counts(open, pending, synced, declined) },
        outbox.observeLastSynced(profileId),
        outbox.observeLastFailure(profileId, SessionOutboxState.SYNCED),
        combine(lastTrigger, serverClock.skew, ::Pair),
    ) { total, counts, lastSynced, lastFailure, session ->
        SessionSyncDiagnostics(
            sessionsRecorded = total,
            // Open rows are pending too: the server has not accepted them, which is the only thing "pending"
            // means here. Reporting them apart would make a book being listened to right now read as synced.
            sessionsPending = counts.open + counts.pending,
            sessionsOpen = counts.open,
            sessionsSynced = counts.synced,
            progressDeclined = counts.declined,
            lastSyncedAt = lastSynced?.syncedAt?.let(Instant::ofEpochMilli),
            lastTrigger = session.first,
            lastFailureCode = lastFailure?.lastErrorCode,
            clockSkew = session.second,
        )
    }

    private data class Counts(val open: Int, val pending: Int, val synced: Int, val declined: Int)

    private suspend fun activeProfile() = settings.current().activeProfileId
        .takeIf(String::isNotBlank)
        ?.let { profileDao.findProfile(it) }

    private fun PlaybackSessionEntity.toOfflineSession() = OfflineSession(
        id = sessionId,
        bookId = LibraryItemId(remoteBookId),
        title = title,
        author = author,
        progress = SessionProgress(
            position = positionMillis.milliseconds,
            duration = durationMillis.milliseconds,
            timeListened = timeListenedMillis.milliseconds,
        ),
        startedAt = Instant.ofEpochMilli(startedAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
    )

    private companion object {
        /** PRODUCT_SPEC PLAY-005 — the retention before compaction. */
        val RETENTION: Duration = 7.days

        /**
         * How many sessions one drain uploads.
         *
         * Bounded because the batch is a single request body and a device that was offline for a fortnight
         * could have queued a lot of them. What is left is sent by the next drain — the queue is durable, so a
         * bound delays an upload rather than dropping it.
         */
        const val BATCH_SIZE = 50

        const val NOT_ACCEPTED = "not_accepted"
    }
}
