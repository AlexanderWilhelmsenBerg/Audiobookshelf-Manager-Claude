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
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerOutcome
import com.example.shelfplayer.core.model.playback.SleepTimerSettings
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.playback.SleepTimerMath
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
        val sessionId: String?,
        val mode: SleepTimerMode,
        /** Elapsed-realtime millis at which a fixed timer fires. Unused by end-of-chapter. */
        val deadlineElapsedMs: Long,
        /** How many chapter boundaries past the current one an end-of-chapter timer is aiming at. */
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

        val bookId = current.currentMediaItem?.let(MediaItems::bookIdOf)
        running = Running(
            sessionId = bookId?.let { recordStarted(it, mode) },
            mode = mode,
            deadlineElapsedMs = deadlineFor(mode),
            chapterSkip = 0,
        )
        startSensing()
        startTicking()
        publish()
        AppResult.Success(Unit)
    }

    /**
     * PRODUCT_SPEC PLAY-008 — "a notification action extends the timer by the configured amount".
     *
     * Adds to what is left rather than replacing it, which is what "extends" means and what makes a
     * second press worth pressing.
     */
    fun extend() = adjust(restart = false)

    /**
     * ADR-0014 — a shake puts the timer back to its full length.
     *
     * The difference from [extend] shows on a timer nearly done: extending a thirty-minute timer with
     * one minute left gives thirty-one minutes; restarting gives thirty. Restarting is what somebody
     * fumbling for their phone in the dark means.
     */
    fun restart() = adjust(restart = true)

    fun cancel() {
        applicationScope.launch(mainDispatcher) { finish(SleepTimerOutcome.Cancelled) }
    }

    private fun adjust(restart: Boolean) {
        applicationScope.launch(mainDispatcher) {
            val current = running ?: return@launch
            val next = when (val mode = current.mode) {
                is SleepTimerMode.Fixed -> {
                    val base = if (restart) Duration.ZERO else remainingOf(current)
                    current.copy(
                        deadlineElapsedMs =
                        clock.elapsed().inWholeMilliseconds + (base + mode.length).inWholeMilliseconds,
                    )
                }

                SleepTimerMode.EndOfChapter -> current.copy(chapterSkip = current.chapterSkip + 1)
            }
            // An end-of-chapter timer at the last chapter cannot reach further. Leaving it where it is
            // — rather than cancelling, or silently becoming a fixed timer — is the honest answer: the
            // book ends there, and so does the timer.
            if (next.mode == SleepTimerMode.EndOfChapter && remainingToChapterEnd(next.chapterSkip) == null) {
                logger.info(LogCategory.Playback, "The sleep timer is already at the last chapter")
                return@launch
            }
            running = next
            restorePlayerVolume()
            next.sessionId?.let { id -> repository.recordRestarted(id) }
            logger.info(
                LogCategory.Playback,
                if (restart) "The sleep timer was restarted" else "The sleep timer was extended",
            )
            publish()
        }
    }

    /** PRODUCT_SPEC PLAY-008 — sensing starts with a timer and only when the user opted in. */
    private fun startSensing() {
        if (!settings.shakeToRestart) return
        shakes.start(::restart)
    }

    private fun startTicking() {
        ticker?.cancel()
        ticker = applicationScope.launch(mainDispatcher) {
            while (isActive && running != null) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    /**
     * One step of the countdown: recompute, fade, and stop at zero.
     *
     * Recomputed from the clock and the playback position every time rather than decremented. An
     * end-of-chapter timer's remaining time moves with the playback speed, and a decrementing counter
     * would drift from the moment the listener changed it.
     */
    private suspend fun tick() {
        val current = running ?: return
        val remaining = remainingOf(current)
        if (remaining <= Duration.ZERO) {
            expire()
            return
        }
        val fade = settings.fadeLength
        player?.volume = SleepTimerMath.fadeVolume(remaining, fade)
        publish(remaining, isFading = remaining < fade)
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
        player?.pause()
        finish(SleepTimerOutcome.Expired)
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
        // PRODUCT_SPEC PLAY-004 — "sleep-timer stop" is one of the moments a position must reach the server.
        // It is the moment that matters most of the list: a listener who fell asleep is not coming back to
        // press anything, and the next thing this device does may be nothing at all for eight hours.
        sessionSync.request(SyncTrigger.SleepTimerStopped)
        sessionSync.drain()
    }

    /**
     * Volume back to full, always, on every path out of a timer.
     *
     * A cancelled fade that left the volume at `0.2` would be a listener whose book had gone quiet for
     * no reason they could see and no control that fixed it.
     */
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
        val item = current.currentMediaItem ?: return null
        val position = MediaItems.globalPositionOf(item, current.currentPosition)
        return SleepTimerMath.remainingToChapterEnd(chapters, position, skip)
    }

    private fun deadlineFor(mode: SleepTimerMode): Long = when (mode) {
        is SleepTimerMode.Fixed -> clock.elapsed().inWholeMilliseconds + mode.length.inWholeMilliseconds
        SleepTimerMode.EndOfChapter -> 0L
    }

    private suspend fun recordStarted(bookId: LibraryItemId, mode: SleepTimerMode): String? =
        when (val recorded = repository.recordStarted(bookId, mode)) {
            is AppResult.Success -> recorded.value
            // A timer whose history could not be written still runs. The record is worth having and is
            // not worth refusing to start a timer over (product priority 1).
            is AppResult.Failure -> null
        }

    /**
     * The settings, kept warm rather than read per tick.
     *
     * The fade is consulted once a second while a timer runs, and a DataStore read on each of those
     * would be a file read a second for half an hour. Collecting once and holding the latest value
     * costs one coroutine for the life of the process and is always at most one emission stale, which
     * for "how long is the fade" is not a distinction anyone can hear.
     */
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
        _state.value = SleepTimerState(
            mode = current.mode,
            remaining = remaining ?: remainingOf(current),
            isFading = isFading,
        )
    }

    private companion object {
        /** A countdown is read in minutes; a second is fine enough and is what the fade needs. */
        val TICK_MS = 1.seconds.inWholeMilliseconds
        const val FULL_VOLUME = 1f
    }
}
