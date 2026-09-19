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
 * [reconcileSensing] derives registration from both the active timer and persisted opt-in. It keeps the
 * accelerometer off with no timer while handling asynchronous settings delivery and active-timer changes.
 */
@Singleton
class SleepTimerController @Inject constructor(
    private val repository: SleepTimerRepository,
    private val shakes: ShakeSource,
    private val sessionSync: SessionSyncCoordinator,
    private val history: PlaybackHistoryRepository,
    private val clock: AppClock,
    private val zoneProvider: LocalZoneProvider,
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
    private var scheduleBoundary: Job? = null

    /** True only while this attached player is actually producing audio. Main-dispatcher confined. */
    private var playbackActive = false

    /**
     * Whether the next transition into actual playback came from an explicit controller Play.
     *
     * The service records this at the MediaSession boundary. Passive playback resumption explicitly writes
     * false, while direct service-owned starts leave it false. It matters only after an automatic timer has
     * expired; ordinary first playback in a window remains eligible regardless of source.
     */
    private var nextPlaybackExplicit = false

    /** The running timer's bookkeeping, or `null`. Read and written on [mainDispatcher] only. */
    private var running: Running? = null

    private data class Running(
        val sessionId: String?,
        val mode: SleepTimerMode,
        /** Elapsed-realtime millis at which a fixed timer fires. Unused by end-of-chapter. */
        val deadlineElapsedMs: Long,
        /** How many chapter boundaries past the current one an end-of-chapter timer is aiming at. */
        val chapterSkip: Int,
        /** BW-SLEEP-01 occurrence id when schedule-created; null for every manually-created timer. */
        val automaticOccurrence: String?,
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
        if (player == null) {
            playbackActive = false
            nextPlaybackExplicit = false
            scheduleBoundary?.cancel()
            scheduleBoundary = null
            if (running != null) {
                applicationScope.launch(mainDispatcher) { finish(SleepTimerOutcome.PlaybackStopped) }
            }
        }
    }

    /**
     * BW-SLEEP-01 — records the origin of the next standard Play before the raw player sees it.
     *
     * Explicit means a controller/user Play. Passive Media3 playback resumption records false at the same
     * boundary, and service-owned automatic starts do not call this method at all.
     */
    fun onPlayRequest(explicit: Boolean) {
        applicationScope.launch(mainDispatcher) { nextPlaybackExplicit = explicit }
    }

    /**
     * BW-SLEEP-01 — the service forwards actual audio activity; this controller remains the policy owner.
     *
     * Crossing from paused/buffering to playing reconciles current eligibility. After an automatic expiry,
     * only the explicit marker above can reopen the same occurrence. Rebuffer recovery therefore cannot
     * manufacture a second automatic timer.
     */
    fun onPlaybackChanged(isPlaying: Boolean) {
        applicationScope.launch(mainDispatcher) {
            playbackActive = isPlaying
            if (!isPlaying) {
                nextPlaybackExplicit = false
                // A schedule-created timer still has an end boundary even while playback is paused.
                // No new timer may arm until actual playback becomes active again.
                scheduleScheduleBoundary()
                return@launch
            }
            val explicit = nextPlaybackExplicit
            nextPlaybackExplicit = false
            reconcileSchedule(explicitPlay = explicit)
        }
    }

    /** Android wall-clock/timezone observation delegates here; no platform policy leaks into this owner. */
    fun onWallClockChanged() {
        applicationScope.launch(mainDispatcher) { reconcileSchedule() }
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
        startTimer(mode = mode, automaticOccurrence = null)
    }

    /**
     * The single creation path for both manual and scheduled timers.
     *
     * BW-SLEEP-01 deliberately lands here instead of maintaining another countdown. [automaticOccurrence]
     * is ownership metadata only; deadline, fade, extension, rewind, history and expiry remain exactly the
     * ordinary PLAY-008 timer.
     */
    private suspend fun startTimer(mode: SleepTimerMode, automaticOccurrence: String?): AppResult<Unit> {
        val current = player ?: return AppResult.Failure(
            AppError.Playback(summary = "Nothing is playing, so there is nothing to stop."),
        )
        if (mode is SleepTimerMode.EndOfChapter && remainingToChapterEnd(skip = 0) == null) {
            return AppResult.Failure(
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
            automaticOccurrence = automaticOccurrence,
        )
        // PRODUCT_SPEC PLAY-003 — the device report asked for this: a timer being set is a decision about
        // the book, and the history is where an evening gets reconstructed. The length travels as the
        // detail, because "sleep timer" on its own says nothing a listener can use.
        running?.let { started -> record(PlaybackEvent.SleepTimerStarted, detail = remainingOf(started)) }
        reconcileSensing()
        startTicking()
        publish()
        scheduleScheduleBoundary()
        return AppResult.Success(Unit)
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
        applicationScope.launch(mainDispatcher) {
            finish(SleepTimerOutcome.Cancelled, suppressAutomaticRearm = true)
        }
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
            // PRODUCT_SPEC PLAY-003 — the device report named this one specifically: *"the shake to extend
            // won't give an event in history"*. Both routes land here, and both are recorded, because from
            // the listener's side they are the same event — the timer moved and they want to know when.
            record(PlaybackEvent.SleepTimerExtended, detail = remainingOf(next))
            publish()
        }
    }

    /**
     * PRODUCT_SPEC PLAY-008 — motion sensing exists exactly while both owners require it.
     *
     * The timer and persisted opt-in arrive independently. Previously the setting was checked once at timer
     * start, so a late first settings emission left that timer with no listener, and mid-timer setting changes
     * were ignored. Reconciliation is idempotent and main-dispatcher confined with [running].
     */
    private fun reconcileSensing() {
        val shouldSense = running != null && settings.shakeToRestart
        when {
            shouldSense && !shakes.isSensing -> shakes.start(::restart)
            !shouldSense && shakes.isSensing -> shakes.stop()
        }
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
        // PLAY-008's fade is *optional*, and zero is how it is declined. `fadeVolume` would return full
        // volume for a zero fade anyway; the guard is here so `isFading` cannot be reported true for a
        // fade that is not happening, which is what the player's UI reads to show its fading state.
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
        player?.pause()
        record(PlaybackEvent.SleepTimerExpired)
        rewindAfterStop()
        finish(SleepTimerOutcome.Expired)
    }

    /**
     * PRODUCT_SPEC PLAY-008 / PLAY-009 — winds the book back after the timer stopped it.
     *
     * The owner asked for this by example: *"if I set on a sleep timer I can set rewind time for five
     * minutes and it will rewind five minutes"*. Somebody who fell asleep does not know when they stopped
     * following, only that it was a while before the timer fired — so the amount is theirs to choose and
     * the app's job is to apply it exactly.
     *
     * **After the pause, not before.** Rewinding a playing book would be audible: five minutes of audio
     * would start again and then stop. Paused first, moved second, and the listener finds the new position
     * when they come back.
     *
     * Clamped to the start of the book, and **not** to the start of the chapter. Auto-rewind clamps to the
     * chapter because a few seconds either side of a boundary is ambiguous; five minutes is not, and a
     * listener who fell asleep across a chapter break wants the part they slept through, not the boundary.
     *
     * Off by default. A feature that moves a saved position without being asked is the one thing product
     * priority 2 does not tolerate.
     */
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

    /**
     * PRODUCT_SPEC PLAY-003 — a timer event, in the book's history.
     *
     * The device report that asked for this: *"starting sleep timer doesn't show"*. A timer is a decision
     * about the book, and the history is where a listener looks to reconstruct an evening.
     *
     * Silent when there is no book. Everything here is a side effect of something that already happened,
     * and none of it may prevent the timer from working (product priority 1).
     */
    private fun record(event: PlaybackEvent, from: Duration? = null, to: Duration? = null, detail: Duration? = null) {
        val media = player ?: return
        val bookId = media.currentMediaItem?.let(MediaItems::bookIdOf) ?: return
        val at = to ?: media.bookPosition()
        applicationScope.launch { history.record(bookId, event, from, at, detail) }
    }

    private suspend fun finish(outcome: SleepTimerOutcome, suppressAutomaticRearm: Boolean = false) {
        val current = running ?: return
        if (current.automaticOccurrence != null) {
            when {
                outcome == SleepTimerOutcome.Cancelled && suppressAutomaticRearm ->
                    rememberScheduleRuntime(
                        suppressedOccurrence = current.automaticOccurrence,
                        replayRequiredOccurrence = null,
                    )

                outcome == SleepTimerOutcome.Expired ->
                    rememberScheduleRuntime(
                        suppressedOccurrence = null,
                        replayRequiredOccurrence = current.automaticOccurrence,
                    )
            }
        }
        running = null
        ticker?.cancel()
        ticker = null
        reconcileSensing()
        restorePlayerVolume()
        _state.value = SleepTimerState.Idle
        current.sessionId?.let { id -> repository.recordEnded(id, outcome) }
        // PRODUCT_SPEC PLAY-004 — "sleep-timer stop" is one of the moments a position must reach the server.
        // It is the moment that matters most of the list: a listener who fell asleep is not coming back to
        // press anything, and the next thing this device does may be nothing at all for eight hours.
        sessionSync.request(SyncTrigger.SleepTimerStopped)
        sessionSync.drain()
        scheduleScheduleBoundary()
    }

    /**
     * BW-SLEEP-01 — reconcile the civil eligibility window against the one timer owner.
     *
     * The schedule end is a cancellation boundary for an automatically-created timer, not a shortened
     * sleep deadline: reaching it clears the automatic timer and leaves playback running. A manual timer
     * has no [Running.automaticOccurrence], so the schedule can never cancel one the listener created.
     */
    private suspend fun reconcileSchedule(explicitPlay: Boolean = false) {
        scheduleBoundary?.cancel()
        scheduleBoundary = null

        val schedule = settings.schedule
        val automatic = running?.automaticOccurrence
        // An already-created automatic timer must still be cancelled at the window end while paused.
        // With no such timer, inactive playback is never a reason to create or schedule one.
        if (!playbackActive && automatic == null) return
        if (!schedule.enabled || schedule.start == schedule.end) {
            if (automatic != null) finish(SleepTimerOutcome.Cancelled)
            return
        }

        val now = clock.now()
        val zone = zoneProvider.current()
        val occurrence = SleepSchedulePolicy.currentOccurrence(now, zone, schedule)

        if (automatic != null) {
            if (occurrence?.id != automatic) {
                finish(SleepTimerOutcome.Cancelled)
            } else {
                scheduleScheduleBoundary(now = now, zone = zone)
            }
            return
        }

        if (running == null && occurrence != null) {
            val suppressed = schedule.suppressedOccurrence == occurrence.id
            val replayRequired = schedule.replayRequiredOccurrence == occurrence.id
            if (!suppressed && (!replayRequired || explicitPlay)) {
                if (schedule.suppressedOccurrence != null || schedule.replayRequiredOccurrence != null) {
                    rememberScheduleRuntime(suppressedOccurrence = null, replayRequiredOccurrence = null)
                }
                startTimer(
                    mode = SleepTimerMode.Fixed(settings.defaultLength),
                    automaticOccurrence = occurrence.id,
                )
                return
            }
        }
        scheduleScheduleBoundary(now = now, zone = zone)
    }

    /**
     * While playback is alive, schedule the one civil boundary that can change schedule behavior next.
     *
     * An active automatic timer watches its occurrence end, where it is cancelled without pausing playback.
     * Otherwise only the next start matters. Coroutine delay cannot wake a dead process; it exists solely
     * inside the already-running playback owner. Wall-clock/timezone changes cancel and recompute it.
     */
    private fun scheduleScheduleBoundary(
        now: java.time.Instant = clock.now(),
        zone: java.time.ZoneId = zoneProvider.current(),
    ) {
        scheduleBoundary?.cancel()
        scheduleBoundary = null
        if (!playbackActive && running?.automaticOccurrence == null) return

        val schedule = settings.schedule
        if (!schedule.enabled || schedule.start == schedule.end) return

        val automatic = running?.automaticOccurrence
        val occurrence = SleepSchedulePolicy.currentOccurrence(now, zone, schedule)
        val boundary = if (automatic != null && occurrence?.id == automatic) {
            occurrence.end
        } else {
            SleepSchedulePolicy.nextStart(now, zone, schedule)
        } ?: return

        val delayMillis = (boundary.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1L)
        scheduleBoundary = applicationScope.launch(mainDispatcher) {
            delay(delayMillis)
            // This is the boundary job itself. Clear the handle before reconciliation so reconcileSchedule
            // does not cancel its own coroutine and abort at the next suspending repository/sync call.
            scheduleBoundary = null
            reconcileSchedule()
        }
    }

    /**
     * Policy memory is updated locally before the DataStore write so continuing playback cannot race a
     * slow disk write and rearm. The repository write makes the same fact survive service/process recreation.
     */
    private suspend fun rememberScheduleRuntime(suppressedOccurrence: String?, replayRequiredOccurrence: String?) {
        settings = settings.copy(
            schedule = settings.schedule.copy(
                suppressedOccurrence = suppressedOccurrence,
                replayRequiredOccurrence = replayRequiredOccurrence,
            ),
        )
        repository.setScheduleRuntimeState(suppressedOccurrence, replayRequiredOccurrence)
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
        // ADR-0016 — the player's timeline *is* the book, so its position needs no conversion.
        return SleepTimerMath.remainingToChapterEnd(chapters, current.bookPosition(), skip)
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
    private var settings: SleepTimerSettings = SleepTimerSettings.Default

    init {
        applicationScope.launch(mainDispatcher) {
            repository.observeSettings().collect { latest ->
                settings = latest
                reconcileSensing()
                reconcileSchedule()
            }
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
