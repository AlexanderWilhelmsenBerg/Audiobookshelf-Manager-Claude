package com.example.shelfplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackSessionStart
import com.example.shelfplayer.core.model.playback.ResumeDecision
import com.example.shelfplayer.core.model.playback.ResumeInvalidation
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.model.settings.DevicePolicy
import com.example.shelfplayer.core.model.settings.PlaybackSettings
import com.example.shelfplayer.domain.playback.AudioOutputRole
import com.example.shelfplayer.domain.playback.AudioOutputRoles
import com.example.shelfplayer.domain.playback.MediaItems
import com.example.shelfplayer.domain.playback.bookDuration
import com.example.shelfplayer.domain.playback.bookPosition
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.usecase.NextInSeriesUseCase
import com.example.shelfplayer.domain.usecase.OpenPlaybackSessionUseCase
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import java.util.concurrent.CancellationException
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-002 / ROUTE-001 — the one playback session Android owns.
 *
 * Android Auto and Wear reach a [MediaLibraryService] through `onGetLibraryRoot`, which the default
 * implementation rejects. Wave 5 answers it: [AutoLibrary] builds four stable root destinations —
 * Continue, Series, Authors, Library — and [LibraryCallback] serves them. A car also needs the app to
 * resolve the `mediaId` it picked into a playable stream; `onSetMediaItems` and `onAddMediaItems` do that
 * through the same [openQueue] path the phone uses, so there is one source of playback truth.
 *
 * PRODUCT_SPEC ROUTE-001 — the service is exported because platform media controls, Android Auto and
 * Bluetooth need to reach it even when the activity does not exist. The manifest grants no custom
 * permission because those platform clients are outside this app's uid; [mayBrowse] is the privacy gate
 * for the metadata tree.
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
    internal lateinit var stopHistoryCause: PlaybackStopHistoryCause

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

    /** PRODUCT_SPEC ROUTE-002 / AUTH-005 — whether the active profile is locked. */
    @Inject
    internal lateinit var lock: ProfileLockGuard

    @Inject
    internal lateinit var bookChanges: BookChanges

    /** PRODUCT_SPEC ROUTE-002 — the process-level device watcher that applies user policy. */
    @Inject
    internal lateinit var outputDevices: OutputDeviceWatcher

    /** PRODUCT_SPEC ROUTE-002 — names of saved devices, and each one's policy. */
    @Inject
    internal lateinit var devices: DeviceRepository

    /** PRODUCT_SPEC 14.4 — monotonic/UTC clock for diagnostics. */
    @Inject
    internal lateinit var clock: AppClock

    @Inject
    internal lateinit var metrics: PlaybackMetricsRecorder

    /** PRODUCT_SPEC PLAY-002 — whether a car is currently bound to this session. */
    @Inject
    internal lateinit var carConnections: CarConnections

    @Inject
    internal lateinit var bookmarks: BookmarkRepository

    /** PRODUCT_SPEC SYNC-002 — last local pause the server positively acknowledged. */
    @Inject
    internal lateinit var resumeBaseline: ResumeBaseline

    /** Issue #91 / #93 — one freshness owner for every standard Play command. */
    @Inject
    internal lateinit var resumeFreshness: ResumeFreshnessCoordinator

    /** Issue #138 — preserves the cold-resumption position until the first loaded Play is freshness-checked. */
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

        override suspend fun route(id: Int) {
            audioOutputs.select(id)
        }
    }

    private suspend fun load(startPlaying: Boolean) {
        val current = player ?: return
        if (current.mediaItemCount > 0) {
            if (startPlaying) current.play()
            return
        }
        val queue = auto.lastPlayed()?.let { book -> openQueue(book.id, startAt = null) } ?: return
        current.setMediaItems(queue.items, queue.startIndex, queue.startAtMs)
        current.prepare()
        if (startPlaying) current.play()
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
            is ResumeDecision.UseLocal -> applyFreshnessPosition(plan, decision.position)
            is ResumeDecision.UseServer -> applyFreshnessPosition(plan, decision.position)
            ResumeDecision.KeepPlaying -> resumeLoadedCurrent()
        }
    }

    private suspend fun applyFreshnessPosition(plan: ResumeFreshnessPlan, position: Duration) {
        if (!resumeFreshness.isCurrent(plan)) return
        val current = player ?: return
        current.seekTo(position.inWholeMilliseconds)
        resumeFreshness.invalidate(ResumeInvalidation.Seek)
        current.play()
    }

    private fun resumeLoadedCurrent() {
        val current = player ?: return
        current.play()
    }

    /**
     * A single snapshot of the current media for one suspend/decision sequence.
     *
     * Player properties are main-thread-affine; copying what is needed once prevents a suspend boundary from
     * splitting "which book" and "where" into observations of two different queue states.
     */
    private fun currentItemSnapshot(): CurrentItemSnapshot? {
        val current = player ?: return null
        val item = current.currentMediaItem ?: return null
        return CurrentItemSnapshot(
            item = item,
            bookId = MediaItems.bookIdOf(item),
            position = current.bookPosition(),
            duration = current.bookDuration(),
            owner = MediaItems.ownerOf(item),
        )
    }

    private data class CurrentItemSnapshot(
        val item: MediaItem,
        val bookId: LibraryItemId,
        val position: Duration,
        val duration: Duration,
        val owner: ProfileId?,
    )

    /**
     * PRODUCT_SPEC PLAY-001 — open one server session and convert it into one Media3 timeline.
     *
     * `startAt` is an *explicit* caller position. `null` means the session's own server-provided start.
     * The caller that knows it has a fresh local position passes it. A browse row that does not know a
     * position does not make one up.
     */
    private suspend fun openQueue(bookId: LibraryItemId, startAt: Duration?): OpenQueue? {
        val opened = openPlaybackSession(bookId)
        val session = when (opened) {
            is AppResult.Success -> opened.value
            is AppResult.Failure -> {
                logger.warn(
                    LogCategory.Playback,
                    "Could not open a playback session",
                    LogField.Public("error", opened.error::class.simpleName ?: "unknown"),
                )
                return null
            }
        }
        val start = startAt ?: session.startAt
        return OpenQueue(
            items = buildQueue(session),
            startIndex = 0,
            startAtMs = start.inWholeMilliseconds.coerceAtLeast(0),
        )
    }

    private fun buildQueue(opened: PlaybackSession): List<MediaItem> {
        val primarySeries = opened.series.firstOrNull { it.isPrimary } ?: opened.series.firstOrNull()
        val seriesText = primarySeries?.let { series ->
            series.sequence?.takeIf(String::isNotBlank)?.let { sequence -> "${series.name} · $sequence" }
                ?: series.name
        }
        return listOf(
            MediaItems.playable(
                bookId = opened.bookId,
                profileId = opened.profileId,
                uri = opened.tracks.firstOrNull()?.contentUrl ?: return emptyList(),
                title = opened.title,
                author = opened.author,
                albumTitle = seriesText,
                duration = opened.duration,
                singleFileFallback = opened.singleFileFallback,
                sessionId = opened.sessionId,
                tracks = opened.tracks,
            ),
        )
    }

    /**
     * PRODUCT_SPEC 6.4 — if this book ended, optionally arm the next unfinished book in its primary series.
     *
     * This is deliberately *arm*, not Play. The player reached a natural end; automatically starting the next
     * book would turn "auto-advance" into "auto-play the next thirty hours". The setting says what to line up,
     * not that BookWave may start audio without another Play command.
     */
    private fun advanceToNextInSeries() {
        val current = currentItemSnapshot() ?: return
        applicationScope.launch(mainDispatcher) {
            val next = nextInSeries(current.bookId) ?: return@launch
            val queue = openQueue(next.id, startAt = next.progress?.position) ?: return@launch
            val media = player ?: return@launch
            media.setMediaItems(queue.items, queue.startIndex, queue.startAtMs)
            media.prepare()
        }
    }

    /**
     * PRODUCT_SPEC PLAY-003 — jump history from an old and new book-relative position.
     *
     * Called only for `DISCONTINUITY_REASON_SEEK`, because a period transition is a track boundary in the one
     * logical book timeline and `SEEK_ADJUSTMENT` is Media3 correcting a request. Neither is a listener
     * decision worth a History row.
     */
    private fun recordSeek(old: Duration, new: Duration) {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        val bookId = MediaItems.bookIdOf(item)
        val owner = MediaItems.ownerOf(item)
        val event = if (new > old) PlaybackEvent.SeekForward else PlaybackEvent.SeekBackward
        applicationScope.launch {
            history.record(bookId, event, from = old, to = new, owner = owner)
        }
    }

    /** PRODUCT_SPEC PLAY-003 — chapter boundaries are useful history only when somebody actually seeks. */
    private fun recordChapterChange(old: Duration, new: Duration) {
        if (old == new) return
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        val bookId = MediaItems.bookIdOf(item)
        val owner = MediaItems.ownerOf(item)
        applicationScope.launch {
            history.record(bookId, PlaybackEvent.ChapterChanged, from = old, to = new, owner = owner)
        }
    }

    private fun recordBookmark(position: Duration) {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        val bookId = MediaItems.bookIdOf(item)
        val owner = MediaItems.ownerOf(item)
        applicationScope.launch {
            bookmarks.add(bookId, position, owner = owner)
        }
    }

    private fun recordSpeed(speed: Float) {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        val bookId = MediaItems.bookIdOf(item)
        applicationScope.launch { playbackSettings.setSpeedFor(bookId, speed) }
    }

    private fun observeSleepTimer() {
        sleepTimerWatch = scope.launch {
            sleepTimer.state.collect {
                publishMediaButtons()
            }
        }
    }

    private fun observeSkipIntervals() {
        skipWatch = scope.launch {
            playbackSettings.observeSettings().collect { publishMediaButtons() }
        }
    }

    private fun observeAudioOutputs() {
        outputWatch = scope.launch {
            combine(audioOutputs.available, carConnections.connected) { _, _ -> Unit }
                .collect {
                    audioOutputs.resettle()
                    publishMediaButtons()
                }
        }
    }

    private fun publishMediaButtons() {
        val current = session ?: return
        val buttons = mediaButtons()
        current.setMediaButtonPreferences(buttons)
        // ADR-0029 R-107 — Media3's session-level setter does not refresh the legacy PlaybackStateCompat
        // custom-action list that projected Android Auto reads. The notification controller path does.
        current.connectedControllers.firstOrNull {
            it.packageName == packageName && it.uid == android.os.Process.myUid()
        }?.let { controller -> current.setCustomLayout(controller, buttons) }
    }

    /**
     * Android Auto and API-33+ System UI share one media-button preference list. The two output actions ask
     * for the compact bar's back/forward slots first and the skip actions accept overflow, so output wins the
     * scarce positions and the familiar skip controls remain available when the host has room.
     */
    private fun mediaButtons(): List<CommandButton> {
        val outputButtons = audioOutputs.buttons().map { output ->
            CommandButton.Builder(output.icon)
                .setDisplayName(output.label)
                .setSessionCommand(SessionCommand(output.action, Bundle.EMPTY))
                .setSlots(output.slot, CommandButton.SLOT_OVERFLOW)
                .setEnabled(output.enabled)
                .build()
        }
        val settings = runBlocking { playbackSettings.observeSettings().first() }
        return outputButtons + listOf(
            skipButton(
                icon = CommandButton.ICON_SKIP_BACK_10,
                action = ACTION_SKIP_BACK,
                label = "Back ${settings.skipBack.inWholeSeconds}s",
                slot = CommandButton.SLOT_BACK,
            ),
            skipButton(
                icon = CommandButton.ICON_SKIP_FORWARD_30,
                action = ACTION_SKIP_FORWARD,
                label = "Forward ${settings.skipForward.inWholeSeconds}s",
                slot = CommandButton.SLOT_FORWARD,
            ),
        )
    }

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
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            history.record(bookId, event, from = null, to = at, owner = owner)
        }
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
        val items: List<MediaItem>,
        val startIndex: Int,
        val startAtMs: Long,
    )

    private fun selectionLog(selection: Selection) {
        logger.debug(
            LogCategory.Playback,
            "A controller selected media",
            LogField.Public("branch", selection.branch),
            LogField.Public("resolved", selection.resolved.toString()),
            LogField.Public("handedBack", selection.items.size.toString()),
        )
    }

    private fun selectionOf(
        branch: String,
        resolved: Boolean,
        items: List<MediaItem>,
        startIndex: Int = 0,
        startAtMs: Long = C.TIME_UNSET,
    ) = Selection(branch, resolved, items, startIndex, startAtMs)

    private fun Selection.toMediaItemsWithStartPosition() =
        MediaSession.MediaItemsWithStartPosition(items, startIndex, startAtMs)

    private fun List<MediaItem>.selection(branch: String, resolved: Boolean) =
        selectionOf(branch, resolved, this)

    private fun Selection.mediaItems() = items

    private fun List<MediaItem>.asResult() =
        if (isEmpty()) MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
        else MediaSession.MediaItemsWithStartPosition(this, 0, C.TIME_UNSET)

    /**
     * PRODUCT_SPEC PLAY-002 / ROUTE-001 — the complete Media3 library/session contract for cars and Wear.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            carConnections.onConnected(controller)
            return super.onConnect(session, controller)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            carConnections.onDisconnected(controller)
            super.onDisconnected(session, controller)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            if (!session.mayBrowse(browser)) return@future deniedItem(browser, "onGetLibraryRoot")
            val root = auto.root()
            LibraryResult.ofItem(root, params)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val now = nowPlaying()
            return future {
                if (!session.mayBrowse(browser)) return@future deniedList(browser, "onGetChildren")
                val children = auto.children(parentId, now)
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
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
            val count = auto.search(query).size
            session.notifySearchResultChanged(browser, query, count, params)
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
            val results = auto.search(query)
            LibraryResult.ofItemList(ImmutableList.copyOf(results.page(page, pageSize)), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val current = player
            return future {
                val selection = when {
                    mediaItems.isEmpty() -> selectionOf("empty", resolved = true, items = emptyList())
                    mediaItems.size == 1 && mediaItems.single().mediaId.isBlank() -> {
                        val item = auto.lastPlayed()?.let { book -> openQueue(book.id, startAt = null) }
                        if (item == null) {
                            selectionOf("resume-empty", resolved = false, items = emptyList())
                        } else {
                            selectionOf("resume", resolved = true, item.items, item.startIndex, item.startAtMs)
                        }
                    }
                    mediaItems.all(MediaItems::isReadyToPlay) && controller.uid == android.os.Process.myUid() -> {
                        selectionOf("pre-resolved", resolved = true, mediaItems)
                    }
                    else -> {
                        resolve(mediaItems, startIndex, startPositionMs, current)
                    }
                }
                selectionLog(selection)
                selection.toMediaItemsWithStartPosition()
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            if (mediaItems.isEmpty()) return@future mutableListOf()
            if (mediaItems.all(MediaItems::isReadyToPlay) && controller.uid == android.os.Process.myUid()) {
                return@future mediaItems
            }
            resolveItems(mediaItems).toMutableList()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val item = auto.lastPlayed()?.let { book -> openQueue(book.id, startAt = null) }
            val queue = item
            if (queue == null) {
                logger.info(LogCategory.Playback, "A resume was requested with nothing to resume")
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            } else {
                val startAt = queue.startAtMs.coerceAtLeast(0).milliseconds
                coldResumeStartPosition.stage(startAt)
                MediaSession.MediaItemsWithStartPosition(queue.items, queue.startIndex, queue.startAtMs)
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = future {
            when (customCommand.customAction) {
                ACTION_SKIP_BACK -> {
                    val current = player ?: return@future SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    val settings = playbackSettings.observeSettings().first()
                    current.seekTo((current.currentPosition - settings.skipBack.inWholeMilliseconds).coerceAtLeast(0))
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                ACTION_SKIP_FORWARD -> {
                    val current = player ?: return@future SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    val settings = playbackSettings.observeSettings().first()
                    current.seekTo((current.currentPosition + settings.skipForward.inWholeMilliseconds)
                        .coerceAtMost(current.duration.coerceAtLeast(0)))
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                ACTION_BOOKMARK -> {
                    val current = player ?: return@future SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    recordBookmark(current.bookPosition())
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                ACTION_OUTPUT_CAR -> {
                    headsetHold.releaseToCar()
                    audioOutputs.select(null)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                ACTION_OUTPUT_HEADSET -> {
                    val id = headsetHold.remembered ?: return@future SessionResult(SessionResult.RESULT_ERROR_UNKNOWN)
                    audioOutputs.select(id)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                else -> SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }
        }
    }

    /** Resolves controller media ids through the same library/session opener used by the phone. */
    private suspend fun resolve(
        requested: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        current: ExoPlayer?,
    ): Selection {
        val ready = resolveItems(requested)
        if (ready.isEmpty()) {
            val existing = current?.currentMediaItem
            return if (existing == null) {
                selectionOf("unresolved", resolved = false, items = emptyList())
            } else {
                selectionOf("unresolved", resolved = false, items = listOf(existing))
            }
        }
        val index = startIndex.coerceIn(0, ready.lastIndex)
        val position = if (startPositionMs == C.TIME_UNSET) C.TIME_UNSET else startPositionMs.coerceAtLeast(0)
        return selectionOf("resolved", resolved = true, items = ready, startIndex = index, startAtMs = position)
    }

    private suspend fun resolveItems(requested: List<MediaItem>): List<MediaItem> = buildList {
        requested.forEach { requestedItem ->
            val target = AutoLibrary.resolve(requestedItem.mediaId) ?: return@forEach
            val queue = openQueue(target.bookId, target.startAt) ?: return@forEach
            addAll(queue.items)
        }
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val deferred = CompletableDeferred<T>()
        scope.launch {
            try {
                deferred.complete(block())
            } catch (cancelled: CancellationException) {
                deferred.cancel(cancelled)
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
            }
        }
        return deferred.asListenableFuture()
    }

    private fun <T> CompletableDeferred<T>.asListenableFuture(): ListenableFuture<T> =
        object : ListenableFuture<T> by Futures.immediateFuture(null as T) {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = this@asListenableFuture.cancel()
            override fun isCancelled(): Boolean = this@asListenableFuture.isCancelled
            override fun isDone(): Boolean = this@asListenableFuture.isCompleted
            override fun get(): T = runBlocking { await() }
            override fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): T = runBlocking {
                withTimeoutOrNull(unit.toMillis(timeout)) { await() } ?: throw java.util.concurrent.TimeoutException()
            }
        }

    /** The only ids Android Auto may browse while the profile is locked. */
    private suspend fun MediaLibrarySession.mayBrowse(controller: MediaSession.ControllerInfo): Boolean {
        if (!lock.isLocked()) return true
        logger.debug(
            LogCategory.Playback,
            "A media browser was refused while the profile is locked",
            LogField.Public("package", controller.packageName),
        )
        return false
    }

    private fun deniedItem(controller: MediaSession.ControllerInfo, call: String): LibraryResult<MediaItem> {
        denied(controller, call)
        return LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
    }

    private fun deniedList(
        controller: MediaSession.ControllerInfo,
        call: String,
    ): LibraryResult<ImmutableList<MediaItem>> {
        denied(controller, call)
        return LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
    }

    @Suppress("ForbiddenVoid")
    private fun deniedVoid(controller: MediaSession.ControllerInfo, call: String): LibraryResult<Void> {
        denied(controller, call)
        return LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
    }

    private fun denied(controller: MediaSession.ControllerInfo, call: String) {
        logger.debug(
            LogCategory.Playback,
            "A media browser call was refused while the profile is locked",
            LogField.Public("call", call),
            LogField.Public("package", controller.packageName),
        )
    }

    /**
     * PRODUCT_SPEC PLAY-001 — one recovery attempt per failing stream, then surface the error.
     */
    private inner class PlayerEvents : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val current = player ?: return
            if (recovery.shouldRetry(error)) {
                logger.warn(LogCategory.Playback, "Playback failed; trying once more")
                current.prepare()
            } else {
                logger.warn(LogCategory.Playback, "Playback failed", LogField.Public("code", error.errorCodeName))
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                heardAudio = true
                rememberHeadsetIfHeard()
                // A local move, including the automatic start after prepare, makes an old acknowledged pause no
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
            recordTransport(stopHistoryCause.eventFor(playWhenReady))
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
            if (mediaItem == null) {
                heardAudio = false
                headsetHold.clear()
                return
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && player?.playbackState == Player.STATE_ENDED) {
                advanceToNextInSeries()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            metrics.onStateChanged(playbackState)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            val old = oldPosition.positionMs.coerceAtLeast(0).milliseconds
            val new = newPosition.positionMs.coerceAtLeast(0).milliseconds
            if (old == new) return
            resumeFreshness.invalidate(ResumeInvalidation.Seek)
            recordSeek(old, new)
        }
    }

    private fun playWhenReadyReason(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "userRequest"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audioFocusLoss"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "becomingNoisy"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "endOfMediaItem"
        else -> "unknown:$reason"
    }

    private fun rememberHeadsetIfHeard() {
        val route = audioOutputs.currentRole() ?: return
        if (route.role == AudioOutputRole.Headset) headsetHold.remember(route.id)
    }

    private data class OpenQueue(
        val items: List<MediaItem>,
        val startIndex: Int,
        val startAtMs: Long,
    )

    private data class NowPlaying(val bookId: LibraryItemId, val position: Duration)

    companion object {
        private const val JOURNAL_INTERVAL_MS = 5_000L
        private const val FRESHNESS_TIMEOUT_MS = 1_500L
        private const val ACTION_SKIP_BACK = "bookwave.skip_back"
        private const val ACTION_SKIP_FORWARD = "bookwave.skip_forward"
        private const val ACTION_BOOKMARK = "bookwave.bookmark"
        private const val ACTION_OUTPUT_CAR = "bookwave.output_car"
        private const val ACTION_OUTPUT_HEADSET = "bookwave.output_headset"

        private val EMPTY_ITEMS = ImmutableList.of<MediaItem>()

        private fun List<MediaItem>.page(page: Int, pageSize: Int): List<MediaItem> {
            if (page < 0 || pageSize <= 0) return emptyList()
            val from = (page.toLong() * pageSize).coerceAtMost(size.toLong()).toInt()
            val to = (from + pageSize).coerceAtMost(size)
            return subList(from, to)
        }
    }
}
