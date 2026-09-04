package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.SessionProgress
import com.example.shelfplayer.core.model.playback.SyncOutcome
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.playback.ListenedTime
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.repository.SessionSyncRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-004 / PLAY-005 — when a position goes back to the server.
 *
 * The durable repository remains the source of truth. This coordinator owns only the live transition
 * between sessions and the player state needed to build each write. All transitions and remote syncs are
 * serialized by [gate], while player-backed mutable state is read or changed on [mainDispatcher].
 */
@Singleton
class SessionSyncCoordinator @Inject constructor(
    private val repository: SessionSyncRepository,
    private val baseline: ResumeBaseline,
    private val clock: AppClock,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
) {
    private var player: Player? = null
    private var current: Active? = null
    private val listened = ListenedTime()
    private var ticker: Job? = null

    /**
     * One remote-session operation at a time.
     *
     * This protects more than two overlapping sync requests: opening a new book is itself a session
     * transition, so it must not overtake an interval/pause sync or another book opening.
     */
    private val gate = Mutex()

    /** Given the player it reads positions from, as the sleep timer is. `null` on teardown. */
    fun attach(player: Player?) {
        this.player = player
        if (player == null) {
            ticker?.cancel()
            ticker = null
        } else {
            startTicker()
        }
    }

    /**
     * Records the start of a session and does not return until its local outbox row exists.
     *
     * This used to launch the transition on [applicationScope] and return immediately. Callers therefore
     * handed the new item to Media3 while the old session could still be closing and the new row might not
     * exist yet. Making the operation suspend is the ordering guarantee [BookChanges] has always documented:
     * close old -> durably open new -> let playback continue.
     */
    suspend fun onSessionOpened(session: PlaybackSession) {
        gate.withLock {
            closeCurrent(SyncTrigger.BookChanged)
            val opened = repository.openSession(
                bookId = session.bookId,
                remoteSessionId = session.id.takeIf(String::isNotBlank),
                title = session.title,
                author = session.author,
                position = session.startAt,
                duration = session.duration,
                startedAt = clock.now(),
            )
            if (opened is AppResult.Success) {
                withContext(mainDispatcher) {
                    current = Active(sessionId = opened.value, bookId = session.bookId)
                    listened.reset(clock.elapsed())
                }
            }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-004 — records that playback started or stopped, for the listening-time counter.
     *
     * Media3 calls this from the player's application thread. Every other access to [listened] is explicitly
     * moved to [mainDispatcher], so the deliberately non-thread-safe counter remains single-thread confined.
     */
    fun onPlayingChanged(isPlaying: Boolean) {
        listened.onPlayingChanged(isPlaying, clock.elapsed())
    }

    /** Fire-and-forget form for triggers whose caller does not need the acknowledgement. */
    fun request(trigger: SyncTrigger) {
        applicationScope.launch { sync(trigger) }
    }

    /**
     * PRODUCT_SPEC SYNC-002 — the same sync, awaited, returning whether the server took the position.
     *
     * Awaited pause syncs and fire-and-forget interval syncs share [gate], so neither can overtake a book
     * transition or leave the server holding an older position after a newer one.
     */
    suspend fun sync(trigger: SyncTrigger): Boolean = gate.withLock { syncNow(trigger) }

    /** PRODUCT_SPEC PLAY-004 — the last sync, on the application scope so service teardown cannot cancel it. */
    fun onShutdown() {
        applicationScope.launch {
            gate.withLock { closeCurrent(SyncTrigger.ServiceShutdown) }
        }
    }

    /** PRODUCT_SPEC PLAY-005 — uploads whatever the durable outbox still contains. */
    fun drain() {
        applicationScope.launch { repository.drainOutbox() }
    }

    private suspend fun syncNow(trigger: SyncTrigger): Boolean {
        val prepared = withContext(mainDispatcher) {
            val active = current ?: return@withContext null
            val snapshot = snapshot() ?: return@withContext null
            if (snapshot.bookId != active.bookId) {
                logger.debug(
                    LogCategory.Playback,
                    "Skipped a stale player snapshot during session sync",
                    LogField.Public("trigger", trigger.name),
                )
                return@withContext null
            }
            PreparedSync(
                active = active,
                snapshot = snapshot,
                timeListened = listened.drain(clock.elapsed()),
            )
        } ?: return false

        val active = prepared.active
        // Read before the send so an acknowledgement can only promote the generation the request observed.
        val generation = baseline.generationFor(active.bookId)
        val progress = SessionProgress(
            position = prepared.snapshot.position,
            duration = prepared.snapshot.duration,
            timeListened = prepared.timeListened,
        )
        val result = repository.syncOpenSession(
            sessionId = active.sessionId,
            progress = progress,
            updatedAt = clock.now(),
            trigger = trigger,
        )
        if (result is AppResult.Failure) {
            logger.debug(
                LogCategory.Playback,
                "A position stayed queued after a sync attempt",
                LogField.Public("trigger", trigger.name),
                LogField.Public("error", result.error.code),
            )
            return false
        }

        val accepted = (result as AppResult.Success).value == SyncOutcome.Accepted
        if (accepted &&
            generation != null &&
            baseline.onPositionAccepted(active.bookId, progress.position, generation)
        ) {
            logger.debug(
                LogCategory.Playback,
                "The server acknowledged the position this device paused at",
                LogField.Public("trigger", trigger.name),
                LogField.Millis("position", progress.position.inWholeMilliseconds),
                LogField.Public("generation", generation.toString()),
            )
        }
        return accepted
    }

    /**
     * Finalizes the current session using a snapshot that still belongs to that session's book.
     *
     * The player can change independently of an application-scope coroutine. Book identity therefore travels
     * with the snapshot and a mismatched one is dropped rather than attached to the wrong session. The durable
     * outbox can recover an unclosed row; it cannot recover a position written to the wrong book.
     */
    private suspend fun closeCurrent(trigger: SyncTrigger) {
        val prepared = withContext(mainDispatcher) {
            val active = current ?: return@withContext null
            val snapshot = snapshot()
            current = null
            if (snapshot == null) {
                listened.reset(clock.elapsed())
                return@withContext null
            }
            if (snapshot.bookId != active.bookId) {
                listened.reset(clock.elapsed())
                logger.debug(
                    LogCategory.Playback,
                    "Skipped a stale player snapshot while closing a session",
                    LogField.Public("trigger", trigger.name),
                )
                return@withContext null
            }
            PreparedClose(
                active = active,
                snapshot = snapshot,
                timeListened = listened.drain(clock.elapsed()),
            )
        } ?: return

        repository.closeSession(
            sessionId = prepared.active.sessionId,
            progress = SessionProgress(
                position = prepared.snapshot.position,
                duration = prepared.snapshot.duration,
                timeListened = prepared.timeListened,
            ),
            updatedAt = clock.now(),
            trigger = trigger,
        )
    }

    /** PRODUCT_SPEC PLAY-004 — approximately every 30 seconds while the player exists. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = applicationScope.launch {
            while (isActive) {
                delay(REMOTE_SYNC_INTERVAL.inWholeMilliseconds)
                gate.withLock { syncNow(SyncTrigger.Interval) }
                repository.drainOutbox()
            }
        }
    }

    /**
     * Reads one whole-book position from Media3.
     *
     * The returned book id is load-bearing: a position is safe to persist only while the media item that
     * produced it still belongs to [current].
     */
    private fun snapshot(): Snapshot? {
        val media = player ?: return null
        val item = media.currentMediaItem ?: return null
        val positionMs = media.currentPosition
        if (positionMs <= 0L || MediaItems.isSingleFileFallback(item)) return null
        return Snapshot(
            bookId = MediaItems.bookIdOf(item),
            position = media.bookPosition(),
            duration = media.bookDuration(),
        )
    }

    private data class Active(val sessionId: String, val bookId: LibraryItemId)

    private data class Snapshot(
        val bookId: LibraryItemId,
        val position: Duration,
        val duration: Duration,
    )

    private data class PreparedSync(
        val active: Active,
        val snapshot: Snapshot,
        val timeListened: Duration,
    )

    private data class PreparedClose(
        val active: Active,
        val snapshot: Snapshot,
        val timeListened: Duration,
    )

    private companion object {
        /** PRODUCT_SPEC PLAY-004 — "approximately every 30 seconds". */
        val REMOTE_SYNC_INTERVAL: Duration = 30.seconds
    }
}
