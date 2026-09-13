package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.playback.SleepTimerMath
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.SleepTimerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-008 — the sleep timer.
 *
 * ### Why a singleton rather than state inside the service
 *
 * The service and the app's UI are in the same process — `PlaybackService` declares no
 * `android:process`, so a `@Singleton` really is shared between them. That is what satisfies "timer
 * survives activity recreation" without serialising anything: destroying the activity destroys the
 * composition, not this object. A timer only dies with the process, and so does the player it was
 * stopping.
 *
 * ### What restarting means, and where it deviates from the requirement
 *
 * PLAY-008 says a notification action "extends the timer by the configured amount". The project owner
 * asked for a **shake that restarts** it, and those differ on a timer already part-way down: extending
 * adds to what is left, restarting goes back to the full length. Both are implemented — [extend] for
 * the notification action, [restart] for the shake — and ADR-0014 records why.
 *
 * An end-of-chapter timer restarts to the end of the *next* chapter. Restarting it to the current
 * chapter's end would be a shake that does nothing, since that is the boundary it was already stopping
 * at.
 *
 * ### Motion sensing is bounded by the timer
 *
 * [ShakeDetector.start] is called when a timer starts and [ShakeDetector.stop] when it ends, so there is
 * no state in which the accelerometer is registered and no timer is running — which is the second half
 * of PLAY-008's shake requirement and the half that is easy to get wrong.
 */
@Singleton
class SleepTimerController @Inject constructor(
    private val repository: SleepTimerRepository,
    private val shakes: ShakeDetector,
    private val sessionSync: SessionSyncCoordinator,
    private val history: PlaybackHistoryRepository,
    private val stopHistoryCause: PlaybackStopHistoryCause,
    private val clock: AppClock,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(SleepTimerState.Idle)

    /** What the player screen, the mini player and the notification all read. */
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var player: Player? = null
    private var chapters: List<Chapter> = emptyList()
    private var ticker: Job? = null

    /** The running timer's bookkeeping, or `null`. Read and written on [mainDispatcher] only. */
    private var running: Running? = null

    private data class Running(
        val mode: SleepTimerMode,
        val deadlineElapsedMs: Long,
        val sessionId: String?,
        val chapterSkip: Int,
    )

    /**
     * Hands the controller the player it is allowed to stop.
     *
     * Called by the service when it creates the player, and with `null` when it releases it. A timer
     * with no player cannot fire, so releasing the player ends any running timer as
     * [SleepTimerOutcome.PlaybackStopped] rather than leaving one counting down against nothing.
     */
    fun attach(player: Player?) {
        this.player = player
        if (player == null && running != null) {
            applicationScope.launch(mainDispatcher) { finish(SleepTimerOutcome.PlaybackStopped) }
        }
    }

    /**
     * The chapters of the book now playing, for [SleepTimerMode.EndOfChapter].
     *
     * Supplied by `PlaybackController` when it starts a book rather than travelling in the playlist:
     * a forty-track book's chapter list in every `MediaItem`'s extras would be tens of kilobytes across
     * the binder, to answer a question only this object asks.
     *
     * Changing books cancels a running timer. A timer set on one book is not a timer on the next, and
     * silently carrying it over would stop a book the listener had just chosen to start.
     */
    fun onBookChanged(chapters: List<Chapter>) {
        this.chapters = chapters
        if (running != null) {
            applicationScope.launch(mainDispatcher) { finish(SleepTimerOutcome.PlaybackStopped) }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-008 — starts a timer, replacing any that was running.
     *
     * Returns a failure the caller can show. The one a listener will actually meet is asking for
     * end-of-chapter on a book with no usable chapters, which is a sentence rather than a timer that
     * silently becomes something else.
     */
    suspend fun start(mode: SleepTimerMode): AppResult<Unit> = withContext(mainDispatcher) {
        val current = player ?: return@withContext AppResult.Failure(
            AppError.Playback(summary = "Nothing is playing, so there is nothing to stop."),
        )
        if (mode is SleepTimerMode.EndOfChapter && remainingToChapterEnd(skip = 0) == null) {
            return@withContext AppResult.Failure(
                AppError.Playback(summary = "This book has no chapters to stop at."),
            )
        }
        if (running != null) finish(SleepTimerOutcome.Cancelled)
        val result = repository.start(mode)
        if (result is AppResult.Failure) return@withContext result
        val started = Running(
            mode = mode,
            deadlineElapsedMs = deadlineFor(mode),
            sessionId = current.currentMediaItem?.let(MediaItems::bookIdOf)?.let { bookId ->
                recordStarted(bookId, mode)
            },
            chapterSkip = 0,
        )
        running = started
        shakes.start { applicationScope.launch(mainDispatcher) { restart() } }
        ticker?.cancel()
        ticker = applicationScope.launch(mainDispatcher) {
            while (isActive && running != null) {
                tick()
                delay(TICK_INTERVAL)
            }
        }
        running?.let { currentRunning -> publish(remainingOf(currentRunning), isFading = false) }
        running?.let { started -> record(PlaybackEvent.SleepTimerStarted, detail = remainingOf(started)) }
        AppResult.Success(Unit)
    }

    /** PRODUCT_SPEC PLAY-008 — extend by the configured amount, from the notification action. */
    suspend fun extend(): AppResult<Unit> = withContext(mainDispatcher) {
        val current = running ?: return@withContext AppResult.Failure(
            AppError.Validation(summary = "No sleep timer is running."),
        )
        val amount = settings.extendBy
        val next = when (current.mode) {
            is SleepTimerMode.Fixed -> current.copy(deadlineElapsedMs = current.deadlineElapsedMs + amount.inWholeMilliseconds)
            SleepTimerMode.EndOfChapter -> current.copy(chapterSkip = current.chapterSkip + 1)
        }
        running = next
        repository.extend(amount)
        publish(remainingOf(next), isFading = false)
        record(PlaybackEvent.SleepTimerExtended, detail = remainingOf(next))
        AppResult.Success(Unit)
    }

    /**
     * Product-owner choice — a shake **restarts** the configured timer rather than extending what is left.
     */
    suspend fun restart(): AppResult<Unit> = withContext(mainDispatcher) {
        val current = running ?: return@withContext AppResult.Failure(
            AppError.Validation(summary = "No sleep timer is running."),
        )
        val next = when (current.mode) {
            is SleepTimerMode.Fixed -> current.copy(deadlineElapsedMs = deadlineFor(current.mode))
            SleepTimerMode.EndOfChapter -> current.copy(chapterSkip = current.chapterSkip + 1)
        }
        running = next
        repository.restart()
        publish(remainingOf(next), isFading = false)
        AppResult.Success(Unit)
    }

    suspend fun cancel(): AppResult<Unit> = withContext(mainDispatcher) {
        if (running == null) return@withContext AppResult.Success(Unit)
        finish(SleepTimerOutcome.Cancelled)
        AppResult.Success(Unit)
    }

    private suspend fun tick() {
        val current = running ?: return
        val remaining = remainingOf(current)
        if (remaining <= Duration.ZERO) {
            expire()
            return
        }
        val fade = settings.fadeLength
        player?.volume = if (fade > Duration.ZERO) SleepTimerMath.fadeVolume(remaining, fade) else FULL_VOLUME
        publish(remaining, isFading = fade > Duration.ZERO && remaining < fade)
    }

    /**
     * PRODUCT_SPEC PLAY-008 — "timer expiration pauses, records progress, and syncs".
     *
     * Pausing is here. Recording the position is the service's five-second journal plus its
     * pause listener, which fires on this very pause — so the position is written by the path that
     * already owns it rather than by a second one that could disagree. Syncing to the server is wave
     * 3's outbox, and until it exists this is honestly a local record only.
     */
    private suspend fun expire() {
        logger.info(
            LogCategory.Playback,
            "The sleep timer expired and paused playback",
            LogField.Public("mode", running?.mode?.let { it::class.simpleName }.orEmpty()),
        )
        val media = player
        if (media?.playWhenReady == true) {
            // #139 — PlaybackService remains the single owner of transport-end history. Mark this pause
            // before asking Media3 to perform it so onPlayWhenReadyChanged writes SleepTimerExpired
            // instead of a second unrelated Pause row for the same physical stop.
            stopHistoryCause.markSleepTimerExpiry()
        }
        media?.pause()
        rewindAfterStop()
        finish(SleepTimerOutcome.Expired)
    }

    private fun rewindAfterStop() {
        val amount = settings.rewindOnStop
        if (amount <= Duration.ZERO) return
        val media = player ?: return
        media.currentMediaItem ?: return
        val from = media.bookPosition()
        val to = (from - amount).coerceAtLeast(Duration.ZERO)
        if (to >= from) return
        media.seekTo(to.inWholeMilliseconds)
        logger.info(
            LogCategory.Playback,
            "Rewound because the sleep timer stopped playback",
            LogField.Millis("amount", (from - to).inWholeMilliseconds),
        )
        record(PlaybackEvent.SleepTimerRewind, from = from, to = to)
    }

    private fun record(event: PlaybackEvent, from: Duration? = null, to: Duration? = null, detail: Duration? = null) {
        val media = player ?: return
        val bookId = media.currentMediaItem?.let(MediaItems::bookIdOf) ?: return
        val at = to ?: media.bookPosition()
        applicationScope.launch { history.record(bookId, event, from, at, detail) }
    }

    private suspend fun finish(outcome: SleepTimerOutcome) {
        val current = running ?: return
        running = null
        ticker?.cancel()
        ticker = null
        shakes.stop()
        restorePlayerVolume()
        _state.value = SleepTimerState.Idle
        current.sessionId?.let { id -> repository.recordEnded(id, outcome) }
        sessionSync.request(SyncTrigger.SleepTimerStopped)
        sessionSync.drain()
    }

    private fun restorePlayerVolume() {
        player?.volume = FULL_VOLUME
    }

    private fun remainingOf(current: Running): Duration = when (current.mode) {
        is SleepTimerMode.Fixed -> SleepTimerMath.remainingUntil(
            deadline = current.deadlineElapsedMs.milliseconds,
            elapsed = clock.elapsed(),
        )

        SleepTimerMode.EndOfChapter -> remainingToChapterEnd(current.chapterSkip) ?: Duration.ZERO
    }

    private fun remainingToChapterEnd(skip: Int): Duration? {
        val current = player ?: return null
        return SleepTimerMath.remainingToChapterEnd(chapters, current.bookPosition(), skip)
    }

    private fun deadlineFor(mode: SleepTimerMode): Long = when (mode) {
        is SleepTimerMode.Fixed -> clock.elapsed().inWholeMilliseconds + mode.length.inWholeMilliseconds
        SleepTimerMode.EndOfChapter -> 0L
    }

    private suspend fun recordStarted(bookId: LibraryItemId, mode: SleepTimerMode): String? =
        when (val recorded = repository.recordStarted(bookId, mode)) {
            is AppResult.Success -> recorded.value
            is AppResult.Failure -> null
        }

    @Volatile
    private var settings: SleepTimerSettings = SleepTimerSettings.Default

    init {
        applicationScope.launch {
            repository.observeSettings().collect { latest -> settings = latest }
        }
    }

    private fun publish(remaining: Duration? = null, isFading: Boolean = false) {
        val current = running
        if (current == null) {
            _state.value = SleepTimerState.Idle
            return
        }
        _state.value = SleepTimerState.Running(
            mode = current.mode,
            remaining = remaining ?: remainingOf(current),
            isFading = isFading,
        )
    }

    private companion object {
        val TICK_INTERVAL = 1.seconds
        const val FULL_VOLUME = 1f
    }
}
