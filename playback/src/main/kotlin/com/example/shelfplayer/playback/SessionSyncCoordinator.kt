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
 * ### The moments PLAY-004 names
 *
 * "Approximately every 30 seconds, plus on pause, seek completion, chapter change, book change,
 * sleep-timer stop, service shutdown callback, and app background transition." Each of those is a
 * [SyncTrigger] and each is called from the place that knows it happened: the service for the player
 * events and its own teardown, the sleep timer for its stop, the app's lifecycle for the background
 * transition. Nothing here infers a trigger from a state change, because a trigger inferred from state is
 * a trigger that fires on rotation.
 *
 * ### A singleton, for the same reason the sleep timer is one
 *
 * `PlaybackService` declares no `android:process`, so the service and the app's UI share this object. That
 * is what lets the app's background transition reach the session the *service* owns without a session
 * command, and it is why a sync requested by the UI and one requested by the ticker cannot end up with two
 * different ideas of what is playing.
 *
 * ### Why a failed sync is not handled here
 *
 * `SessionSyncRepository` writes the row before it sends, and leaves it queued when the send fails. So there
 * is nothing for this class to retry: the next tick, the next pause, or the next drain sends it. That is the
 * whole reason the outbox exists (product priority 2).
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
     * One sync at a time.
     *
     * The triggers are genuinely concurrent — the ticker fires while a pause is still being written — and two
     * overlapping syncs on the same session would send the second's position and then the first's, leaving
     * the server holding the older of the two under the newer timestamp.
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
     * Records the start of a session and begins the cadence for it.
     *
     * The previous session is closed first. A book that follows another must not have the earlier one's
     * listening time attributed to it, and an outbox row left `Open` forever is a row that only the
     * process-death path would ever finalize.
     */
    fun onSessionOpened(session: PlaybackSession) {
        applicationScope.launch {
            closeCurrent(SyncTrigger.BookChanged)
            val opened = repository.openSession(
                bookId = session.bookId,
                // The server's id for this session, which is what the live sync route needs. It is `null`
                // for a session that never reached the server, and the offline route is then the only way
                // its listening can ever be uploaded (PLAY-005).
                remoteSessionId = session.id.takeIf(String::isNotBlank),
                title = session.title,
                author = session.author,
                position = session.startAt,
                duration = session.duration,
                startedAt = clock.now(),
            )
            if (opened is AppResult.Success) {
                current = Active(sessionId = opened.value, bookId = session.bookId)
                listened.reset(clock.elapsed())
            }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-004 — records that playback started or stopped, for the listening-time counter.
     *
     * Separate from [request] on purpose: a pause both closes the interval *and* is a sync trigger, and the
     * order matters — the interval has to close before the sync reads it, or the sync reports less listening
     * than happened.
     */
    fun onPlayingChanged(isPlaying: Boolean) {
        listened.onPlayingChanged(isPlaying, clock.elapsed())
    }

    /**
     * Sends the current position, if there is a session to send it against.
     *
     * Everything is read on the main thread, because every [Player] property must be. The send itself is not:
     * the repository moves to IO for the row and the request.
     *
     * Fire and forget, on the application scope. [sync] is the same work awaited, for the one caller that
     * has to know whether it landed.
     */
    fun request(trigger: SyncTrigger) {
        applicationScope.launch { sync(trigger) }
    }

    /**
     * PRODUCT_SPEC SYNC-002 — the same sync, **awaited**, returning whether the server took the position.
     *
     * ### Why the pause needs this and nothing else did
     *
     * The freshness check before a Play decides from an *acknowledged* pause: a position this device
     * stopped at and Audiobookshelf confirmed it holds (see `ResumeBaseline`). "Confirmed" is only knowable
     * by waiting for the answer, so the pause path awaits its own sync instead of launching it and moving
     * on. Every other trigger stays fire-and-forget, because no decision hangs on their outcome.
     *
     * The promotion itself happens in [syncNow], for every trigger rather than only this one: a pause whose
     * upload failed and whose next thirty-second tick succeeded is just as firmly agreed, and there is no
     * reason to throw that away.
     *
     * Still under [gate], so awaiting this cannot overtake a sync already in flight — which is the ordering
     * that stops the server being left holding the older of two positions.
     */
    suspend fun sync(trigger: SyncTrigger): Boolean = gate.withLock { syncNow(trigger) }

    /**
     * PRODUCT_SPEC PLAY-004 — the last sync, on the way out.
     *
     * On the application scope rather than the service's, for the reason `PlaybackService.flushProgress`
     * gives: the service's scope is cancelled in `onDestroy`, which is exactly when this matters most.
     */
    fun onShutdown() {
        applicationScope.launch {
            gate.withLock { closeCurrent(SyncTrigger.ServiceShutdown) }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-005 — uploads whatever is queued.
     *
     * Called when a sync trigger lands and after the app comes back online. A drain with an empty queue makes
     * no request at all (`AbsPlaybackApi.syncOfflineSessions`), so calling it often is cheap.
     */
    fun drain() {
        applicationScope.launch { repository.drainOutbox() }
    }

    private suspend fun syncNow(trigger: SyncTrigger): Boolean {
        val active = current ?: return false
        val snapshot = withContext(mainDispatcher) { snapshot() } ?: return false
        // PRODUCT_SPEC SYNC-002 — read *before* the send, so an acknowledgement can only ever be applied to
        // the pause that was current when the upload was built. See `ResumeBaseline.generationFor`.
        val generation = baseline.generationFor(active.bookId)
        // The position and the moment it was observed, together. `updatedAt` is stamped here rather than at
        // upload time because the server resolves conflicts on it: a timestamp taken when the request finally
        // succeeds would let a position observed ten minutes ago beat one set since (PLAY-004).
        val updatedAt = clock.now()
        val elapsed = clock.elapsed()
        val progress = SessionProgress(
            position = snapshot.position,
            duration = snapshot.duration,
            // The delta since the previous sync, not the session total. See `ListenedTime`: whether the live
            // route accumulates this field or replaces it is not settled by any capture we hold, and the
            // delta is the reading that is either correct or harmlessly small rather than wrong and large.
            timeListened = listened.drain(elapsed),
        )
        val result = repository.syncOpenSession(active.sessionId, progress, updatedAt, trigger)
        if (result is AppResult.Failure) {
            logger.debug(
                LogCategory.Playback,
                "A position stayed queued after a sync attempt",
                LogField.Public("trigger", trigger.name),
                LogField.Public("error", result.error.code),
            )
            return false
        }
        // PRODUCT_SPEC SYNC-002 — **only an accepted position may promote the baseline.** Success alone is
        // not enough: a session that never reached `/play` has no server id, so its position is durably
        // queued and never sent, and success says so honestly. Treating that as an acknowledgement would
        // build a baseline the server has never seen, and the next Play would then read the server's older
        // position, call it another device's work, and rewind the listener onto it. See `SyncOutcome`.
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

    /** Finalizes the open session, if any, and stops attributing listening time to it. */
    private suspend fun closeCurrent(trigger: SyncTrigger) {
        val active = current ?: return
        val snapshot = withContext(mainDispatcher) { snapshot() }
        current = null
        if (snapshot == null) return
        repository.closeSession(
            sessionId = active.sessionId,
            progress = SessionProgress(
                position = snapshot.position,
                duration = snapshot.duration,
                timeListened = listened.drain(clock.elapsed()),
            ),
            updatedAt = clock.now(),
            trigger = trigger,
        )
    }

    /**
     * PRODUCT_SPEC PLAY-004 — "approximately every 30 seconds".
     *
     * A timer rather than a listener, for the reason the local journal uses one: Media3 has no "the position
     * moved" callback. It runs while the player exists rather than only while playing, so a book paused and
     * left paused still has its final position on the server within half a minute.
     */
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
     * Where the book is, read from the player.
     *
     * `null` when there is nothing worth sending — the same guard the local journal applies: a player still
     * on the first item at position zero has not started, and reporting that would move a listener back to
     * the beginning of a book they were part-way through (product priority 2).
     */
    private fun snapshot(): Snapshot? {
        val media = player ?: return null
        val item = media.currentMediaItem ?: return null
        val positionMs = media.currentPosition
        /*
         * The same two reasons as the local journal's `positionSnapshot`, and the same single condition.
         *
         * Zero means the book has not started. `docs/risks.md` R-61 is the second: when a track's length was
         * unknown and unrecoverable, the player's timeline is one file and `currentPosition` is an offset
         * into it. Sending that to `PATCH /api/me/progress/…` would overwrite the account's progress *on the
         * server*, where every other device then reads it — the widest form of the same data loss.
         * `BookMediaSourceFactory` logs the reason once when the session opens.
         */
        if (positionMs <= 0L || MediaItems.isSingleFileFallback(item)) return null
        // ADR-0016 — one window per book, so both numbers are the book's and neither is converted.
        return Snapshot(position = media.bookPosition(), duration = media.bookDuration())
    }

    private data class Active(val sessionId: String, val bookId: LibraryItemId)

    private data class Snapshot(val position: Duration, val duration: Duration)

    private companion object {
        /** PRODUCT_SPEC PLAY-004 — "approximately every 30 seconds". */
        val REMOTE_SYNC_INTERVAL: Duration = 30.seconds
    }
}
