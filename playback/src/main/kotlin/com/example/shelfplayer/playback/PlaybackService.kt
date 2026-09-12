package com.example.shelfplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaConstants
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogEvent
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.LogLevel
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.usecase.OpenPlaybackSessionUseCase
import com.example.shelfplayer.domain.usecase.NextInSeriesUseCase
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001 — the one player and the one media session, both owned by this service.
 *
 * ### One session, structurally
 *
 * PLAY-001 says "a single media session". That is not enforced by a comment: the [ExoPlayer] and the
 * [MediaLibraryService.MediaLibrarySession] are private to this class, this class is the only `Service`
 * in the module, and the module is the only one in the build that can name either type. Nothing else in
 * the app is able to construct a second one.
 *
 * ### The browse tree
 *
 * Android Auto and Wear reach a [MediaLibraryService] through `onGetLibraryRoot`, which the default
 * implementation rejects. Wave 5 answers it: [AutoLibrary] builds four stable root destinations —
 * Continue, Series, Authors, Library — and [LibraryCallback] serves them. A car also needs the app to
 * *declare* itself, which is a manifest `meta-data` entry pointing at `automotive_app_desc.xml`; without
 * it the app is invisible in the dashboard no matter how good its tree is, which is exactly what a device
 * run found.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    internal lateinit var players: PlayerFactory

    /** PRODUCT_SPEC PLAY-002 — the chooser's half that can actually move audio: see [AudioOutputRouter]. */
    @Inject
    internal lateinit var audioOutputs: AudioOutputRouter

    /** PRODUCT_SPEC 6.4 step 6 — what to play when a book ends, or nothing. See [advanceToNextInSeries]. */
    @Inject
    internal lateinit var nextInSeries: NextInSeriesUseCase

    @Inject
    internal lateinit var playbackRepository: PlaybackRepository

    @Inject
    internal lateinit var openPlaybackSession: OpenPlaybackSessionUseCase

    @Inject
    internal lateinit var sleepTimer: SleepTimerController

    @Inject
    internal lateinit var sessionSync: SessionSyncCoordinator

    @Inject
    internal lateinit var autoRewind: AutoRewindController

    @Inject
    internal lateinit var playbackSettings: PlaybackSettingsRepository

    @Inject
    internal lateinit var history: PlaybackHistoryRepository

    @Inject
    internal lateinit var auto: AutoLibrary

    /**
     * PRODUCT_SPEC ROUTE-002 / AUTH-005 — whether the active profile is locked.
     *
     * Field-injected like everything else here: a `Service` is constructed by the framework, so Hilt fills
     * these in rather than passing them through a constructor detekt would count.
     */
    @Inject
    internal lateinit var lock: ProfileLockGuard

    @Inject
    internal lateinit var bookChanges: BookChanges

    /** PRODUCT_SPEC ROUTE-002 — what happens when a headset, a car or a speaker connects. */
    @Inject
    internal lateinit var outputDevices: OutputDeviceWatcher

    /**
     * PRODUCT_SPEC ROUTE-002 — the car's own policy, which used to be a global switch on another screen.
     *
     * The same repository `OutputDeviceWatcher` reads, so a car and a headset are governed by one set of
     * rules rather than two that can disagree.
     */
    @Inject
    internal lateinit var devices: DeviceRepository

    /** For stamping the car's `lastSeenAt` when it connects, like any other remembered device. */
    @Inject
    internal lateinit var clock: AppClock

    /** PRODUCT_SPEC PLAY-006 — the two readings that say whether the buffer preset is the right one. */
    @Inject
    internal lateinit var metrics: PlaybackMetricsRecorder

    /** PRODUCT_SPEC ROUTE-002 — so Settings can say whether a car has ever reached this app. */
    @Inject
    internal lateinit var carConnections: CarConnections

    /** PRODUCT_SPEC 11.1 — "expose custom commands for bookmark". This is what that command writes to. */
    @Inject
    internal lateinit var bookmarks: BookmarkRepository

    /**
     * PRODUCT_SPEC SYNC-002 — the pause the server has confirmed, which is service state by nature.
     *
     * Written here and only here: this listener is the one thing that sees every pause, every seek and every
     * track boundary, whether they came from the app's screen, the notification, a headset or a car. The
     * shared freshness coordinator reads the same singleton — see [ResumeBaseline].
     */
    @Inject
    internal lateinit var resumeBaseline: ResumeBaseline

    /** Issue #91 — one freshness decision shared by every standard Media3 Play surface. */
    @Inject
    internal lateinit var resumeFreshness: ResumeFreshnessCoordinator

    /** Issue #138 — debug may replace only the start position handed back by cold Media3 resumption. */
    @Inject
    internal lateinit var coldResumeStartPosition: ColdResumeStartPosition

    @Inject
    internal lateinit var logger: Logger

    @Inject
    @ApplicationScope
    internal lateinit var applicationScope: CoroutineScope

    @Inject
    @Dispatcher(ShelfDispatcher.MainImmediate)
    internal lateinit var mainDispatcher: CoroutineDispatcher

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var journal: Job? = null
    private var sleepTimerWatch: Job? = null
    private var skipWatch: Job? = null
    private var outputWatch: Job? = null

    /** The two inputs to the notification's own buttons. Main thread only, like everything that reads them. */
    private var skips: SkipIntervals = SkipIntervals.Default
    private var sleepTimerState: SleepTimerState = SleepTimerState.Idle

    /**
     * PRODUCT_SPEC PLAY-002 — which of the car's two output buttons are published, and what the headset one
     * is labelled with. Read on the main thread like the two above.
     */
    private var outputButtons: OutputButtons = OutputButtons.None

    /**
     * PRODUCT_SPEC PLAY-002 / ROUTE-002 — whether this book has actually made sound in this process.
     *
     * `mediaItemCount > 0` was standing in for "the book was being heard here", and arming breaks that
     * proxy: `DevicePolicy.ArmOnly` is the **default**, and it deliberately loads the last book paused so a
     * headset button starts it instantly. Under the old predicate, connecting earbuds armed a book, the
     * platform's media route reported those earbuds as active, and a car arriving was then refused in favour
     * of a headset that had never played — the *merely connected* case `HeadsetHold` exists to exclude.
     *
     * Set when audio starts and kept across a pause, because a book paused in a headset on the walk to the
     * car is the case worth preserving. Cleared when the book changes or the queue empties.
     */
    private var heardAudio: Boolean = false

    /**
     * PRODUCT_SPEC PLAY-002 — which headset the book was last heard in.
     *
     * ADR-0029: preservation is routing behaviour with no user-facing preference, so there is no setting
     * behind this — only the observed route.
     *
     * Stateful because the fact it holds outlives the moment it is needed; `HeadsetHold` explains why asking
     * at car-connect time is too late.
     */
    private val headsetHold = HeadsetHold()

    /** PRODUCT_SPEC PLAY-001 — how many times a failing stream may be re-prepared before the user is told. */
    private val recovery = PlaybackRecovery()

    /**
     * The service's own scope, on the main thread because every [Player] read has to be.
     *
     * Cancelled in [onDestroy]. The *final* progress write deliberately does not use it — see
     * [flushProgress].
     */
    private lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        // PRODUCT_SPEC PLAY-006 — the preset in force when this player is built. Read blocking on the
        // service's own creation rather than observed: a load control is a construction argument, and the
        // requirement is that a change applies to the *next* player rather than to this one.
        // PRODUCT_SPEC PLAY-002 / PLAY-006 — both are construction arguments: Media3 fixes the load control
        // and the audio attributes when the player is built, so both apply to the *next* player. Read once,
        // together, rather than as two blocking reads.
        val settings = runBlocking { playbackSettings.observeSettings().first() }
        val exoPlayer = players.create(buffer = settings.buffer, focus = settings.focusBehaviour)
            .also { player = it }
        exoPlayer.addListener(PlayerEvents())
        resumeFreshness.attach(exoPlayer)
        val sessionPlayer = ResumeFreshnessPlayer(
            delegate = exoPlayer,
            preparePlay = { future { handleFreshnessPlay() } },
            consumeFreshStart = resumeFreshness::consumeFreshStart,
            invalidate = resumeFreshness::invalidate,
        )
        // Issue #91 — controllers see the forwarding player; service-owned timers/sync/routing below keep
        // the raw ExoPlayer so internal atomic operations cannot recursively enter the external Play gate.
        session = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setBitmapLoader(players.bitmapLoader())
            // PRODUCT_SPEC PLAY-001 — tapping the notification opens the app. Without this the media
            // notification has no `contentIntent` at all, so a tap does nothing: a listener who reaches for
            // the notification to see where they are gets no response and no explanation.
            .apply { launchIntent()?.let(::setSessionActivity) }
            .build()
        // PRODUCT_SPEC PLAY-008 — the timer is given the player it is allowed to stop. It is a
        // singleton in this process, so it is the same object the app's UI drives.
        sleepTimer.attach(exoPlayer)
        // PRODUCT_SPEC PLAY-004 — the remote cadence reads the same player the journal does. It is given the
        // player rather than owning one, for the same reason the timer is: there is exactly one.
        sessionSync.attach(exoPlayer)
        autoRewind.attach(exoPlayer) {
            resumeFreshness.invalidate(ResumeInvalidation.AutoRewind)
        }
        // PRODUCT_SPEC PLAY-002 — after the player exists and before anything can be chosen. A preference
        // set on a released player routes nothing, so this is re-run on every player this service builds.
        audioOutputs.attach(exoPlayer)
        startJournal()
        observeSleepTimer()
        observeSkipIntervals()
        observeAudioOutputs()
        outputDevices.start(scope, DeviceActions())
        observeBrowseTreeInvalidation()
        logger.info(LogCategory.Playback, "Playback service started")
    }

    /**
     * A pending intent that opens the app, resolved from the package manager rather than from a class name.
     *
     * `:playback` cannot name the app's activity — it does not depend on `:app`, and it must not, or the
     * module boundary that keeps `MediaSession` in one place would run backwards. Asking the package manager
     * for the launch intent gets the same activity without naming it, and returns `null` on the one build
     * where there is no launcher activity at all (an instrumentation run), where a session activity would be
     * meaningless anyway.
     */
    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            // `IMMUTABLE` because nothing may add extras to it, and required from API 31 regardless.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * PRODUCT_SPEC ROUTE-002 — the three verbs the device watcher is allowed to use.
     *
     * The watcher decides *whether* and *which*; this does the Media3 part. Split that way because the
     * decision — the debounce, the policy lookup, the classification — is the part worth testing, and none
     * of it should need a player.
     *
     * `armAndPlay` is the only path in this app that starts audio with nobody pressing anything, and it is
     * reached only from a policy the user set on that specific device.
     */
    private inner class DeviceActions : OutputDeviceWatcher.Actions {

        override fun isBusy(): Boolean = (player?.mediaItemCount ?: 0) > 0

        override suspend fun arm() {
            load(startPlaying = false)
        }

        override suspend fun armAndPlay() {
            load(startPlaying = true)
        }

        /**
         * Loads the last book, optionally playing it.
         *
         * `prepare()` without `play()` is what "armed" means: the book is in the session, the notification
         * shows it, and the headset's own Play button starts it instantly — with no app to open and no book
         * to find. That is most of the value of auto-play without the part that makes noise in a quiet room.
         */
        private suspend fun load(startPlaying: Boolean) {
            val current = player ?: return
            if (current.mediaItemCount > 0) return
            val book = auto.lastPlayed() ?: return
            val queue = openQueue(book.id, startAt = null) ?: return
            current.setMediaItem(queue.item, queue.startPositionMs)
            current.prepare()
            if (startPlaying) current.play()
        }
    }

    /**
     * PRODUCT_SPEC 5.2 / ROUTE-001 — tells a connected car to forget the previous profile's tree.
     *
     * A browser fetches the browse tree once and caches it; Media3 re-asks only after
     * `notifyChildrenChanged`. Nothing called it, so after a profile switch a head unit went on showing
     * the account it had loaded first — **someone else's book titles, in a car with other people in it.**
     * That is a profile boundary rather than a stale-UI annoyance, which is why it is worth a collector.
     *
     * The child count is read rather than guessed: Media3 passes it to the browser, and a wrong number is
     * how a row renders with the previous account's length. `AutoLibrary.browsableParents` owns the id
     * list so a new tab cannot be added without being invalidated too.
     */
    private fun observeBrowseTreeInvalidation() {
        scope.launch {
            auto.invalidations().collect {
                val current = session ?: return@collect
                auto.browsableParents().forEach { parentId ->
                    current.notifyChildrenChanged(parentId, auto.children(parentId, nowPlaying()).size, null)
                }
                logger.info(LogCategory.Playback, "The active account changed; the car browse tree was invalidated")
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * PRODUCT_SPEC product priority 1 — swiping the app away does not stop a book that is actively playing.
     *
     * BookWave still journals the paused position before the task goes away, but Media3 owns the lifecycle
     * decision after that. Its default keeps an actively playing session alive and calls
     * `pauseAllPlayersAndStopSelf()` for paused or empty sessions, which guarantees `onDestroy()` releases
     * the old player/session instead of leaving a stale media item available to a later Bluetooth Play.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            flushProgress()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        flushProgress()
        outputDevices.stop()
        metrics.onReleased()
        // PRODUCT_SPEC PLAY-004 — "service shutdown callback". On the application scope inside the
        // coordinator, because `scope` is cancelled two lines below.
        sessionSync.onShutdown()
        sessionSync.attach(null)
        resumeFreshness.attach(null)
        autoRewind.attach(null)
        sleepTimer.attach(null)
        audioOutputs.detach()
        journal?.cancel()
        sleepTimerWatch?.cancel()
        skipWatch?.cancel()
        outputWatch?.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        scope.cancel()
        logger.info(LogCategory.Playback, "Playback service stopped")
        super.onDestroy()
    }

    /**
     * PRODUCT_SPEC PLAY-004 — "position is journaled locally at least every five seconds".
     *
     * A timer rather than a listener. `Player.Listener` has no "the position moved" callback —
     * `onPositionDiscontinuity` fires on a seek, not on ordinary progress — so polling is what the
     * requirement's cadence needs, and five seconds of audio is the most that can ever be lost.
     *
     * It runs while the service lives rather than only while playing. A paused position is worth exactly
     * as much as a playing one, and the write is a single row against a key that already exists.
     */
    private fun startJournal() {
        journal = scope.launch {
            while (isActive) {
                delay(JOURNAL_INTERVAL_MS)
                recordPosition()
            }
        }
    }

    private suspend fun recordPosition() {
        recordPosition(positionSnapshot() ?: return)
    }

    /**
     * The same write against a snapshot the caller already took.
     *
     * The pause path needs it: the baseline it establishes and the row it journals have to be the **same**
     * position, and reading the player twice a few milliseconds apart is how they would stop being.
     */
    private suspend fun recordPosition(snapshot: PositionSnapshot) {
        playbackRepository.recordPosition(
            bookId = snapshot.bookId,
            position = snapshot.position,
            duration = snapshot.duration,
            owner = snapshot.owner,
        )
    }

    /**
     * PRODUCT_SPEC SYNC-002 — the pause, as one ordered transaction.
     *
     * ### The four steps, and why they are one coroutine
     *
     *  1. **capture** the exact position the player came to rest at, once, on the main thread;
     *  2. **journal** that same position locally, so nothing is lost if the process dies here;
     *  3. **await** the session sync for it — not `request`, which returns before the server has answered;
     *  4. and the sync itself **promotes** the baseline when the server took that position.
     *
     * Step 3 is the whole reason this is a suspending sequence rather than three launches. The freshness
     * check before the next Play decides from an *acknowledged* pause, and "acknowledged" is a fact that
     * only exists after the answer arrives (see [ResumeBaseline]). Launching the sync separately and moving
     * on is what four previous versions did, and each of them then had to guess at the acknowledgement from
     * something local — a timestamp, a stopwatch, a persistence flag — and each guess was wrong on a device.
     *
     * ### On the application scope, on the main dispatcher
     *
     * The same reasoning as [flushProgress]: a pause is often the last thing that happens before the
     * service is torn down, and [scope] is cancelled in `onDestroy`. Awaiting a network round trip on a
     * scope that is about to die would abandon the acknowledgement at exactly the moment it matters most.
     * Nothing is *lost* by that — the outbox row is written before the send, so the drain carries it up
     * later — but the pause would stay unacknowledged and the next Play would decline to ask the server.
     *
     * `MainImmediate` means the capture happens synchronously inside this callback rather than on a later
     * dispatch, so no other player event can slip between the pause and the position it is recorded at.
     *
     * ### This fires on a rebuffer too, and that is harmless
     *
     * `isPlaying` goes false whenever the player stalls, not only when somebody pressed pause. Such a
     * baseline describes a real position that really was uploaded, and the `isPlaying = true` that follows
     * the stall invalidates it. Capturing here rather than from `playWhenReady` — which *is* intent — is
     * deliberate: it puts the capture in the same callback and the same coroutine as the sync that
     * acknowledges it, so no dispatch order can put the acknowledgement before the thing it acknowledges.
     */
    private suspend fun onCameToRest() {
        val snapshot = positionSnapshot()
        if (snapshot == null) {
            // Nothing worth a baseline — a player that has not started, or the single-file fallback whose
            // offsets are not book positions at all (R-61). The next Play has no acknowledged pause and
            // therefore resumes locally, which is the correct answer for a position we cannot vouch for.
            resumeBaseline.onLocalMove()
            return
        }
        val generation = resumeBaseline.onPaused(snapshot.bookId, snapshot.position)
        logger.debug(
            LogCategory.Playback,
            "The player came to rest, pending the server's acknowledgement",
            LogField.Millis("position", snapshot.position.inWholeMilliseconds),
            LogField.Public("generation", generation.toString()),
        )
        recordPosition(snapshot)
        sessionSync.sync(SyncTrigger.Paused)
    }

    /**
     * The last write, on a scope that outlives this service.
     *
     * [scope] is cancelled in `onDestroy`, so a `launch` on it would be cancelled before the row reached
     * Room — precisely the moment the position matters most. The application scope is PRODUCT_SPEC
     * 22.10's sanctioned alternative to `GlobalScope`, and this is the case it exists for.
     *
     * **PRODUCT_SPEC 6.5 — outliving the service is exactly why the owner has to travel with it.** A launch
     * on a scope that survives this object is a launch that can still be pending when the app switches
     * profile, and a write that resolved the account at the far end of it would file the departing
     * listener's position against the arriving one. [PositionSnapshot.owner] is read here, on this side of
     * the suspension, from the loaded book's own extras.
     */
    private fun flushProgress() {
        val snapshot = positionSnapshot() ?: return
        applicationScope.launch {
            playbackRepository.recordPosition(
                bookId = snapshot.bookId,
                position = snapshot.position,
                duration = snapshot.duration,
                owner = snapshot.owner,
            )
        }
    }

    /**
     * What is playing and where, read on the main thread because every [Player] property must be.
     *
     * `null` when there is nothing worth writing. The guard that matters is the last one: a player that
     * is still on the first item at position zero has not started, and writing that would move a
     * listener back to the beginning of a book they were part-way through (product priority 2).
     */
    private fun positionSnapshot(): PositionSnapshot? {
        val current = player ?: return null
        val item = current.currentMediaItem ?: return null
        val positionMs = current.currentPosition
        /*
         * Two reasons there is nothing worth writing, in one condition because they are one idea.
         *
         * A position of zero means the book has not started, and writing it would move a listener back to
         * the beginning of one they were part-way through.
         *
         * `docs/risks.md` R-61 is the second. `BookMediaSourceFactory` falls back to playing the first file
         * when a track's length is unknown and `TrackDurations` could not recover it; `currentPosition` is
         * then an offset into *that file*, and writing it here would replace the book's stored progress with
         * it — a 34-hour book reduced to minutes on the next read. Product priority 2 is *do not lose
         * progress*, and declining is how this obeys it.
         *
         * Silent rather than logged: this runs every few seconds, and the factory already warns once per
         * session with the reason. A warning per tick would bury it.
         */
        if (!isPersistablePlaybackPosition(positionMs, MediaItems.isSingleFileFallback(item))) return null
        // ADR-0016 — the player's timeline is the book, so the position and the duration are read straight
        // off it. There is no per-file arithmetic left to get wrong.
        return PositionSnapshot(
            bookId = MediaItems.bookIdOf(item),
            position = current.bookPosition(),
            duration = current.bookDuration(),
            // PRODUCT_SPEC 6.5 — read here, with the position, rather than resolved at the far end of the
            // write. The two facts are one observation: this position, of this account's book.
            owner = MediaItems.ownerOf(item),
        )
    }

    private data class PositionSnapshot(
        val bookId: LibraryItemId,
        val position: Duration,
        val duration: Duration,
        /** PRODUCT_SPEC 6.5 — whose book this is; `null` for an item this app did not build. */
        val owner: ProfileId?,
    )

    /**
     * Issue #91 — handles one standard Play after [ResumeFreshnessPlayer] intercepted it.
     *
     * No controller identity is needed here: every controller that can send standard Play reaches the same
     * forwarding player, which is the point of putting the decision at the Media3 boundary rather than in a
     * screen. A superseded request does nothing; the newer seek/Pause/book/profile command already owns the
     * player.
     */
    private suspend fun handleFreshnessPlay() {
        when (val prepared = resumeFreshness.preparePlay()) {
            ResumePlayPreparation.Bypass -> resumeLoadedCurrent()
            ResumePlayPreparation.Superseded -> Unit
            is ResumePlayPreparation.Ready -> applyFreshnessPlan(prepared.plan)
        }
    }

    private suspend fun applyFreshnessPlan(plan: ResumeFreshnessPlan) {
        when (val decision = plan.decision) {
            is ResumeFreshnessDecision.Current -> {
                resumeFreshness.withCurrentPlan(plan) {
                    recordFreshnessCheck(plan)
                    resumeLoadedCurrent()
                }
            }
            is ResumeFreshnessDecision.Adopt -> {
                val outcome = resumeFreshness.withCurrentPlan(plan) {
                    recordFreshnessCheck(plan)
                    resumeAt(plan, decision.position)
                } ?: return
                recordRemoteProgress(plan, outcome)
            }
        }
    }

    /** The old direct Play behaviour, now used only after the shared freshness decision says to stay local. */
    private suspend fun resumeLoadedCurrent() = withContext(mainDispatcher) {
        val current = player ?: return@withContext
        if (current.mediaItemCount == 0) return@withContext
        if (current.playbackState == Player.STATE_IDLE || current.playerError != null) current.prepare()
        current.play()
    }

    /** Records the REST check result independently of whether a later adopted seek succeeds. */
    private fun recordFreshnessCheck(plan: ResumeFreshnessPlan) {
        val event = plan.serverCheckHistoryEvent() ?: return
        applicationScope.launch {
            history.record(
                bookId = plan.bookId,
                event = event,
                from = null,
                to = plan.localPosition,
                owner = plan.profileId,
            )
        }
    }

    /** Records an undoable remote movement only after the raw player confirmed it was actually applied. */
    private fun recordRemoteProgress(plan: ResumeFreshnessPlan, outcome: ResumeOutcome) {
        val event = plan.remoteProgressHistoryEvent(outcome) ?: return
        val decision = plan.decision as? ResumeFreshnessDecision.Adopt ?: return
        applicationScope.launch {
            history.record(
                bookId = plan.bookId,
                event = event,
                from = plan.localPosition,
                to = decision.position,
                owner = plan.profileId,
            )
        }
    }

    /**
     * PRODUCT_SPEC SYNC-002 — the whole adopt-a-remote-position operation, on the player this service owns.
     *
     * ### Why the app cannot do this for itself
     *
     * It used to try. `PlaybackController` seeked through `MediaController` and then read the position back
     * off the same proxy a second later to see whether it had taken — which cannot distinguish a seek the
     * player dropped from one it honoured, because both answers come from the session rather than from the
     * player (see `AtomicResume.kt`). Here there is no proxy: [player] *is* the `ExoPlayer`, the discontinuity
     * is its own, and the answer that goes back over the binder is a fact rather than an assumption.
     *
     * Everything runs on [mainDispatcher] because every `Player` read and write must.
     */
    private suspend fun resumeAt(plan: ResumeFreshnessPlan, target: Duration): ResumeOutcome =
        withContext(mainDispatcher) {
            val current = player ?: return@withContext ResumeOutcome.NotLoaded
            val outcome = OwnedPlayer(current, plan).seekAndResume(
                bookId = plan.bookId,
                target = target,
                tolerance = ADOPT_TOLERANCE,
                timeout = SEEK_CONFIRM_TIMEOUT,
            )
            // Where the seek landed is logged by `OwnedPlayer.seekAndAwait`, which is the only place that holds
            // it *before* audio starts. Reading the position again here would report the target plus however
            // much has played since, which is the kind of confident wrong number R-90 was made of.
            logger.info(
                LogCategory.Playback,
                if (outcome == ResumeOutcome.Resumed) {
                    "Resumed on a position adopted from another device"
                } else {
                    "A position adopted from another device did not take"
                },
                LogField.Millis("target", target.inWholeMilliseconds),
                LogField.Public("outcome", outcome.name),
            )
            outcome
        }

    /**
     * [ResumeTarget] over the service's own [ExoPlayer]. Main thread only, like its subject.
     *
     * Thin on purpose: the ordering it is driven by is asserted against a fake in `AtomicResumeTest`, and
     * everything here is a single Media3 call so that there is as little as possible that only a device can
     * exercise.
     */
    private inner class OwnedPlayer(private val media: ExoPlayer, private val plan: ResumeFreshnessPlan) :
        ResumeTarget {

        override fun loadedBookId(): LibraryItemId? =
            media.currentMediaItem?.takeIf { media.mediaItemCount > 0 }?.let(MediaItems::bookIdOf)

        override fun needsPreparing(): Boolean = media.playbackState == Player.STATE_IDLE || media.playerError != null

        override fun prepare() = media.prepare()

        /**
         * Seeks, and waits for **this player** to say where it ended up.
         *
         * The listener is attached before the seek is issued, because `ExoPlayer` dispatches the
         * discontinuity synchronously from `seekTo` on this same thread: registering afterwards would
         * reliably miss it.
         *
         * If no discontinuity arrives inside [timeout] the player's live position is read instead. That is
         * not the discredited controller re-read — this is the player itself, on its own thread — and it
         * covers the one case Media3 does not promise a callback for: a seek to where the player already
         * is. Either way the caller compares the answer against the target before any audio starts.
         */
        override suspend fun seekAndAwait(position: Duration, timeout: Duration): Duration? {
            val landed = CompletableDeferred<Duration>()
            val listener = object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK &&
                        reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                    ) {
                        return
                    }
                    landed.complete(newPosition.positionMs.coerceAtLeast(0).milliseconds)
                }
            }
            media.addListener(listener)
            return try {
                media.seekTo(position.inWholeMilliseconds.coerceAtLeast(0))
                val reported = withTimeoutOrNull(timeout) { landed.await() }
                (reported ?: media.bookPosition()).also { where ->
                    logger.debug(
                        LogCategory.Playback,
                        "The player reported where a seek landed",
                        LogField.Millis("target", position.inWholeMilliseconds),
                        LogField.Millis("landed", where.inWholeMilliseconds),
                        LogField.Public("source", if (reported == null) "position" else "discontinuity"),
                    )
                }
            } finally {
                // In the `finally` rather than on cancellation: this block runs in this coroutine's
                // context, which is the main dispatcher, and `removeListener` off the main thread is a
                // Media3 violation whatever the reason for unwinding.
                media.removeListener(listener)
            }
        }

        override suspend fun playIfCurrent(): Boolean = resumeFreshness.withCurrentPlan(plan, requireBaseline = false) {
            media.play()
            true
        } ?: false
    }

    /**
     * The moments a five-second timer would round off.
     *
     * Pausing, crossing into another track and reaching the end of a book are all points a listener
     * expects to be remembered exactly, and each is the last thing that happens before the service may
     * be torn down.
     */
    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The listening-time interval closes before the sync reads it, or a pause would report less
            // listening than happened.
            sessionSync.onPlayingChanged(isPlaying)
            if (isPlaying) {
                // Audio is coming out, so whatever went wrong is over and the next failure starts from one.
                recovery.onPlaying()
                // ROUTE-002 — the first proof this book is being heard, which is what the headset hold needs.
                if (!heardAudio) {
                    heardAudio = true
                    // PLAY-002 — before the hold is fed, so the hold then remembers the headset this just
                    // pinned rather than the one the selection disagreed with.
                    startInRoutedHeadset()
                    feedHeadsetHold()
                }
                // PRODUCT_SPEC SYNC-002 — the book is moving again, so the position it was resting at is no
                // longer a description of where this device is. See `ResumeBaseline.onLocalMove`.
                resumeBaseline.onLocalMove()
                // PRODUCT_SPEC PLAY-009 — before anything else on the resume path: a rewind that landed after
                // playback had started would be audible as a stutter.
                autoRewind.onResumed()
            } else {
                // PRODUCT_SPEC SYNC-002 — capture, journal, then *await* the sync that acknowledges it. See
                // `onCameToRest` for why the awaiting is the point, and why it is not on this service's
                // scope.
                applicationScope.launch(mainDispatcher) { onCameToRest() }
            }
        }

        /**
         * PRODUCT_SPEC PLAY-009 — why playback stopped, which decides whether a rewind may follow.
         *
         * `onIsPlayingChanged` does not carry a reason, and the reason is the requirement: an audio-focus loss
         * is not a pause the listener asked for, and rewinding out of one would replay ten seconds every time
         * a satnav spoke.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // PRODUCT_SPEC PLAY-003 — play and pause, in the book's history. The device report asked for
            // them: *"Play start and play pause doesn't show."*
            //
            // Recorded from `playWhenReady` rather than from `isPlaying`, and that is the whole trick.
            // `isPlaying` goes false every time the player buffers, so a book on a slow connection would
            // write a pause and a play every few seconds and bury everything else in the list. This flag
            // is *intent*: it changes when somebody presses something, or when audio focus is taken away.
            //
            // Here rather than in `PlaybackController`, because the service outlives the app — a pause from
            // a headset with the app closed is exactly the one worth having. One recorder, so no event can
            // be written twice.
            recordTransport(if (playWhenReady) PlaybackEvent.Play else PlaybackEvent.Pause)
            /*
             * PRODUCT_SPEC 14.4 — and the only line that can witness a steering wheel.
             *
             * A review caught §2.9 inferring the wheel from `The player changed state`, which a working
             * wheel need not produce: play/pause keeps the player in `STATE_READY`, volume touches no
             * playback state at all, and next/previous are no-ops on the one-item queue a car selection
             * builds. The step would have reported a working wheel as unsupported — the mirror image of
             * the false pass it had just been fixed for.
             *
             * `reason` is worth logging rather than `playWhenReady` alone because it separates a person
             * from the system: `audioFocusLoss` and `becomingNoisy` mean nobody pressed anything. A
             * boolean would have said neither.
             *
             * It does **not** say who pressed, and §2.9 briefly claimed it did (R-76). Media3 collapses
             * that before this listener sees it: a controller's play/pause reaches `MediaSession`, which
             * forwards it to the local player as `play()` → `setPlayWhenReady(true)`, and `ExoPlayerImpl`
             * hard-codes that call to `PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST`. A wheel, a headset
             * button and the app's own UI are therefore indistinguishable here. `REMOTE` belongs to a
             * *remote* player — CastPlayer, or a controller observing a session — and this listener is on
             * the local ExoPlayer, so it cannot appear. The device script isolates the log window and
             * tells the tester to touch nothing else; that, not the reason, is the attribution.
             */
            logger.debug(
                LogCategory.Playback,
                "Playback was asked to change",
                LogField.Public("playWhenReady", playWhenReady.toString()),
                LogField.Public("reason", playWhenReadyReason(reason)),
            )
            if (playWhenReady) return
            // `REMOTE` cannot occur while this listener is on the local ExoPlayer (R-76); it stays in the
            // condition so the *intent* — a person asked, from wherever — survives if the player is ever
            // wrapped or replaced by a remote one, which is when the reason would start appearing.
            autoRewind.onPaused(
                wasUserInitiated = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
        }

        /**
         * A track boundary. `ChapterChanged` is reported by `PlaybackController`, which is the only place that
         * holds the chapter list — the service deliberately does not, because a long book's chapters in every
         * `MediaItem`'s extras would be tens of kilobytes across the binder to answer one question.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            recovery.onBookChanged()
            // PRODUCT_SPEC PLAY-006 — the startup stopwatch starts here rather than at `prepare()`, because
            // this fires for every book including one started from a car or by a media button, and the wait
            // to hear a book is the wait for *that* book.
            if (mediaItem != null) metrics.onItemPrepared()
            // PRODUCT_SPEC PLAY-002 — a book arriving is the other half of what the headset hold watches;
            // already-connected earbuds raise no device event, so without this the common order never
            // registers a headset to preserve. A *new* book has been heard nowhere yet.
            heardAudio = false
            feedHeadsetHold()
            // PRODUCT_SPEC SYNC-002 — a baseline is per book and per position, and this is both changing.
            resumeBaseline.onBookClosed()
            // Record before the sync that follows it, so the row the sync uploads is this item's own.
            scope.launch {
                recordPosition()
                sessionSync.request(SyncTrigger.TrackChanged)
            }
        }

        /**
         * PRODUCT_SPEC PLAY-004 — "seek completion".
         *
         * `onPositionDiscontinuity` with `DISCONTINUITY_REASON_SEEK_ADJUSTMENT` or `_SEEK` is the seek having
         * landed, which is the moment worth syncing: syncing when the seek was *requested* would send the
         * position the listener left rather than the one they chose.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                // PRODUCT_SPEC PLAY-009 — "rewind is not applied after a user seek". A listener who chose a
                // position chose it; moving it afterwards is the app overruling them.
                autoRewind.onSeeked()
                // PRODUCT_SPEC SYNC-002 — the book has moved, so whatever pause was acknowledged no longer
                // describes it. Including the adopting seek itself: the pause it belonged to is over.
                resumeBaseline.onLocalMove()
                // Record before the sync that follows it, so the row the sync uploads is the seek's own.
                scope.launch {
                    recordPosition()
                    sessionSync.request(SyncTrigger.SeekCompleted)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            /*
             * PRODUCT_SPEC 14.4 — the other half of the evidence a car tap needs.
             *
             * `A controller asked to set what plays` says this service answered. Only the player can say
             * whether the answer was accepted, and until now it said nothing: a queue that was set and then
             * never prepared, and a queue that was never set at all, produced identical logs — which is
             * what left a head unit's loading message undiagnosable.
             *
             * A state name is a Media3 constant. It names no book (14.5), and there are four of them, so
             * this is not a line that can flood a log the way a position or a buffer event could.
             */
            logger.debug(
                LogCategory.Playback,
                "The player changed state",
                LogField.Public("state", stateName(playbackState)),
            )
            // PRODUCT_SPEC PLAY-006 — the recorder decides what counts; this only reports what happened.
            when (playbackState) {
                Player.STATE_BUFFERING -> metrics.onBuffering()
                Player.STATE_READY -> metrics.onReady()
                else -> Unit
            }
            if (playbackState == Player.STATE_ENDED) {
                // PRODUCT_SPEC SYNC-002 — the book is over; nothing about its last pause is still true.
                resumeBaseline.onBookClosed()
                // Record before the sync that follows it, so the row the sync uploads is the final one.
                scope.launch {
                    recordPosition()
                    sessionSync.request(SyncTrigger.BookChanged)
                }
                // PRODUCT_SPEC 6.4 step 6 — after the position is journalled and the sync is asked for,
                // never before: the book that just ended has to be recorded as finished whether or not
                // anything follows it, and an advance that failed must not have cost the last write.
                scope.launch { advanceToNextInSeries() }
            }
        }

        /**
         * PRODUCT_SPEC 14.4 / 14.5 / PLAY-001 — the error is recorded, the book it happened to is not, and
         * the player is put back on its feet.
         *
         * `errorCodeName` is a Media3 constant and says what went wrong. The exception's message can
         * contain the failing URL, which is a path on someone's private server, so it is deliberately
         * not logged.
         *
         * **The recovery is the important half.** An errored player is `STATE_IDLE`, and an idle player
         * ignores `play()` and `seekTo()` — a device run found a book that stopped mid-seek and then could
         * not be restarted at all. `prepare()` is the only way out, so a transient error takes it, a few
         * times, with a delay. See [PlaybackRecovery] for what counts as transient and why the count is
         * bounded.
         */
        override fun onPlayerError(error: PlaybackException) {
            val retryIn = recovery.onError(error)
            logger.warn(
                LogCategory.Playback,
                if (retryIn == null) "Playback stopped on an error" else "Playback hit an error and will retry",
                LogField.Public("errorCode", error.errorCodeName),
                LogField.Count("attempt", recovery.attemptCount),
            )
            scope.launch { recordPosition() }
            reportFailureToControllers(error, willRetry = retryIn != null)
            if (retryIn == null) return
            scope.launch {
                delay(retryIn)
                val current = player ?: return@launch
                if (current.mediaItemCount == 0) return@launch
                // `playWhenReady` survives an error, so re-preparing resumes a book that was playing and
                // leaves a paused one paused. Nothing here decides to start playback that was not running.
                current.prepare()
            }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-001 / PLAY-007 / PLAY-008 — everything in the notification that is ours.
     *
     * Three buttons, republished whenever either input changes:
     *
     *  - **back and forward**, in the slots Media3 would otherwise fill with skip-to-previous and
     *    skip-to-next. See [NotificationButtons] for why that substitution is not optional.
     *  - **the sleep timer**, carrying its countdown as its display name and extending the timer when
     *    pressed — both of PLAY-008's notification requirements in one control. It disappears when no timer
     *    is running rather than sitting there greyed out: a control that does nothing is a control a
     *    half-asleep listener will press anyway.
     *
     * The skip intervals are observed rather than read once, so a change in Settings reaches the
     * notification immediately. The in-app buttons and the notification's must never disagree about how far
     * they jump — that is the whole reason `SkipControls` bundles a label with its callbacks.
     */
    private fun observeSleepTimer() {
        sleepTimerWatch = scope.launch {
            sleepTimer.state.collect { timer ->
                sleepTimerState = timer
                publishMediaButtons()
            }
        }
    }

    private fun observeSkipIntervals() {
        skipWatch = scope.launch {
            playbackSettings.observeSettings().collect { settings ->
                skips = settings.skips
                // PRODUCT_SPEC PLAY-002 — read here rather than in its own collector: it comes off the same
                // flow, and a second `observeSettings()` would be a second cold DataStore read of the same
                // bytes on every write to any playback setting.
                publishMediaButtons()
            }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-002 — keeps the car's two output buttons matching what is connected.
     *
     * Both flows, because the headset button reports the **route** and falls back to the **choice**, and
     * either can move without the other: a headset disconnecting changes the route with no selection
     * involved, and choosing an output changes the selection before the platform has acted on it.
     *
     * This is also the only place [headsetHold] is fed, and it is fed on **every** emission rather than only
     * on a change — the hold is about which headset was in use a moment ago, so it has to see every moment.
     */
    private fun observeAudioOutputs() {
        outputWatch = scope.launch {
            combine(audioOutputs.outputs, audioOutputs.selectedId, ::Pair).collect { (outputs, selected) ->
                feedHeadsetHold(outputs, selected)
                // Republishing on every emission would rewrite the notification for a device change that
                // does not touch either button, and Media3 pushes each set to every controller.
                republishOutputButtons()
            }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-002 — the one place [headsetHold] is fed, from **two** triggers.
     *
     * Output changes alone are not enough. Earbuds that are already connected when a book is started produce
     * no device event, so a collector watching only the output flows would never see the book arrive: the
     * common order — connect earbuds, then press play — left nothing remembered and the car took the audio
     * anyway. Loading a book is therefore the second trigger, and it is why this is a function rather than
     * two lines inside the collector.
     *
     * Called on [mainDispatcher] from both, which is where Media3 requires the player read.
     */
    private fun feedHeadsetHold(
        outputs: List<AudioOutput> = audioOutputs.outputs.value,
        selectedId: String? = audioOutputs.selectedId.value,
    ) {
        val loaded = (player?.mediaItemCount ?: 0) > 0
        headsetHold.observe(outputs, selectedId, hasMedia = loaded && heardAudio)
    }

    /**
     * Recomputes the two output buttons and rewrites the notification only if they moved.
     *
     * Called from the output collector and from both halves of a car binding, because a car is a reason the
     * car button appears without any audio device having changed.
     */
    private fun republishOutputButtons() {
        val outputs = audioOutputs.outputs.value
        val next = AudioOutputRoles.buttons(
            outputs = outputs,
            selectedId = audioOutputs.selectedId.value,
            carConnected = carConnections.isConnected(),
        )
        if (next == outputButtons) return
        outputButtons = next
        logOutputState(outputs, next)
        publishMediaButtons()
    }

    /**
     * PRODUCT_SPEC PLAY-002 / 14.5 — the line that makes a drive conclusive instead of suggestive.
     *
     * Two device runs reported the Car action not lighting and neither could say **why**, because nothing
     * recorded the inputs to that decision. The only routing log was in `AudioOutputRouter.apply`, which
     * fires on an explicit selection and so never during the case being investigated. Four different causes
     * produce the same photograph — the platform reported no route, it reported one BookWave classifies as
     * a speaker or as unknown, the API is below 33 so no route is reported at all, or the host declined to
     * draw the lit glyph — and this line separates them.
     *
     * Device **kinds** only, never advertised names: a headset's product name is the user's, not a
     * diagnostic (14.5, and priority 7 keeps private self-hosted data out of reports).
     */
    private fun logOutputState(outputs: List<AudioOutput>, state: OutputButtons) {
        logger.info(
            LogCategory.Playback,
            "The car output actions were recomputed",
            LogField.Public("api", Build.VERSION.SDK_INT.toString()),
            LogField.Public("carBound", carConnections.isConnected().toString()),
            LogField.Public("routeKnown", outputs.any(AudioOutput::isActive).toString()),
            LogField.Public(
                "outputs",
                outputs.joinToString("+") { output ->
                    "${output.role}${if (output.isActive) "*" else ""}"
                },
            ),
            LogField.Public("onCar", state.onCar.toString()),
            LogField.Public("onHeadset", state.onHeadset.toString()),
        )
    }

    /**
     * PRODUCT_SPEC PLAY-002 — *if play comes from a headset, start in that headset*, as far as that is knowable.
     *
     * Runs at the first proof a book is being heard, which is the one moment the route is settled and the
     * decision costs the play path nothing. [AudioOutputRoles.startTarget] holds the policy and the reason
     * the pressing device itself cannot be identified.
     *
     * Deliberately **not** folded into [HeadsetHold]. That class answers "which headset was the book in a
     * moment ago, so a car arriving does not steal it"; its trigger is a car binding and its output is a
     * memory. This is a different trigger and an immediate selection, and keeping them apart leaves
     * `HeadsetHold`'s already-subtle release state machine untouched. They compose as they stand: this runs
     * first, so the hold then observes the headset just pinned.
     */
    private fun startInRoutedHeadset() {
        val target = AudioOutputRoles.startTarget(
            outputs = audioOutputs.outputs.value,
            selectedId = audioOutputs.selectedId.value,
            carConnected = carConnections.isConnected(),
        ) ?: return
        logger.info(
            LogCategory.Playback,
            "A book started in the headset already carrying the route",
            LogField.Public("kind", target.substringBefore(':')),
        )
        audioOutputs.select(target)
    }

    /**
     * PRODUCT_SPEC PLAY-002 — *Keep sound in the headset*, applied at the one moment it means anything.
     *
     * A no-op unless the book was already coming out of a headset that is still connected. The log line
     * names the *kind* rather than the headset's advertised name (14.5).
     *
     * The queue is re-checked **here** rather than trusted from the memory. The collector that maintains it
     * runs on output changes, and `PlaybackController.stop()` empties the queue without touching either
     * output flow — so a memory set while a book was playing can outlive the book, and a car arriving would
     * pin the route to earbuds nobody is listening to.
     */
    private fun holdHeadsetAgainstCar() {
        if ((player?.mediaItemCount ?: 0) == 0) {
            headsetHold.forget()
            return
        }
        val hold = headsetHold.holdOnCarArrival(audioOutputs.outputs.value) ?: return
        logger.info(
            LogCategory.Playback,
            "A car connected and the book was held in the headset",
            LogField.Public("kind", hold.substringBefore(':')),
        )
        audioOutputs.select(hold)
    }

    /**
     * PRODUCT_SPEC PLAY-001 — say why the book stopped, to whoever is listening.
     *
     * A device run found the car's player silent about everything; §8 answered the routing half and this is
     * the other. A driver whose self-hosted server has expired its credentials, or which is simply not
     * reachable from the car's network, otherwise sees a book that does not start and no explanation.
     *
     * `sendError` reaches every connected controller, which is right: the phone notification benefits from
     * the same sentence, and there is no per-controller version of this that a head unit reads.
     *
     * The credential case carries a **labelled action**, because Media3 has the two legacy extras for it
     * and a message with a way out is worth more than a message. It opens the app rather than pretending
     * a head unit can host a sign-in — see the string's own comment.
     *
     * [PlaybackFailureReport] holds the decision and the reason a retry stays quiet.
     */
    private fun reportFailureToControllers(error: PlaybackException, willRetry: Boolean) {
        val current = session ?: return
        val report = PlaybackFailureReport.of(
            errorCode = error.errorCode,
            httpStatus = error.httpResponseCode(),
            localFile = error.isLocalFileFailure(),
            willRetry = willRetry,
        ) ?: return
        val message = messageFor(report)
        val extras = resolutionExtras(report)
        logger.warn(
            LogCategory.Playback,
            "The car was told why the book stopped",
            LogField.Public("kind", report.message.name),
            LogField.Public("hasAction", extras.isEmpty.not().toString()),
        )
        current.sendError(SessionError(report.code, getString(message), extras))
    }

    /**
     * The sentence for a report. Both failure paths draw from the same four.
     *
     * The seek-slot reservation extras used to be published from here, set to `false`. That was removed as
     * measured dead code: `MediaSessionLegacyStub` recomputes both keys from the custom layout on every
     * button update and overwrites whatever the app put there, and because PLAY-007's skip buttons occupy
     * `SLOT_BACK` and `SLOT_FORWARD` it computes exactly the `false` this was setting. The decision is
     * still the right one — a book is one timeline window (ADR-0016), so reserving those positions would
     * invite a host to blank the ones the skip buttons live in — it simply is not this app's to make.
     */
    private fun messageFor(report: PlaybackFailureReport.Report): Int = when (report.message) {
        PlaybackFailureReport.Message.CredentialsExpired -> R.string.car_error_credentials_expired
        PlaybackFailureReport.Message.ServerUnreachable -> R.string.car_error_server_unreachable
        PlaybackFailureReport.Message.ServerCannotDeliver -> R.string.car_error_server_cannot_deliver
        PlaybackFailureReport.Message.FileNotPlayable -> R.string.car_error_file_not_playable
    }

    /**
     * The labelled action, for the one failure a person can act on.
     *
     * Empty for everything else: a button that cannot help is worse than no button, and a head unit draws
     * whatever it is given.
     */
    private fun resolutionExtras(report: PlaybackFailureReport.Report): Bundle = Bundle().apply {
        if (!report.isCredentialFailure) return@apply
        launchIntent()?.let { intent ->
            putString(
                MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                getString(R.string.car_error_sign_in_action),
            )
            putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, intent)
        }
    }

    /**
     * PRODUCT_SPEC PLAY-001 — the session-opening half of [reportFailureToControllers].
     *
     * Shares the sentence and the labelled action, and differs only in what it classifies from: a typed
     * `AppError` rather than a `PlaybackException`, because on this path nothing ever reached the player.
     */
    private fun reportSessionFailureToControllers(error: AppError) {
        val current = session ?: return
        val report = PlaybackFailureReport.ofSessionFailure(error) ?: return
        current.sendError(SessionError(report.code, getString(messageFor(report)), resolutionExtras(report)))
    }

    /**
     * PRODUCT_SPEC PLAY-002 — pushes the button set, including to the car.
     *
     * The second publish is what reaches Android Auto and is not optional; [MediaButtonPublishing] holds
     * the measured reason, and `MediaButtonPublishingTest` fails if it is dropped.
     */
    private fun publishMediaButtons() {
        val current = session ?: return
        MediaButtonPublishing.publish(
            buttons = mediaButtons(),
            toAllControllers = current::setMediaButtonPreferences,
            toNotificationController = current.mediaNotificationControllerInfo?.let { controller ->
                { buttons -> current.setMediaButtonPreferences(controller, buttons) }
            },
        )
    }

    /**
     * PRODUCT_SPEC PLAY-002 / PLAY-007 — every button, in the order that decides who gets the car's bar.
     *
     * **List order is the mechanism, not a style choice.** Pass 1 of
     * `CommandButton.getCustomLayoutFromMediaButtonPreferences` walks this list and gives a contested slot
     * to the *first* enabled button whose chain names it, so the output actions are emitted before the
     * skips in order to win the two primary positions. The owner asked for exactly that trade after a
     * device run: *"I need them more than seek forward and back."*
     *
     * **The back slot is never left empty, and that is a safety property rather than tidiness.** When no
     * button holds it, Media3 stops clearing `ACTION_SKIP_TO_PREVIOUS`, and this app has no
     * `ForwardingPlayer` intercepting it — so a head unit's *previous* would reach `Player.seekToPrevious`
     * and restart a thirty-four-hour book, the defect `NotificationButtons` exists to prevent. Car takes
     * the slot when it is shown and skip back takes it when Car is not, so one of them always does.
     * `MediaButtonSlotConversionTest` runs the real conversion over this list in all four states and
     * asserts that invariant.
     */
    private fun mediaButtons(): List<CommandButton> = MediaButtonLayout.inPriorityOrder(
        outputActions = outputCommandButtons(),
        skipActions = listOf(
            skipButton(
                icon = NotificationButtons.backIcon(skips.back),
                action = NotificationButtons.ACTION_SKIP_BACK,
                label = resources.getQuantityString(
                    R.plurals.player_notification_skip_back,
                    skips.back.inWholeSeconds.toInt(),
                    skips.back.inWholeSeconds.toInt(),
                ),
                slot = CommandButton.SLOT_BACK,
            ),
            skipButton(
                icon = NotificationButtons.forwardIcon(skips.forward),
                action = NotificationButtons.ACTION_SKIP_FORWARD,
                label = resources.getQuantityString(
                    R.plurals.player_notification_skip_forward,
                    skips.forward.inWholeSeconds.toInt(),
                    skips.forward.inWholeSeconds.toInt(),
                ),
                slot = CommandButton.SLOT_FORWARD,
            ),
        ),
        overflowActions = listOfNotNull(sleepTimerButton()),
    )

    /** PRODUCT_SPEC PLAY-008 — the running timer's remaining minutes, or `null` when no timer is set. */
    private fun sleepTimerButton(): CommandButton? =
        NotificationButtons.sleepTimerButton(sleepTimerState) { remaining ->
            getString(R.string.player_sleep_remaining, remaining.asMinutesLabel())
        }

    /**
     * PRODUCT_SPEC PLAY-002 — the car button and the headset button, or as many of them as apply.
     *
     * **These hold the car's two app-claimable bar positions, by the owner's decision.** Android Auto
     * reserves the *previous* and *next* positions and hands them to an app's custom actions when the app
     * does not advertise those transport commands — which BookWave does not, because a book is one timeline
     * window (ADR-0016). Two earlier attempts asked for the secondary slots instead; a device run showed
     * the bar unchanged, because the legacy conversion a car is served by branches on the back, forward and
     * overflow slots and on nothing else. So these now name the real primary slots.
     *
     * The cost is deliberate and was chosen after a device run: **skip back and skip forward move to the
     * overflow menu**, on the car *and* on the phone's system media controls, which read the same single
     * layout. `docs/risks.md` R-109 records the trade and that the owner accepted it.
     *
     * Absent rather than disabled when there is nothing to act on. A head unit draws a disabled custom
     * action as a grey square with no explanation, and a driver cannot ask it why; one fewer button is a
     * clearer statement than a dead one.
     */
    private fun outputCommandButtons(): List<CommandButton> = buildList {
        val state = outputButtons
        if (state.showCar) {
            add(
                outputButton(
                    icon = OutputActionIcons.car(state),
                    action = NotificationButtons.ACTION_SELECT_CAR_OUTPUT,
                    label = getString(R.string.player_car_action),
                    slot = CommandButton.SLOT_BACK,
                ),
            )
        }
        if (state.showHeadset) {
            add(
                outputButton(
                    icon = OutputActionIcons.headset(state),
                    action = NotificationButtons.ACTION_CYCLE_HEADSET_OUTPUT,
                    label = state.headsetName
                        ?.let { name -> getString(R.string.player_headset_action, name) }
                        ?: getString(R.string.player_headset_action_unknown),
                    slot = CommandButton.SLOT_FORWARD,
                ),
            )
        }
    }

    /**
     * One output button.
     *
     * `ICON_UNDEFINED` on purpose: neither a car nor a headset is among Media3's icon constants, and the
     * constant is passed to legacy controllers as a hint beside the resource. Naming a wrong one would
     * invite a head unit to draw something else entirely. `setCustomIconResId` is what actually reaches the
     * car — Media3 builds the legacy `PlaybackStateCompat.CustomAction` from that resource.
     */
    private fun outputButton(icon: Int, action: String, label: String, slot: Int): CommandButton =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(icon)
            .setDisplayName(label)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            // Two slots, in preference order, and the second is why this is safe. `setSlots` is a *chain*:
            // the host takes the first it can honour. So a head unit that gives these a primary-bar
            // position draws them beside the transport controls — where the minimised bar can reach them —
            // and one that cannot falls back to the overflow menu, which is exactly where they were.
            .setSlots(slot, CommandButton.SLOT_OVERFLOW)
            .setEnabled(true)
            .build()

    /**
     * One skip button, asking for its primary slot and **accepting overflow when an output action wins it**.
     *
     * The overflow fallback is load-bearing rather than defensive. Pass 2 of the legacy conversion emits a
     * displaced button only if its chain contains overflow; without it a skip that lost its slot is
     * *dropped from every surface* rather than relocated. A test asserts both halves.
     */
    private fun skipButton(icon: Int, action: String, label: String, slot: Int): CommandButton =
        CommandButton.Builder(icon)
            .setDisplayName(label)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .setSlots(slot, CommandButton.SLOT_OVERFLOW)
            .setEnabled(true)
            .build()

    /**
     * PRODUCT_SPEC PLAY-003 — one play or pause, in the playing book's history.
     *
     * Silent when nothing is loaded: `playWhenReady` also changes as a book is torn down, and a pause
     * against no book is not an event anybody wants to read.
     */
    private fun recordTransport(event: PlaybackEvent) {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        if (current.mediaItemCount == 0) return
        val bookId = MediaItems.bookIdOf(item)
        val at = current.bookPosition()
        // PRODUCT_SPEC 6.5 — the owner is read now, on the main thread with the position, not inside the
        // launch. See [flushProgress] for why the far end of an application-scoped launch is the wrong
        // place to ask who is signed in.
        val owner = MediaItems.ownerOf(item)
        applicationScope.launch { history.record(bookId, event, from = null, to = at, owner = owner) }
    }

    /**
     * PRODUCT_SPEC 11.1 / PLAY-003 — the loaded book and current position used by Chapters and History.
     *
     * Whatever is loaded, or `null` for a car opened with nothing playing — in which case [AutoLibrary]
     * falls back to the last book with progress. It deliberately does not fall back to zero: zero is a real
     * position and would draw every chapter bar empty for a book the listener is halfway through.
     *
     * **Must be called on the main thread.** `currentMediaItem` and `currentPosition` are Player calls and
     * Media3 asserts the application thread. Callers copy the plain [NowPlaying] value out before they hop to
     * the application scope; R-66 was the bug caused by doing those Player reads after the hop.
     */
    private fun nowPlaying(): NowPlaying? {
        val current = player ?: return null
        val bookId = current.currentMediaItem?.let(MediaItems::bookIdOf) ?: return null
        return NowPlaying(bookId, current.currentPosition.coerceAtLeast(0).milliseconds)
    }

    /**
     * Which branch `setMediaItems` took, whether the request actually resolved, and the answer.
     *
     * **`handedBack` alone cannot say whether a request resolved, and a review caught that it could not.**
     * When nothing resolves and a book is already playing, `unresolved` deliberately hands that book back
     * rather than emptying the queue — so the response carries one item for a request that failed. A
     * diagnostic reporting `handedBack=1` there would send the next reader to the player for a defect that
     * is in resolution, which is the exact confusion this logging exists to end. The branch and the
     * resolution are therefore carried out of the `when` that decides them, rather than inferred
     * afterwards from a shape that two different outcomes share.
     *
     * On the service rather than inside the session callback because that callback is an anonymous object,
     * and Kotlin does not allow a class declaration inside one.
     */
    private data class Selection(
        val branch: String,
        val resolved: Boolean,
        val kind: String,
        val answer: MediaSession.MediaItemsWithStartPosition,
    )

    /**
     * A `PLAY_WHEN_READY_CHANGE_REASON_*` constant as the word Media3 calls it.
     *
     * What this can and cannot tell §2.9: it separates a press from the system — `audioFocusLoss` and
     * `becomingNoisy` mean nobody pressed anything — and it does not say *who* pressed. `remote` is
     * mapped for completeness and is unreachable from this service: it describes a change reported by a
     * *remote* player, and the listener is attached to the local ExoPlayer, which emits only
     * `userRequest` (every `setPlayWhenReady` call), `becomingNoisy`, and the focus and end-of-item
     * reasons. R-76 is what happened when a device script read `remote` as "the wheel asked".
     */
    private fun playWhenReadyReason(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "userRequest"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audioFocusLoss"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "becomingNoisy"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "endOfItem"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressedTooLong"
        else -> "unknown"
    }

    /**
     * A `Player.STATE_*` constant as the word Media3 calls it.
     *
     * `Player.STATE_IDLE` is the one worth reading: a player that was handed a queue and stayed idle was
     * never prepared, which is a different defect from one that buffered and never became ready.
     */
    private fun stateName(playbackState: Int): String = when (playbackState) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown"
    }

    /**
     * Whatever is loaded **at the moment this is called**, as Media3's own value type.
     *
     * **Must be called on the main thread**, for the same reason as [nowPlaying] and stated here as well
     * rather than by reference, because the whole of R-66 was a caller that did not follow the reference.
     *
     * **And it must be called late.** The one caller hops onto the main thread with `withContext` at the
     * point of use rather than reading this early and carrying the answer, because a position is only true
     * for the instant it was read: playback advances, and `unresolved`'s caller can spend seconds in a
     * network failure before it needs one. A value copied out beforehand would seek a playing book
     * backwards by exactly that long. Freshness and the thread are two requirements, and a snapshot taken
     * on the right thread satisfies only one (R-72).
     *
     * Both properties are read in one place so a book that changed between them cannot produce a position
     * from one item against the identity of another (ADR-0016), and `null` when the player holds nothing —
     * which the caller turns into an empty queue rather than into a guess.
     */
    private fun loadedNow(): MediaSession.MediaItemsWithStartPosition? {
        val current = player ?: return null
        val loaded = current.currentMediaItem ?: return null
        return MediaSession.MediaItemsWithStartPosition(listOf(loaded), 0, current.currentPosition.coerceAtLeast(0))
    }

    /**
     * Turns a browse item into something the player can actually load.
     *
     * A browse item has a media id and no URI: the tree is built from cached rows, and the track URLs come
     * from a session the server has to open. This is where that happens.
     *
     * An item that is **already** complete is returned untouched — see [MediaItems.isReadyToPlay], which is
     * where the reasoning for that lives, because it is the part worth testing.
     *
     * @param trusted whether the submitting controller is this application. Only then may a pre-resolved
     *   item pass through as given; otherwise the item must name a browse id this service resolves itself,
     *   because "already complete" is satisfied by a bare URI and an outside caller chooses that URI.
     */
    private suspend fun resolvePlayable(item: MediaItem, trusted: Boolean): MediaItem? =
        if (trusted && MediaItems.isReadyToPlay(item)) item else resolveQueue(item)?.item

    /**
     * Resolves a controller-supplied browse/spoken item to a fresh server queue.
     *
     * Deliberately has no "Play follows" argument. `MediaSession.Callback.onSetMediaItems` and
     * `onAddMediaItems` mean only that a controller is setting media; Media3 1.11 does not promise a Play in
     * the same operation. Treating this helper as arm-only makes generic external resolution structurally
     * unable to mint a fresh-start exemption that a much later Play could consume.
     */
    private suspend fun resolveQueue(item: MediaItem): MediaItems.Queue? {
        val target = AutoLibrary.resolve(item.mediaId) ?: return null
        return openQueue(target.bookId, target.startAt)
    }

    /**
     * PRODUCT_SPEC 6.4 step 6 — plays the next book in the series, if there is one and the listener wants it.
     *
     * ### Why this reads the player rather than being told what ended
     *
     * `STATE_ENDED` says the timeline ran out, not which book it was. The current item *is* the one that
     * just finished — ADR-0016 makes a book a single window, so there is no next item Media3 could have
     * moved to — and reading it here is the only place that fact is still available.
     *
     * ### The three ways this does nothing, and why each is silent
     *
     * The listener turned it off; the book is in no series; nothing unfinished follows it.
     * `NextInSeriesUseCase` collapses all three into `null` because they are one answer to this caller.
     * None is an error and none is worth a message: a book ending and the app stopping is what every
     * version before this did.
     *
     * ### Why it plays rather than arming
     *
     * Because audio was playing a moment ago. ROUTE-002's *arm only* exists for a car connecting in a
     * silent room, where starting audio is the surprise; here the surprise is the silence. What that costs
     * somebody who fell asleep is recorded in `docs/risks.md`, and PLAY-008's sleep timer is the answer to
     * it — this is not the control that should be trying to guess whether anybody is awake.
     */
    private suspend fun advanceToNextInSeries() {
        val finished = withContext(mainDispatcher) {
            player?.currentMediaItem?.let(MediaItems::bookIdOf)
        } ?: return
        val next = nextInSeries(finished) ?: return
        val queue = openQueue(next.id, startAt = null) ?: return
        withContext(mainDispatcher) {
            val current = player ?: return@withContext
            current.setMediaItem(queue.item, queue.startPositionMs)
            current.prepare()
            current.play()
        }
        // No title and no id (14.5). That a series advanced is the diagnosable fact; which book it was is
        // the thing a private library must not put in a log.
        logger.info(LogCategory.Playback, "A book ended, so the next in its series was started")
    }

    /**
     * Opens a session for [bookId] and returns it as a queue, starting at [startAt] when one was asked for.
     *
     * `null` on any failure, and the failure is logged rather than surfaced: the caller is a car or a
     * headset, neither of which has anywhere to show an error. What they get instead is nothing happening,
     * which is what ROUTE-001 asks for — "if no playable item exists, the command does nothing and logs a
     * non-fatal diagnostic".
     *
     * Service-owned queue opens deliberately do not mint PR #93's first-Play exemption. Generic controller
     * resolution can arm media without playing it, while cold `onPlaybackResumption(isForPlayback = true)`
     * has a separate Media3 loaded-item Play after installation. That loaded Play must pass through the
     * shared freshness coordinator because a newly opened `/play` position can still be stale.
     */
    private suspend fun openQueue(bookId: LibraryItemId, startAt: Duration?): MediaItems.Queue? =
        when (val opened = openPlaybackSession(bookId)) {
            is AppResult.Failure -> {
                logger.warn(
                    LogCategory.Playback,
                    "Could not open a session for a browse or resume request",
                    LogField.Public("error", opened.error.code),
                )
                reportSessionFailureToControllers(opened.error)
                null
            }

            is AppResult.Success -> {
                val playbackSession = opened.value
                bookChanges.onBookOpened(playbackSession)
                val queue = MediaItems.queueFor(
                    session = playbackSession,
                    historyLink = MediaItems.HistoryLink(
                        label = getString(R.string.car_player_history_link),
                        historyMediaId = AutoLibrary.TAB_HISTORY,
                    ),
                )
                if (startAt == null) {
                    queue
                } else {
                    queue.copy(startPositionMs = startAt.inWholeMilliseconds.coerceAtLeast(0))
                }
            }
        }

    /**
     * A suspending body as the `ListenableFuture` Media3's callbacks return.
     *
     * Media3's session callbacks are future-based and this app is coroutine-based, and the bridge has to
     * exist somewhere. On the **application** scope rather than the service's: a browse request that arrives
     * as the service is being torn down should still answer, and a cancelled scope would leave the car
     * waiting on a future nobody completes.
     */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val settable = SettableFuture.create<T>()
        val job = applicationScope.launch {
            // ADR-0003 — `resultOf` is the app's single exception boundary, and this is a boundary: a
            // throwing browse request must reach the *future*, or the car waits on something nobody will
            // ever complete. `resultOf` rethrows cancellation before it catches anything, which is the
            // property a `runCatching` here would not have.
            when (val outcome = resultOf { block() }) {
                is AppResult.Success -> settable.set(outcome.value)
                is AppResult.Failure -> {
                    /*
                     * PRODUCT_SPEC 14.4 / 14.5 — the code *and* what threw.
                     *
                     * This used to log the code alone, and a head-unit run spent its whole budget on two
                     * lines reading `error=unknown` — which is what `AppError.Unknown` is called, and says
                     * nothing about the `IllegalStateException` underneath it. The cause was carried and
                     * discarded at the one place it would have been read.
                     *
                     * The **class name** is what identifies a defect; the message is what may carry a book
                     * title or a URL. So the class is logged and the message is not, which keeps 14.5 while
                     * making the line worth reading. `AppError.Unknown` is the only variant with a cause,
                     * and for every other variant the code already is the answer.
                     */
                    val cause = (outcome.error as? AppError.Unknown)?.cause
                    logger.warn(
                        LogCategory.Playback,
                        "A browse request failed",
                        LogField.Public("error", outcome.error.code),
                        LogField.Public("thrown", cause?.let { it::class.java.simpleName } ?: "none"),
                    )
                    settable.setException(IllegalStateException(outcome.error.code))
                }
            }
        }
        settable.addListener({ if (settable.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return settable
    }

    /**
     * PRODUCT_SPEC 11.1 — a bookmark at whatever is playing, from a control surface with no keyboard.
     *
     * The title is empty, and that is the design rather than a gap: a driver cannot type, and a bookmark
     * with a position and no note is exactly what they meant — "this bit". The phone's sheet is where a note
     * gets added afterwards.
     *
     * On the application scope rather than the service's, like every other write that must outlive the
     * moment: a bookmark dropped as a car disconnects is the one most worth keeping.
     */
    private fun bookmarkHere() {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        val bookId = MediaItems.bookIdOf(item)
        val at = Bookmark.roundedFrom(current.bookPosition())
        // PRODUCT_SPEC 6.5 — read here, on the main thread with the position, not inside the launch. The
        // same reasoning as `flushProgress`: the far end of an application-scoped launch is the wrong place
        // to ask who is signed in, because the whole point of that scope is outliving this moment.
        val owner = MediaItems.ownerOf(item)
        applicationScope.launch { bookmarks.add(bookId, at, title = "", owner = owner) }
    }

    /**
     * PRODUCT_SPEC PLAY-007 — the notification's skip, which is the app's skip.
     *
     * Expressed as a seek rather than as `Player.seekForward` for the same reason `PlaybackController` does:
     * Media3's own skip uses the increment fixed when the player was built, and PLAY-007's is configurable
     * per direction while the player is running.
     *
     * Media3 clamps the top end at the window's duration; the bottom is clamped here, because a negative
     * seek would be silently accepted as zero by some controllers and rejected by others.
     */
    private fun skipBy(delta: Duration) {
        val current = player ?: return
        if (current.mediaItemCount == 0) return
        // Issue #91 — this custom notification command bypasses ResumeFreshnessPlayer.handleSeek.
        resumeFreshness.invalidate(ResumeInvalidation.NotificationSkip)
        current.seekTo((current.bookPosition() + delta).inWholeMilliseconds.coerceAtLeast(0))
    }

    /**
     * "1 min" until the last minute, then seconds.
     *
     * Rounding **up** while minutes are shown is deliberate: a timer with 61 seconds left saying
     * "1 min" and then ticking to "1 min" again reads as stuck. Rounding up means it counts 2, 1, then
     * seconds, and never shows a number it has already passed.
     */
    private fun Duration.asMinutesLabel(): String {
        val seconds = inWholeSeconds
        if (seconds < SECONDS_PER_MINUTE) return getString(R.string.player_sleep_seconds, seconds)
        val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        return getString(R.string.player_sleep_minutes, minutes)
    }

    /**
     * Connections, browse and transport controls at the controller trust boundary.
     *
     * Media3's standard transport commands are available to playback-only controllers, but library reads,
     * arbitrary browse resolution and BookWave's custom commands stay behind [ControllerTrust]. That split
     * is intentional: a headset may control playback without gaining access to private library metadata or
     * the ability to submit an arbitrary URI and have the service play it.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /**
         * PRODUCT_SPEC 11.1 — the browse root, which is what makes the app appear in a car at all.
         *
         * The default rejects, and a rejection is what wave 1 shipped because an empty root would have
         * looked supported and browsed to nothing. There is a tree now.
         */
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // PRODUCT_SPEC ROUTE-001 — the car asking for a *resume tile* rather than for the browse tree.
            //
            // Android Auto sends this hint when it starts, before the driver has touched anything, and what
            // comes back is the tile on the media home screen. It is a different question from "what can I
            // browse", so it gets a different root: see `AutoLibrary.RECENT_ROOT`.
            if (!session.mayBrowse(browser)) {
                return Futures.immediateFuture(deniedItem(browser, "onGetLibraryRoot"))
            }
            val root = if (params?.isRecent == true) auto.recentRoot() else auto.root()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            /*
             * **Read the player here, not inside `future`** — `docs/risks.md` R-66.
             *
             * Media3 invokes this callback on the application thread; `future` runs its block on
             * `applicationScope`, which is `Dispatchers.Default`. `nowPlaying()` reads
             * `player.currentMediaItem`, and ExoPlayer throws `IllegalStateException` for any property read
             * off its application thread. So the call used to be inside the block and every browse of a
             * node threw before it could answer.
             *
             * That is what a head-unit run saw: two `A browse request failed error=unknown` lines — the two
             * roots Android Auto asks for on connect — an empty browse tree, and **search working**, because
             * `onGetSearchResult` is the one browse callback that does not need the player.
             *
             * Same discipline as `recordTransport` and `positionSnapshot` a few hundred lines up, which say
             * the same thing about the same object. This is the third place it has mattered.
             */
            val now = nowPlaying()
            return future {
                if (!session.mayBrowse(browser)) return@future deniedList(browser, "onGetChildren")
                val all = auto.children(parentId, now)
                logger.info(
                    LogCategory.Playback,
                    "A browser asked for a node's children",
                    LogField.Public("parent", parentId),
                    LogField.Count("children", all.size),
                )
                val from = (page * pageSize).coerceAtMost(all.size)
                val to = (from + pageSize).coerceAtMost(all.size)
                LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val now = nowPlaying()
            return future {
                if (!session.mayBrowse(browser)) return@future deniedItem(browser, "onGetItem")
                auto.item(mediaId, now)
                    ?.let { item -> LibraryResult.ofItem(item, null) }
                    ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }
        }

        /**
         * Media3 search is a two-step protocol: this callback announces how many results are available,
         * then [onGetSearchResult] supplies the requested page. Returning items directly from `onSearch`
         * would skip the notification a browser is waiting for and leave Android Auto showing no results.
         */
        @Suppress("ForbiddenVoid")
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
            if (!session.mayBrowse(browser)) return@future deniedVoid(browser, "onSearch")
            val results = auto.search(query)
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid(params)
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            if (!session.mayBrowse(browser)) return@future deniedList(browser, "onGetSearchResult")
            val all = auto.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        /**
         * Resolves media ids submitted through controller `addMediaItems` into playable server-backed items.
         *
         * This is how a browser/head unit can press a library row whose [MediaItem] intentionally carries no
         * private stream URI. Only this application's UID may pass through a pre-resolved playable item;
         * outside controllers are resolved against BookWave's own browse ids so they cannot choose a URI.
         */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            val trusted = controller.isThisApplication()
            val matches = mediaItems.mapNotNull { item -> resolvePlayable(item, trusted)?.let { item to it } }
            val resolved = matches.map { (_, playable) -> playable }.toMutableList()
            logSelection(
                callback = "onAddMediaItems",
                asked = mediaItems,
                selection = Selection(
                    branch = if (trusted) "passthrough" else "browse",
                    resolved = matches.isNotEmpty(),
                    kind = actedKind(mediaItems, matches.firstOrNull()?.first),
                    answer = MediaSession.MediaItemsWithStartPosition(resolved.toList(), 0, 0L),
                ),
            )
            resolved
        }

        /**
         * Resolves a controller's requested playlist without assuming transport intent.
         *
         * Media3 1.11 gives `onSetMediaItems` no guarantee that `play()` follows. A controller may set or
         * replace the item and leave it paused indefinitely. Therefore both spoken and browse resolution use
         * arm-only [resolveQueue]; neither can create a fresh-start token. If Play arrives later it enters
         * [ResumeFreshnessPlayer] normally and reconciles against current server evidence.
         */
        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            setMediaItems(session, controller, mediaItems, startIndex, startPositionMs)
        }

        private suspend fun setMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): MediaSession.MediaItemsWithStartPosition {
            val spokenRequest = mediaItems
                .firstNotNullOfOrNull { item -> item.requestMetadata.searchQuery?.let { q -> item to q } }
                ?.takeIf { session.mayBrowse(controller) }
            val query = spokenRequest?.second
            val spoken = query?.let { asked -> auto.search(asked).firstOrNull() }
            val selection = when {
                query != null -> spoken?.let { match ->
                    resolveQueue(match)
                }.let { queue ->
                    Selection(
                        branch = "spoken",
                        resolved = queue != null,
                        kind = actedKind(mediaItems, spokenRequest.first),
                        answer = queue.asItems(startIndex, startPositionMs),
                    )
                }

                controller.isThisApplication() &&
                    mediaItems.isNotEmpty() &&
                    mediaItems.all(MediaItems::isReadyToPlay) ->
                    Selection(
                        branch = "passthrough",
                        resolved = true,
                        kind = actedKind(mediaItems),
                        answer = MediaSession.MediaItemsWithStartPosition(
                            mediaItems.toList(),
                            startIndex,
                            startPositionMs,
                        ),
                    )

                else ->
                    mediaItems
                        .firstNotNullOfOrNull { item ->
                            resolveQueue(item)?.let { queue -> item to queue }
                        }
                        .let { match ->
                            Selection(
                                branch = "browse",
                                resolved = match != null,
                                kind = actedKind(mediaItems, match?.first),
                                answer = match?.second.asItems(startIndex, startPositionMs),
                            )
                        }
            }
            logSelection("onSetMediaItems", mediaItems, selection)
            return selection.answer
        }

        private fun actedKind(asked: List<MediaItem>, acted: MediaItem? = null): String =
            (acted ?: asked.firstOrNull())?.mediaId?.let(AutoLibrary::kindOf) ?: "none"

        /**
         * Selection diagnostics intentionally carry only public routing facts: callback/branch/kind, counts,
         * resolution status and a numeric start position. They never log a search query, title or stream URI;
         * those are private library/server data and are not needed to diagnose controller resolution.
         */
        private fun logSelection(callback: String, asked: List<MediaItem>, selection: Selection) {
            logger.log(
                LogEvent(
                    level = LogLevel.Info,
                    category = LogCategory.Playback,
                    message = "A controller asked to set what plays",
                    fields = buildList {
                        add(LogField.Public("callback", callback))
                        add(LogField.Public("branch", selection.branch))
                        add(LogField.Public("kind", selection.kind))
                        add(LogField.Public("resolved", selection.resolved.toString()))
                        add(LogField.Count("asked", asked.size))
                        add(LogField.Count("handedBack", selection.answer.mediaItems.size))
                        add(LogField.Millis("startAt", selection.answer.startPositionMs))
                    },
                ),
            )
        }

        /**
         * A successfully opened queue owns its authoritative start position. The controller's requested
         * position is retained only when resolution failed and [unresolved] must preserve the existing item.
         */
        private suspend fun MediaItems.Queue?.asItems(startIndex: Int, startPositionMs: Long) = when (this) {
            null -> unresolved(startIndex, startPositionMs)
            else -> MediaSession.MediaItemsWithStartPosition(listOf(item), 0, this.startPositionMs)
        }

        /**
         * Keeps current playback intact when a controller request cannot be resolved.
         *
         * [loadedNow] is deliberately read late and on [mainDispatcher], after any network/search work. A
         * position snapshot taken before that work could be seconds stale and hand Media3 the current book
         * with an older position, effectively seeking backwards while merely declining another request.
         */
        private suspend fun unresolved(
            startIndex: Int,
            startPositionMs: Long,
        ): MediaSession.MediaItemsWithStartPosition = withContext(mainDispatcher) { loadedNow() }
            ?: MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)

        /**
         * Media3 1.11 distinguishes describing resumable media from requesting playback.
         *
         * `isForPlayback = false` is metadata-only and opens no server session. For `true`, Media3's pinned
         * callback contract automatically installs the returned media, prepares it and calls Play. That
         * loaded-item Play deliberately gets no fresh-start exemption here: it is the point where the shared
         * PR #93 freshness coordinator validates the newly opened `/play` position before audio starts.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            if (isForPlayback) resumeForPlayback() else describeResumable()
        }

        /** The `isForPlayback = true` half: open the book and hand back a queue Media3 immediately plays. */
        private suspend fun resumeForPlayback(): MediaSession.MediaItemsWithStartPosition {
            val book = auto.lastPlayed()
            val queue = book?.let {
                openQueue(it.id, startAt = null)
            }
            return if (queue == null) {
                logger.info(LogCategory.Playback, "A resume was requested with nothing to resume")
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            } else {
                // Issue #138 — fault injection happens after the real `/play` session was staged above, so
                // ResumeFreshnessCoordinator still owns the real trusted/server position. Only Media3's
                // initial local install is replaced with zero, and only this playback-resumption callback
                // can consume the one-shot diagnostic.
                val startPositionMs = coldResumeStartPosition.forPlaybackResumption(queue.startPositionMs)
                logger.info(LogCategory.Playback, "Resuming the last book for a media button")
                MediaSession.MediaItemsWithStartPosition(listOf(queue.item), 0, startPositionMs)
            }
        }

        /**
         * The `isForPlayback = false` half: what *would* resume, described, with nothing opened.
         *
         * The same tile the car's recent root shows, because it is the same question asked by a different
         * surface — see [AutoLibrary.resumeItem]. The position travels in the media id rather than in the
         * start position, which Media3 discards on this path; it keeps the item self-describing for a
         * controller that hands it straight back.
         */
        private suspend fun describeResumable(): MediaSession.MediaItemsWithStartPosition {
            val item = auto.resumeItem()
            if (item == null) {
                logger.info(LogCategory.Playback, "A resumable book was asked for and there is none")
                return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            }
            logger.info(LogCategory.Playback, "Described the resumable book without opening a session")
            return MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
        }

        /**
         * Grants controller capabilities according to the same trust decision used by browse callbacks.
         * Playback-only controllers keep standard transport controls; library/custom commands are granted
         * only to controllers allowed across the library boundary. This prevents a convenient media-control
         * connection from silently becoming read access to the listener's private library.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.isCar()) {
                carConnections.onConnected()
                logger.info(
                    LogCategory.Playback,
                    "A car connected to the media session",
                    LogField.Public("controller", controller.packageName),
                )
                scope.launch {
                    audioOutputs.resettle()
                    holdHeadsetAgainstCar()
                    republishOutputButtons()
                }
            }
            val access = session.accessFor(controller)
            val commands = when (access) {
                ControllerAccess.LibraryAndPlayback ->
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .add(SessionCommand(NotificationButtons.ACTION_EXTEND_SLEEP_TIMER, Bundle.EMPTY))
                        .add(SessionCommand(NotificationButtons.ACTION_SKIP_BACK, Bundle.EMPTY))
                        .add(SessionCommand(NotificationButtons.ACTION_SKIP_FORWARD, Bundle.EMPTY))
                        .add(SessionCommand(NotificationButtons.ACTION_ADD_BOOKMARK, Bundle.EMPTY))
                        .add(SessionCommand(NotificationButtons.ACTION_SELECT_CAR_OUTPUT, Bundle.EMPTY))
                        .add(SessionCommand(NotificationButtons.ACTION_CYCLE_HEADSET_OUTPUT, Bundle.EMPTY))
                        .build()

                ControllerAccess.PlaybackOnly -> {
                    logger.info(
                        LogCategory.Playback,
                        "A controller connected without library access",
                        LogField.Public("controller", controller.packageName),
                    )
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                }
            }
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .apply { ControllerTrust.withheldPlayerCommands(access).forEach(::remove) }
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(playerCommands)
                .setMediaButtonPreferences(mediaButtons())
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (!controller.isCar()) return
            carConnections.onDisconnected()
            scope.launch { republishOutputButtons() }
        }

        /**
         * Applies the user's remembered car policy only after Media3 accepted the controller connection.
         * Arm means set/prepare and stay silent; ArmAndPlay is a deliberate service-owned direct start. Both
         * operate on the raw ExoPlayer, so neither needs an external forwarding-player fresh-start token.
         */
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (!controller.isCar()) return
            val current = player ?: return
            if (current.mediaItemCount > 0) return
            scope.launch {
                when (CarConnection.decide(devices, lock, clock.now())) {
                    AutoStartAction.ArmAndPlay -> startLastBook(current, play = true)
                    AutoStartAction.Arm -> startLastBook(current, play = false)
                    AutoStartAction.Suppressed -> logger.info(
                        LogCategory.Playback,
                        "A car connected while the account was locked; nothing started",
                    )
                    AutoStartAction.None -> Unit
                }
            }
        }

        /** Loads the last played book, playing it or leaving it paused. Silent when there is nothing to load. */
        private suspend fun startLastBook(current: ExoPlayer, play: Boolean) {
            val book = auto.lastPlayed() ?: return
            val queue = openQueue(book.id, startAt = null) ?: return
            logger.info(
                LogCategory.Playback,
                if (play) {
                    "A car connected and its policy is to start playing"
                } else {
                    "A car connected and the last book was made ready"
                },
            )
            current.setMediaItem(queue.item, queue.startPositionMs)
            current.prepare()
            if (play) current.play()
        }

        /** Package names identify known car hosts for routing UX only; they are not the library trust anchor. */
        private fun MediaSession.ControllerInfo.isCar(): Boolean = packageName in CAR_PACKAGES

        /** Same-process UID is the trust anchor for accepting a pre-resolved playable URI from BookWave. */
        private fun MediaSession.ControllerInfo.isThisApplication(): Boolean = uid == Process.myUid()

        /**
         * Builds the trust input from Media3's public controller classifiers. Package names are diagnostics
         * and car-routing hints, not substitutes for Media3's trust/automotive identity signals.
         */
        private fun MediaSession.identityOf(controller: MediaSession.ControllerInfo) = ControllerIdentity(
            packageName = controller.packageName,
            uid = controller.uid,
            isTrustedForMediaControl = controller.isTrusted,
            isMediaNotificationController = isMediaNotificationController(controller),
            isAutomotive = isAutomotiveController(controller),
            isAutoCompanion = isAutoCompanionController(controller),
        )

        private fun MediaSession.accessFor(controller: MediaSession.ControllerInfo): ControllerAccess =
            ControllerTrust.accessFor(identityOf(controller), selfUid = Process.myUid())

        private fun MediaSession.mayBrowse(controller: MediaSession.ControllerInfo): Boolean =
            ControllerTrust.mayBrowse(accessFor(controller))

        /** Records the public controller package/request only; no media id, title, query or private URI. */
        private fun denied(browser: MediaSession.ControllerInfo, callback: String) {
            logger.info(
                LogCategory.Playback,
                "A controller without library access was refused",
                LogField.Public("controller", browser.packageName),
                LogField.Public("request", callback),
            )
        }

        private fun deniedItem(browser: MediaSession.ControllerInfo, callback: String): LibraryResult<MediaItem> {
            denied(browser, callback)
            return LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
        }

        private fun deniedList(
            browser: MediaSession.ControllerInfo,
            callback: String,
        ): LibraryResult<ImmutableList<MediaItem>> {
            denied(browser, callback)
            return LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
        }

        @Suppress("ForbiddenVoid")
        private fun deniedVoid(browser: MediaSession.ControllerInfo, callback: String): LibraryResult<Void> {
            denied(browser, callback)
            return LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
        }

        /**
         * Executes only commands already granted to library-capable controllers in [onConnect]. The explicit
         * guard remains defense in depth: bookmark/output/sleep-timer actions should never become an escape
         * hatch around the same controller trust boundary that protects browse resolution.
         */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (!session.mayBrowse(controller)) {
                denied(controller, "onCustomCommand")
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            when (customCommand.customAction) {
                NotificationButtons.ACTION_EXTEND_SLEEP_TIMER -> sleepTimer.extend()
                NotificationButtons.ACTION_SKIP_BACK -> skipBy(-skips.back)
                NotificationButtons.ACTION_SKIP_FORWARD -> skipBy(skips.forward)
                NotificationButtons.ACTION_ADD_BOOKMARK -> bookmarkHere()
                NotificationButtons.ACTION_SELECT_CAR_OUTPUT -> {
                    headsetHold.releaseToCar(audioOutputs.outputs.value, audioOutputs.selectedId.value)
                    audioOutputs.select(AudioOutputRoles.carTarget(audioOutputs.outputs.value))
                }

                NotificationButtons.ACTION_CYCLE_HEADSET_OUTPUT ->
                    AudioOutputRoles
                        .nextHeadset(audioOutputs.outputs.value, audioOutputs.selectedId.value)
                        ?.let(audioOutputs::select)
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        /** PRODUCT_SPEC PLAY-004 — "at least every five seconds". */
        const val JOURNAL_INTERVAL_MS = 5_000L

        /**
         * PRODUCT_SPEC SYNC-002 — how far from the adopted position the player may land and still count.
         *
         * `ExoPlayer` seeks to the nearest sync sample unless the extractor can do better, so an exact
         * landing is not something a seek promises. A second is well inside "the same place in the book"
         * and far below any difference another device's listening could produce.
         */
        val ADOPT_TOLERANCE: Duration = 1.seconds

        /**
         * How long the seek has to report back before it is treated as lost.
         *
         * Nothing is playing yet, so this is dead air on a Play the listener has already waited a network
         * round trip for; two seconds is the same budget the freshness read itself gets. A seek that has not
         * landed by then is treated as failed and the book remains paused. There is deliberately no second
         * state-changing `/play` recovery after ownership may have moved elsewhere.
         */
        val SEEK_CONFIRM_TIMEOUT: Duration = 2.seconds

        const val SECONDS_PER_MINUTE = 60L

        val CAR_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.autoauto",
            "com.google.android.androidauto",
        )
    }
}
