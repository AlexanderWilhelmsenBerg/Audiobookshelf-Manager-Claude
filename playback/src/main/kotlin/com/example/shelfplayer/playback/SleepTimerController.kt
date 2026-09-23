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
import com.example.shelfplayer.core.model.playback.ShakeSensitivity
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The playback-service seam used by post-expiry grace.
 *
 * Grace is listener intent, but it is not allowed to manufacture a second raw-Play path. The service therefore
 * routes it through the same resume-freshness coordinator used by MediaSession controllers and evaluates
 * [stillAuthorized] again at the final raw-player command.
 */
internal interface SleepTimerResumeOwner {
    suspend fun resume(stillAuthorized: () -> Boolean): Boolean
}

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
/*
 * Detekt LargeClass is suppressed deliberately here: this type is the single lifecycle authority for sleep-timer
 * transitions, including persistence claims, expiry, grace ownership, sensing, and schedule reconciliation.
 * Splitting those state transitions across multiple owners would weaken the generation/token invariants this
 * controller exists to enforce. Revisit only as an explicit architecture change with equivalent race coverage.
 */
@Suppress("LargeClass")
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
    private var resumeOwner: SleepTimerResumeOwner? = null
    private var chapters: List<Chapter> = emptyList()
    private var ticker: Job? = null
    private var scheduleBoundary: Job? = null
    private var shakeGraceJob: Job? = null

    /** True only while this attached player is actually producing audio. Main-dispatcher confined. */
    private var playbackActive = false

    /**
     * Whether the next transition into actual playback came from an explicit controller Play.
     *
     * The service records this at the MediaSession boundary. Passive playback resumption explicitly writes
     * false, while direct service-owned starts leave it false. It matters only after an automatic timer has
     * expired; ordinary first playback in a window remains eligible regardless of source.
     */
    private var playRequestGeneration = 0L
    private var nextPlaybackIntent: PlayIntent? = null

    private data class PlayIntent(val token: Long, val explicit: Boolean)

    /** Monotonic identity of the attached player/book owner. Every detach/book replacement invalidates claims. */
    private var playbackGeneration = 0L

    /** Monotonic identity of one logical timer transition. A stale coroutine can never regain this token. */
    private var transitionGeneration = 0L

    /** Serializes the two persisted fields that represent one automatic-schedule runtime decision. */
    private val scheduleRuntimeMutex = Mutex()

    /**
     * One state owner for every timer/grace transition.
     *
     * In particular there is no representation in which a naturally expired timer is both [TimerPhase.Running] and
     * independently grace-actionable. Suspending storage work may outlive a phase, but it may not publish a
     * later phase without revalidating the phase token and playback generation.
     */
    private sealed interface TimerPhase {
        data object Idle : TimerPhase
        data class Starting(val claim: StartClaim) : TimerPhase
        data class Running(val timer: TimerRun) : TimerPhase
        data class Expiring(val timer: TimerRun, val token: Long) : TimerPhase
        data class GraceAvailable(val grace: ShakeGrace) : TimerPhase
        data class GraceClaimed(val claim: GraceClaim) : TimerPhase
    }

    private var phase: TimerPhase = TimerPhase.Idle

    private data class TimerRun(
        val sessionId: String?,
        val mode: SleepTimerMode,
        /** Elapsed-realtime millis at which a fixed timer fires. Unused by end-of-chapter. */
        val deadlineElapsedMs: Long,
        /** How many chapter boundaries past the current one an end-of-chapter timer is aiming at. */
        val chapterSkip: Int,
        /** BW-SLEEP-01 occurrence id when schedule-created; null for every manually-created timer. */
        val automaticOccurrence: String?,
    )

    private data class StartClaim(
        val token: Long,
        val automaticOccurrence: String?,
        val player: Player,
        val playbackGeneration: Long,
        val bookId: LibraryItemId?,
    )

    private data class ShakeGrace(
        val token: Long,
        val mode: SleepTimerMode,
        val automaticOccurrence: String?,
        val expiresElapsedMs: Long,
        val player: Player,
        val playbackGeneration: Long,
        val bookId: LibraryItemId?,
    )

    private data class GraceClaim(val token: Long, val grace: ShakeGrace)

    private val running: TimerRun?
        get() = (phase as? TimerPhase.Running)?.timer

    /** Sensitivity used by the currently registered listener, so a live setting change can re-register it. */
    private var sensingSensitivity: ShakeSensitivity? = null

    /**
     * Hands the controller the player it is allowed to stop.
     *
     * Called by the service when it creates the player, and with `null` when it releases it. A timer
     * with no player cannot fire, so releasing the player ends any running timer as
     * [SleepTimerOutcome.PlaybackStopped] rather than leaving one counting down against nothing.
     */
    internal fun attach(player: Player?, resumeOwner: SleepTimerResumeOwner? = null) {
        val changed = this.player !== player
        val detachedRun = if (changed) running else null
        if (changed) {
            playbackGeneration += 1
            if (detachedRun != null) {
                leaveRunning(detachedRun)
            } else {
                invalidateTransientPhase()
            }
            playbackActive = false
            invalidatePlayRequestNow()
            scheduleBoundary?.cancel()
            scheduleBoundary = null
        }
        this.player = player
        this.resumeOwner = if (player == null) null else resumeOwner
        if (changed && detachedRun != null) {
            applicationScope.launch(mainDispatcher) {
                finalizeTimer(detachedRun, SleepTimerOutcome.PlaybackStopped)
            }
        }
    }

    /**
     * The exact MediaSession Play/Pause intent boundary.
     *
     * ResumeFreshnessPlayer invokes this before it forwards Pause or begins a Play freshness request. Because
     * the service player uses the main application looper and mainDispatcher is MainImmediate, a controller
     * command invalidates grace before any suspending freshness work can start. A duplicate Pause therefore
     * supersedes expiry authority even when raw ExoPlayer state does not change.
     */
    internal fun onControllerTransportIntent() {
        invalidateGraceAuthority()
        invalidatePlayRequestNow()
    }

    /**
     * Service-owned Play intent that deliberately bypasses ResumeFreshnessPlayer.
     *
     * Car-continuity recovery is the only existing-book raw-Play path. It is a newer playback owner just as
     * surely as a controller Play, so it must revoke post-expiry grace before issuing the raw command.
     */
    internal fun onServiceResumeIntent() {
        invalidateGraceAuthority()
        invalidatePlayRequestNow()
    }

    /**
     * Mirrors explicit movement invalidations owned by ResumeFreshnessCoordinator.
     *
     * User seeks, notification skips, Stop and media replacement supersede post-expiry resume authority. The
     * auto-rewind hook is deliberately excluded: it can be a consequence of the same pause and is not a new
     * transport command from the listener.
     */
    internal fun onResumeInvalidation(origin: ResumeInvalidation) {
        if (origin != ResumeInvalidation.AutoRewind) invalidatePlayRequestNow()
        when (origin) {
            ResumeInvalidation.Pause,
            ResumeInvalidation.Seek,
            ResumeInvalidation.NotificationSkip,
            -> invalidateGraceAuthority()

            ResumeInvalidation.Stop,
            ResumeInvalidation.MediaChanged,
            -> {
                playbackGeneration += 1
                invalidateTransientPhase()
            }

            ResumeInvalidation.AutoRewind -> Unit
        }
    }

    /**
     * BW-SLEEP-01 — records the origin of the next standard Play before the raw player sees it.
     *
     * Explicit means a controller/user Play. Passive Media3 playback resumption records false at the same
     * boundary, and service-owned automatic starts do not call this method at all.
     */
    internal suspend fun onPlayRequest(explicit: Boolean): Long = withContext(mainDispatcher) {
        playRequestGeneration += 1
        val token = playRequestGeneration
        nextPlaybackIntent = PlayIntent(token = token, explicit = explicit)
        token
    }

    internal suspend fun clearPlayRequest(token: Long) = withContext(mainDispatcher) {
        if (nextPlaybackIntent?.token == token) nextPlaybackIntent = null
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
                invalidatePlayRequestNow()
                scheduleScheduleBoundary()
                return@launch
            }
            val explicit = nextPlaybackIntent?.explicit == true
            nextPlaybackIntent = null
            when (phase) {
                is TimerPhase.GraceAvailable -> {
                    // Some service-owned path resumed without claiming grace. It now owns playback.
                    invalidateGraceAuthority()
                    reconcileSchedule(explicitPlay = explicit)
                }

                is TimerPhase.GraceClaimed,
                is TimerPhase.Starting,
                is TimerPhase.Expiring,
                -> {
                    // The transition owner will publish/reconcile after its suspension-safe commit.
                }

                TimerPhase.Idle,
                is TimerPhase.Running,
                -> reconcileSchedule(explicitPlay = explicit)
            }
        }
    }

    /** Android wall-clock/timezone observation delegates here; no platform policy leaks into this owner. */
    fun onWallClockChanged() {
        applicationScope.launch(mainDispatcher) {
            invalidateGraceIfOccurrenceEnded()
            reconcileSchedule()
        }
    }

    /**
     * The chapters of the book now playing, for SleepTimerMode.EndOfChapter.
     *
     * Supplied by PlaybackController when it starts a book rather than travelling in the playlist:
     * a forty-track book's chapter list in every MediaItem's extras would be tens of kilobytes across
     * the binder, to answer a question only this object asks.
     *
     * Changing books cancels a running timer. A timer set on one book is not a timer on the next, and
     * silently carrying it over would stop a book the listener had just chosen to start.
     */
    internal suspend fun onBookChanged(chapters: List<Chapter>) = withContext(mainDispatcher) {
        playbackGeneration += 1
        invalidatePlayRequestNow()
        val old = running
        if (old != null) {
            leaveRunning(old)
        } else {
            invalidateTransientPhase()
        }
        this@SleepTimerController.chapters = chapters
        if (old != null) finalizeTimer(old, SleepTimerOutcome.PlaybackStopped)
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
     * The Starting claim is installed before history persistence. If storage suspends, a book/player/service
     * generation change can invalidate that claim and the old coroutine can no longer install a timer later.
     */
    private suspend fun startTimer(mode: SleepTimerMode, automaticOccurrence: String?): AppResult<Unit> {
        val currentPlayer = player ?: return nothingToStop()
        if (mode is SleepTimerMode.EndOfChapter && remainingToChapterEnd(skip = 0) == null) {
            return AppResult.Failure(
                AppError.Playback(summary = "This book has no chapters to stop at."),
            )
        }

        val replaced = running
        if (replaced != null) {
            leaveRunning(replaced)
        } else {
            invalidateTransientPhase()
        }
        val claim = StartClaim(
            token = nextTransitionToken(),
            automaticOccurrence = automaticOccurrence,
            player = currentPlayer,
            playbackGeneration = playbackGeneration,
            bookId = currentPlayer.currentMediaItem?.let(MediaItems::bookIdOf),
        )
        phase = TimerPhase.Starting(claim)
        reconcileSensing()
        publish()

        if (replaced != null) {
            finalizeTimer(replaced, SleepTimerOutcome.Cancelled)
            if (!isStartClaimCurrent(claim)) return startSuperseded()
        }

        val sessionId = claim.bookId?.let { recordStarted(it, mode) }
        if (!isStartClaimCurrent(claim)) {
            closeUncommittedSession(sessionId)
            return startSuperseded()
        }

        val started = TimerRun(
            sessionId = sessionId,
            mode = mode,
            deadlineElapsedMs = deadlineFor(mode),
            chapterSkip = 0,
            automaticOccurrence = automaticOccurrence,
        )
        phase = TimerPhase.Running(started)
        record(PlaybackEvent.SleepTimerStarted, detail = remainingOf(started))
        reconcileSensing()
        startTicking()
        publish()
        scheduleScheduleBoundary()
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
     * The difference from extend shows on a timer nearly done: extending a thirty-minute timer with
     * one minute left gives thirty-one minutes; restarting gives thirty. Restarting is what somebody
     * fumbling for their phone in the dark means.
     */
    fun restart() = adjust(restart = true)

    fun cancel() {
        applicationScope.launch(mainDispatcher) {
            val current = running
            if (current != null) {
                finish(SleepTimerOutcome.Cancelled, suppressAutomaticRearm = true)
            } else {
                invalidateTransientPhase()
            }
        }
    }

    private fun adjust(restart: Boolean) {
        applicationScope.launch(mainDispatcher) {
            adjustNow(restart)
        }
    }

    private suspend fun adjustNow(restart: Boolean) {
        val current = running ?: return
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
        if (next.mode == SleepTimerMode.EndOfChapter && remainingToChapterEnd(next.chapterSkip) == null) {
            logger.info(LogCategory.Playback, "The sleep timer is already at the last chapter")
            return
        }
        phase = TimerPhase.Running(next)
        restorePlayerVolume()
        next.sessionId?.let { id -> repository.recordRestarted(id) }
        if (running != next) return
        logger.info(
            LogCategory.Playback,
            if (restart) "The sleep timer was restarted" else "The sleep timer was extended",
        )
        record(PlaybackEvent.SleepTimerExtended, detail = remainingOf(next))
        publish()
    }

    /**
     * PRODUCT_SPEC PLAY-008 / BW-SLEEP-02 — motion sensing exists only while the active timer or its bounded
     * post-expiry grace period needs it.
     *
     * The timer and persisted opt-in arrive independently. Previously the setting was checked once at timer
     * start, so a late first settings emission left that timer with no listener, and mid-timer setting changes
     * were ignored. Reconciliation is idempotent and main-dispatcher confined with [running].
     */
    private fun reconcileSensing() {
        val shouldSense = settings.shakeToRestart &&
            (phase is TimerPhase.Running || phase is TimerPhase.GraceAvailable)
        val desired = settings.shakeSensitivity
        when {
            shouldSense && (!shakes.isSensing || sensingSensitivity != desired) -> {
                if (shakes.isSensing) shakes.stop()
                sensingSensitivity = if (shakes.start(desired, ::onShake)) desired else null
            }

            !shouldSense && shakes.isSensing -> {
                shakes.stop()
                sensingSensitivity = null
            }

            !shouldSense -> sensingSensitivity = null
        }
    }

    private fun onShake() {
        applicationScope.launch(mainDispatcher) {
            when (val current = phase) {
                is TimerPhase.Running -> adjustNow(restart = true)
                is TimerPhase.GraceAvailable -> claimShakeGrace(current.grace)
                else -> Unit
            }
        }
    }

    /**
     * The first qualifying shake atomically owns the grace window before any repository/freshness suspension.
     *
     * Queued later sensor callbacks observe GraceClaimed and are no-ops. The deadline job remains alive while
     * the claim is in flight, so a slow persistence/freshness decision cannot commit after grace itself expired.
     */
    private suspend fun claimShakeGrace(grace: ShakeGrace) {
        if (!isGraceAvailable(grace)) return
        if (!isGraceWindowOpen(grace) || !isGracePlaybackOwnerCurrent(grace) || !isOccurrenceCurrent(grace)) {
            invalidateGraceAuthority()
            return
        }
        val claim = GraceClaim(token = nextTransitionToken(), grace = grace)
        phase = TimerPhase.GraceClaimed(claim)
        reconcileSensing()
        restartFromShakeGrace(claim)
    }

    /**
     * Natural expiry is already a completed timer session. A grace shake therefore records a fresh session,
     * enters the service's canonical resume-freshness path, and only then publishes a new running timer.
     */
    private suspend fun restartFromShakeGrace(claim: GraceClaim) {
        val grace = claim.grace
        if (grace.mode is SleepTimerMode.EndOfChapter && remainingToChapterEnd(skip = 0) == null) {
            abandonGraceClaim(claim)
            return
        }
        val sessionId = grace.bookId?.let { recordStarted(it, grace.mode) }
        if (!isGraceClaimCurrent(claim)) {
            closeUncommittedSession(sessionId)
            return
        }

        val owner = resumeOwner
        if (owner == null) {
            abandonGraceClaim(claim)
            closeUncommittedSession(sessionId)
            return
        }

        val resumed = owner.resume { isGraceClaimCurrent(claim) }
        if (!resumed || !isGraceClaimCurrent(claim)) {
            abandonGraceClaim(claim)
            closeUncommittedSession(sessionId)
            return
        }

        val restarted = TimerRun(
            sessionId = sessionId,
            mode = grace.mode,
            deadlineElapsedMs = deadlineFor(grace.mode),
            chapterSkip = 0,
            automaticOccurrence = grace.automaticOccurrence,
        )
        phase = TimerPhase.Running(restarted)
        shakeGraceJob?.cancel()
        shakeGraceJob = null
        record(PlaybackEvent.SleepTimerStarted, detail = remainingOf(restarted))
        reconcileSensing()
        startTicking()
        publish()
        scheduleScheduleBoundary()

        if (grace.automaticOccurrence != null) {
            // Expiry's replay marker write may still be in flight. The mutex preserves expiry -> explicit replay
            // order so a slow old write cannot restore the marker after this successful replacement.
            rememberScheduleRuntime(suppressedOccurrence = null, replayRequiredOccurrence = null)
        }
        logger.info(LogCategory.Playback, "A grace-period shake restarted playback and the sleep timer")
    }

    private fun armShakeGrace(expired: TimerRun, expiringToken: Long) {
        val expiring = phase as? TimerPhase.Expiring ?: return
        if (expiring.token != expiringToken || expiring.timer != expired) return

        val graceLength = settings.shakeGracePeriod
        val currentPlayer = player
        if (!settings.shakeToRestart || graceLength <= Duration.ZERO || currentPlayer == null) {
            phase = TimerPhase.Idle
            reconcileSensing()
            publish()
            return
        }

        val grace = ShakeGrace(
            token = nextTransitionToken(),
            mode = expired.mode,
            automaticOccurrence = expired.automaticOccurrence,
            expiresElapsedMs = clock.elapsed().inWholeMilliseconds + graceLength.inWholeMilliseconds,
            player = currentPlayer,
            playbackGeneration = playbackGeneration,
            bookId = currentPlayer.currentMediaItem?.let(MediaItems::bookIdOf),
        )
        phase = TimerPhase.GraceAvailable(grace)
        reconcileSensing()
        shakeGraceJob?.cancel()
        shakeGraceJob = applicationScope.launch(mainDispatcher) {
            delay(graceLength.inWholeMilliseconds)
            expireGraceIfDue(grace)
        }
    }

    private fun expireGraceIfDue(grace: ShakeGrace) {
        val ownsGrace = when (val current = phase) {
            is TimerPhase.GraceAvailable -> current.grace.token == grace.token
            is TimerPhase.GraceClaimed -> current.claim.grace.token == grace.token
            else -> false
        }
        if (!ownsGrace || clock.elapsed().inWholeMilliseconds < grace.expiresElapsedMs) return
        phase = TimerPhase.Idle
        shakeGraceJob = null
        reconcileSensing()
        publish()
        scheduleScheduleBoundary()
    }

    private fun invalidateGraceAuthority() {
        when (phase) {
            is TimerPhase.Expiring,
            is TimerPhase.GraceAvailable,
            is TimerPhase.GraceClaimed,
            -> {
                phase = TimerPhase.Idle
                shakeGraceJob?.cancel()
                shakeGraceJob = null
                reconcileSensing()
                publish()
                scheduleScheduleBoundary()
            }

            TimerPhase.Idle,
            is TimerPhase.Starting,
            is TimerPhase.Running,
            -> Unit
        }
    }

    private fun abandonGraceClaim(claim: GraceClaim) {
        val current = phase as? TimerPhase.GraceClaimed ?: return
        if (current.claim.token != claim.token) return
        phase = TimerPhase.Idle
        shakeGraceJob?.cancel()
        shakeGraceJob = null
        reconcileSensing()
        publish()
        scheduleScheduleBoundary()
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
        val expired = running ?: return
        val expiringToken = nextTransitionToken()

        // Retire the active session before grace exists. No suspension occurs between these writes, so a
        // shake can observe Running or GraceAvailable, never both.
        phase = TimerPhase.Expiring(expired, expiringToken)
        val expiredTicker = ticker
        _state.value = SleepTimerState.Idle
        reconcileSensing()

        logger.info(
            LogCategory.Playback,
            "The sleep timer expired and paused playback",
            LogField.Public("mode", expired.mode::class.simpleName.orEmpty()),
        )
        player?.pause()
        record(PlaybackEvent.SleepTimerExpired)
        rewindAfterStop()
        restorePlayerVolume()
        armShakeGrace(expired, expiringToken)

        // The countdown coroutine itself is not the durable-finish owner. Finish in a sibling application
        // coroutine so retiring this ticker cannot cancel DataStore/Room work, and so the old ticker cannot
        // later wake up and tick a replacement timer that a grace shake created.
        applicationScope.launch(mainDispatcher) {
            finalizeTimer(expired, SleepTimerOutcome.Expired)
        }
        expiredTicker?.cancel()
        if (ticker === expiredTicker) ticker = null
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
        leaveRunning(current)
        finalizeTimer(current, outcome, suppressAutomaticRearm)
    }

    /**
     * Removes a running timer from externally actionable state without suspending.
     *
     * Repository writes happen only in finalizeTimer after this transition, so Main-thread re-entry can never
     * observe a timer that is logically finished but still restartable merely because DataStore/Room is slow.
     */
    private fun leaveRunning(current: TimerRun) {
        val active = phase as? TimerPhase.Running ?: return
        if (active.timer != current) return
        phase = TimerPhase.Idle
        ticker?.cancel()
        ticker = null
        restorePlayerVolume()
        _state.value = SleepTimerState.Idle
        reconcileSensing()
    }

    private suspend fun finalizeTimer(
        current: TimerRun,
        outcome: SleepTimerOutcome,
        suppressAutomaticRearm: Boolean = false,
    ) {
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
        current.sessionId?.let { id -> repository.recordEnded(id, outcome) }
        sessionSync.request(SyncTrigger.SleepTimerStopped)
        sessionSync.drain()
        scheduleScheduleBoundary()
    }

    /**
     * BW-SLEEP-01 — reconcile the civil eligibility window against the one timer owner.
     *
     * The schedule end is a cancellation boundary for an automatically-created timer, not a shortened
     * sleep deadline: reaching it clears the automatic timer and leaves playback running. A manual timer
     * has no [TimerRun.automaticOccurrence], so the schedule can never cancel one the listener created.
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
            cancelAutomaticTimerIfRunning(automatic)
            return
        }

        val now = clock.now()
        val zone = zoneProvider.current()
        val occurrence = SleepSchedulePolicy.currentOccurrence(now, zone, schedule)

        if (automatic != null) {
            reconcileRunningAutomaticTimer(
                automaticOccurrence = automatic,
                currentOccurrence = occurrence,
                now = now,
                zone = zone,
            )
            return
        }

        if (phase is TimerPhase.Idle && occurrence != null && shouldArmAutomaticTimer(occurrence, explicitPlay)) {
            armAutomaticTimer(occurrence)
            return
        }
        scheduleScheduleBoundary(now = now, zone = zone)
    }

    private suspend fun cancelAutomaticTimerIfRunning(automaticOccurrence: String?) {
        if (automaticOccurrence != null) finish(SleepTimerOutcome.Cancelled)
    }

    private suspend fun reconcileRunningAutomaticTimer(
        automaticOccurrence: String,
        currentOccurrence: SleepSchedulePolicy.Occurrence?,
        now: java.time.Instant,
        zone: java.time.ZoneId,
    ) {
        if (currentOccurrence?.id != automaticOccurrence) {
            finish(SleepTimerOutcome.Cancelled)
        } else {
            scheduleScheduleBoundary(now = now, zone = zone)
        }
    }

    private fun shouldArmAutomaticTimer(occurrence: SleepSchedulePolicy.Occurrence, explicitPlay: Boolean): Boolean {
        val schedule = settings.schedule
        val suppressed = schedule.suppressedOccurrence == occurrence.id
        val replayRequired = schedule.replayRequiredOccurrence == occurrence.id
        return !suppressed && (!replayRequired || explicitPlay)
    }

    private suspend fun armAutomaticTimer(occurrence: SleepSchedulePolicy.Occurrence) {
        val ownerPlayer = player ?: return
        val ownerGeneration = playbackGeneration
        val schedule = settings.schedule
        if (schedule.suppressedOccurrence != null || schedule.replayRequiredOccurrence != null) {
            rememberScheduleRuntime(suppressedOccurrence = null, replayRequiredOccurrence = null)
        }
        if (player !== ownerPlayer || playbackGeneration != ownerGeneration) return
        val currentOccurrence = SleepSchedulePolicy.currentOccurrence(
            now = clock.now(),
            zone = zoneProvider.current(),
            settings = settings.schedule,
        )
        if (currentOccurrence?.id != occurrence.id) return
        startTimer(
            mode = SleepTimerMode.Fixed(settings.defaultLength),
            automaticOccurrence = occurrence.id,
        )
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
        scheduleRuntimeMutex.withLock {
            settings = settings.copy(
                schedule = settings.schedule.copy(
                    suppressedOccurrence = suppressedOccurrence,
                    replayRequiredOccurrence = replayRequiredOccurrence,
                ),
            )
            repository.setScheduleRuntimeState(suppressedOccurrence, replayRequiredOccurrence)
        }
    }

    private fun invalidatePlayRequestNow() {
        playRequestGeneration += 1
        nextPlaybackIntent = null
    }

    private fun nextTransitionToken(): Long {
        transitionGeneration += 1
        return transitionGeneration
    }

    private fun isStartClaimCurrent(claim: StartClaim): Boolean {
        val current = phase as? TimerPhase.Starting ?: return false
        val occurrenceCurrent = claim.automaticOccurrence?.let { currentOccurrenceId() == it } ?: true
        return current.claim.token == claim.token &&
            player === claim.player &&
            playbackGeneration == claim.playbackGeneration &&
            claim.player.currentMediaItem?.let(MediaItems::bookIdOf) == claim.bookId &&
            occurrenceCurrent
    }

    private fun isGraceAvailable(grace: ShakeGrace): Boolean =
        (phase as? TimerPhase.GraceAvailable)?.grace?.token == grace.token

    /** BW-SLEEP-02 uses a half-open grace interval: valid exactly while elapsed < deadline. */
    private fun isGraceWindowOpen(grace: ShakeGrace): Boolean =
        clock.elapsed().inWholeMilliseconds < grace.expiresElapsedMs

    private fun isGracePlaybackOwnerCurrent(grace: ShakeGrace): Boolean = player === grace.player &&
        playbackGeneration == grace.playbackGeneration &&
        grace.player.currentMediaItem?.let(MediaItems::bookIdOf) == grace.bookId

    private fun isOccurrenceCurrent(grace: ShakeGrace): Boolean =
        grace.automaticOccurrence == null || currentOccurrenceId() == grace.automaticOccurrence

    private fun isGraceClaimCurrent(claim: GraceClaim): Boolean {
        val current = phase as? TimerPhase.GraceClaimed ?: return false
        if (current.claim.token != claim.token) return false
        val grace = claim.grace
        return isGraceWindowOpen(grace) &&
            isGracePlaybackOwnerCurrent(grace) &&
            isOccurrenceCurrent(grace)
    }

    private fun currentOccurrenceId(): String? = SleepSchedulePolicy.currentOccurrence(
        now = clock.now(),
        zone = zoneProvider.current(),
        settings = settings.schedule,
    )?.id

    private fun invalidateGraceIfOccurrenceEnded() {
        val grace = when (val current = phase) {
            is TimerPhase.GraceAvailable -> current.grace
            is TimerPhase.GraceClaimed -> current.claim.grace
            else -> return
        }
        if (!isOccurrenceCurrent(grace)) invalidateGraceAuthority()
    }

    /**
     * Invalidates work that has not committed a running timer.
     *
     * A coroutine may still return from DataStore/Room afterwards, but its token is gone and the revalidation
     * helpers above force it to close any provisional history row instead of publishing stale playback state.
     */
    private fun invalidateTransientPhase() {
        when (phase) {
            is TimerPhase.Starting,
            is TimerPhase.Expiring,
            is TimerPhase.GraceAvailable,
            is TimerPhase.GraceClaimed,
            -> {
                phase = TimerPhase.Idle
                shakeGraceJob?.cancel()
                shakeGraceJob = null
                reconcileSensing()
                publish()
            }

            TimerPhase.Idle,
            is TimerPhase.Running,
            -> Unit
        }
    }

    private suspend fun closeUncommittedSession(sessionId: String?) {
        sessionId?.let { id -> repository.recordEnded(id, SleepTimerOutcome.PlaybackStopped) }
    }

    private fun nothingToStop(): AppResult<Unit> = AppResult.Failure(
        AppError.Playback(summary = "Nothing is playing, so there is nothing to stop."),
    )

    private fun startSuperseded(): AppResult<Unit> = AppResult.Failure(
        AppError.Playback(summary = "Playback changed before the sleep timer could start."),
    )

    /**
     * Volume back to full, always, on every path out of a timer.
     *
     * A cancelled fade that left the volume at `0.2` would be a listener whose book had gone quiet for
     * no reason they could see and no control that fixed it.
     */
    private fun restorePlayerVolume() {
        player?.volume = FULL_VOLUME
    }

    private fun remainingOf(current: TimerRun): Duration = when (current.mode) {
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
                if (!latest.shakeToRestart) invalidateGraceAuthority()
                invalidateGraceIfOccurrenceEnded()
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
