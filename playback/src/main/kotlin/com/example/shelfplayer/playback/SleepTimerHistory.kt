package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/** Timer persistence effects; the controller remains the sole owner of phase and eligibility. */
@Singleton
class SleepTimerHistory @Inject constructor(
    private val timers: SleepTimerRepository,
    private val history: PlaybackHistoryRepository,
    private val sessionSync: SessionSyncCoordinator,
    private val clock: AppClock,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    /** Snapshot the exact book, profile, position and time before dispatching the best-effort write. */
    internal fun record(
        player: Player?,
        event: PlaybackEvent,
        from: Duration? = null,
        to: Duration? = null,
        detail: Duration? = null,
    ) {
        val media = player ?: return
        val item = media.currentMediaItem ?: return
        val bookId = MediaItems.bookIdOf(item)
        val position = to ?: sessionSync.effectiveBookPosition(media)
        val owner = MediaItems.ownerOf(item)
        val happenedAt = clock.now()
        applicationScope.launch {
            history.record(bookId, event, from, position, detail, at = happenedAt, owner = owner)
        }
    }

    internal suspend fun recordStarted(bookId: LibraryItemId, mode: SleepTimerMode): String? =
        when (val recorded = timers.recordStarted(bookId, mode)) {
            is AppResult.Success -> recorded.value

            // History failure must not prevent a timer from protecting playback.
            is AppResult.Failure -> null
        }

    internal suspend fun closeUncommittedSession(sessionId: String?) {
        sessionId?.let { timers.recordEnded(it, SleepTimerOutcome.PlaybackStopped) }
    }

    /** Called only after the controller has retired the running timer's actionable state. */
    internal suspend fun recordEnded(sessionId: String?, outcome: SleepTimerOutcome) {
        sessionId?.let { timers.recordEnded(it, outcome) }
        sessionSync.request(SyncTrigger.SleepTimerStopped)
        sessionSync.drain()
    }
}
