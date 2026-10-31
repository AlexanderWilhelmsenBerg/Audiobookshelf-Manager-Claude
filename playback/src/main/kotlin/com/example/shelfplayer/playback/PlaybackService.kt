package com.example.shelfplayer.playback

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
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
import com.example.shelfplayer.domain.lock.ProfileLockGuard
import com.example.shelfplayer.domain.playback.ResumeBaseline
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.usecase.OpenPlaybackSessionUseCase
import com.example.shelfplayer.domain.usecase.NextInSeriesUseCase
import com.example.shelfplayer.domain.usecase.RestoreProfilePlaybackUseCase
import com.example.shelfplayer.domain.usecase.SwitchProfileUseCase
import com.example.shelfplayer.domain.usecase.SyncAccountUseCase
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
 * Continue, Series, Authors, Profiles — and [LibraryCallback] serves them. A car also needs the app to
 * *declare* itself, which is a manifest `meta-data` entry pointing at `automotive_app_desc.xml`; without
 * it the app is invisible in the dashboard no matter how good its tree is, which is exactly what a device
 * run found.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    internal lateinit var players: PlayerFactory

    /**
     * Issue #75 — the in-process handle the phone UI may use to attach only to this already-live session.
     *
     * It carries no book or playback metadata; Media3 remains the source of every rendered playback fact.
     */
    @Inject
    internal lateinit var liveSession: LivePlaybackSession

    /** PR #272 opt-in passive route observer; never changes playback policy. */
    @Inject
    internal lateinit var audioRouteLab: AudioRouteLab

    @Inject
    internal lateinit var audioTrackRouteMonitor: AudioTrackRouteMonitor

    /** PRODUCT_SPEC PLAY-002 — the chooser's half that can actually move audio: see [AudioOutputRouter]. */
    @Inject
    internal lateinit var audioOutputs: AudioOutputRouter

    @Inject
    internal lateinit var volumeEffects: PlaybackVolumeEffects

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
    internal lateinit var listeningHistory: ListeningHistoryRecorder

    @Inject
    internal lateinit var auto: AutoLibrary

    /** Issue #88 — one cheap authenticated account reconcile when local/cached resume identity is absent. */
    @Inject
    internal lateinit var syncAccount: SyncAccountUseCase

    /** PD-001 / PRODUCT_SPEC 6.5 — the same switch transaction the phone profile switcher delegates to. */
    @Inject
    internal lateinit var switchProfile: SwitchProfileUseCase

    /** PRODUCT_SPEC 6.5 step 6 — incoming remembered book is armed paused after a successful switch. */
    @Inject
    internal lateinit var restoreProfilePlayback: RestoreProfilePlaybackUseCase

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

    /** Issue #36 — physical projection lifecycle, independent of stale legacy MediaSession bindings. */
    @Inject
    internal lateinit var carProjection: AndroidAutoProjectionMonitor

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

    @Inject
    internal lateinit var coldStartToggleGate: ColdStartToggleGate
    private var serviceStartedAt: Duration = Duration.ZERO
    private var player: ExoPlayer? = null
    private lateinit var activeProfileGeneration: () -> Long
    private var session: MediaLibrarySession? = null
    private var journal: Job? = null
    private var sleepTimerWatch: Job? = null
    private var skipWatch: Job? = null
    private var outputWatch: Job? = null

    /**
     * Issue #88 — invalidates a suspended holder-to-play materialization when a newer controller command wins.
     *
     * Volatile because the Media3 callback is initiated on the player thread while the future may suspend on
     * the application scope during an authenticated /play request.
     */
    @Volatile
    private var controllerCommandGeneration = 0L

    /** Raw Media3 item transitions are newer ownership, even when no controller command was involved. */
    private var mediaItemGeneration = 0L

    /** Only the newest failed stream may retry; book changes and successful playback cancel older delays. */
    private var pendingRecoveryRetry: Job? = null

    /** One synchronous gate for every service-owned raw prepare/Play activation. */
    internal val playbackCommandEffects: PlaybackCommands by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackCommandEffects(
            currentPlayer = { player },
            itemGeneration = { mediaItemGeneration },
            isAttached = audioOutputs::isAttachedPlayer,
            protectOutput = { expectedPlayer, willStartAudio, holdForSeek ->
                audioOutputs.protectManualPlayOnGuardedHeadset(expectedPlayer, willStartAudio, holdForSeek)
            },
        )
    }

    /**
     * PLAY-001 / ROUTE-002 / issue #257 — one service-owned claim for delayed recovery and automatic
     * media replacement. The controller generation remains the existing Media3 freshness authority;
     * this guard observes it rather than creating a competing controller or session.
     */
    private val deferredWork by lazy {
        DeferredPlaybackWork(
            currentPlayer = { player },
            commandGeneration = { controllerCommandGeneration },
            itemGeneration = { mediaItemGeneration },
            profileGeneration = { activeProfileGeneration() },
            explicitSelectionSequence = ::currentExplicitSelectionSequence,
            commands = playbackCommandEffects,
            acceptPrepared = { prepared, authorized, install ->
                bookChanges.acceptBook(prepared.session, authorized, install)
            },
        )
    }

    /** Reconcile civil wall-clock changes only while this playback service already exists. */
    private val wallClockChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            sleepTimer.onWallClockChanged()
        }
    }

    /** The two inputs to the notification's own buttons. Main thread only, like everything that reads them. */
    private var skips: SkipIntervals = SkipIntervals.Default
    private var sleepTimerState: SleepTimerState = SleepTimerState.Idle

    /**
     * PRODUCT_SPEC PLAY-002 — which of the car's two output buttons are published, and what the headset one
     * is labelled with. Read on the main thread like the two above.
     */
    private var outputButtons: OutputButtons = OutputButtons.None

    /** Issue #38 — car binding changes media-button priority even when the visible output actions do not. */
    private val mediaButtonPublishing = MediaButtonPublishing.Tracker()

    /** Issue #10 — compare already-published browse shape without re-reading the library parent by parent. */
    private val browseInvalidations = AutoBrowseInvalidationTracker()

    /**
     * PRODUCT_SPEC PLAY-002 / ROUTE-002 — one generation-bound owner for route-heard evidence.
     *
     * Explicit listener output intent is fed through [AudioOutputRouter.explicitSelection]; Android route
     * policy is observed only while this player is actually playing. See [RouteHeardOwnership].
     */
    internal val routeOwnership: RouteHeardOwnership by lazy(LazyThreadSafetyMode.NONE) {
        RouteHeardOwnership(audioOutputs.outputIntentOwner)
    }

    private val routeObservationEffects by lazy(LazyThreadSafetyMode.NONE) {
        PlaybackRouteObservationEffects(
            currentPlayer = { player },
            volumeEffects = volumeEffects,
            onAudible = ::onRouteAudible,
        )
    }

    /**
     * Issue #36 — one owner correlates measured audio-focus loss with car arrival or final departure.
     *
     * The route recovery can tolerate Android's transient device-list omission but can target only the exact
     * headset captured by this policy. continuityPlayAwaiting is diagnostic only; it never authorizes Play.
     */
    private val carContinuity = CarArrivalResumeGate()
    private val carRecovery = CarContinuityRecoveryCoordinator(carContinuity)

    /**
     * Owns existing controller/projection lifecycle latches, separate from Media3, route policy
     * and the physical AudioTrack. Lazy because Android injects CarConnections before onCreate.
     */
    internal val carLifecycle by lazy { CarLifecycleAuthority(carConnections) }

    /** One owner for correlation, bounded reuse and privacy-safe Android Auto diagnostics. */
    private val autoTraces by lazy {
        AndroidAutoTraceRecorder(logger, clock) {
            AndroidAutoDiagnostics.playerFields(player) + carSnapshotFields()
        }
    }

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
        activeProfileGeneration = auto::activeProfileGeneration
        audioOutputs.currentProjection = { carLifecycle.projectionState.carConnected }
        audioOutputs.lastHeardRouteId = { routeOwnership.heardRoute?.outputId }
        serviceStartedAt = clock.elapsed()
        audioRouteLab.serviceStarted()
        scope = CoroutineScope(SupervisorJob() + mainDispatcher)
        registerWallClockChanges()
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
        val sessionPlayer = createSessionPlayer(exoPlayer)
        // Issue #91 — controllers see the forwarding player; service-owned timers/sync/routing below keep
        // the raw ExoPlayer so internal atomic operations cannot recursively enter the external Play gate.
        val mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
            .setBitmapLoader(players.bitmapLoader())
            .setCommandButtonsForMediaItems(listOf(profileSwitchButton()))
            // PRODUCT_SPEC PLAY-001 — tapping the notification opens the app. Without this the media
            // notification has no `contentIntent` at all, so a tap does nothing: a listener who reaches for
            // the notification to see where they are gets no response and no explanation.
            .apply { launchIntent()?.let(::setSessionActivity) }
            .build()
        session = mediaSession
        // Issue #75 — publish only after the real MediaSession exists. A direct session token lets a
        // recreated Activity attach to this exact session without using the service token that can start us.
        liveSession.publish(mediaSession.token)
        // Attach route/volume ownership before sleep and sync observe the successor. A retiring probe may
        // restore only its own old player and cannot emit an audible-resume event into the new observers.
        installPositionCheckpointHandlers()
        attachRouteSafetyMonitor(exoPlayer)
        routeObservationEffects.attach()
        attachSleepTimer(exoPlayer)
        // PRODUCT_SPEC PLAY-004 — the remote cadence reads the same player the journal does. It is given the
        // player rather than owning one, for the same reason the timer is: there is exactly one.
        sessionSync.attach(exoPlayer, ::effectiveBookPosition)
        autoRewind.attach(exoPlayer) {
            resumeFreshness.invalidate(ResumeInvalidation.AutoRewind)
        }
        startJournal()
        observeSleepTimer()
        observeSkipIntervals()
        observeAudioOutputs()
        carProjection.start(scope, ::onCarProjectionUpdate)
        outputDevices.start(scope, DeviceActions())
        observeBrowseTreeInvalidation()
        logger.info(LogCategory.Playback, "Playback service started")
    }

    private fun attachRouteSafetyMonitor(exoPlayer: ExoPlayer) {
        audioTrackRouteMonitor.onRouteChanged = audioOutputs::onActualTrackRouteChanged
        audioOutputs.attach(exoPlayer, audioTrackRouteMonitor::currentRouteIds)
        audioOutputs.currentTrackObservation = audioTrackRouteMonitor::currentRouteObservation
        audioOutputs.onActualTrackRouteChanged()
    }

    internal fun installPositionCheckpointHandlers() {
        positionEffects.install()
    }

    private val positionEffects by lazy(LazyThreadSafetyMode.NONE) {
        MutedPositionEffects(
            audioOutputs,
            object : MutedPositionEffects.Source {
                override fun player(): ExoPlayer? = this@PlaybackService.player
                override fun context() = MutedPositionEffects.Context(
                    controllerCommandGeneration,
                    mediaItemGeneration,
                    activeProfileGeneration(),
                )

                override fun onSettlingRefused(advance: Duration) {
                    logger.info(
                        LogCategory.Playback,
                        "Muted output verification refused excessive post-seek clock movement",
                        LogField.Millis("advance", advance.inWholeMilliseconds),
                    )
                }

                override fun onCompensated(advance: Duration) {
                    logger.info(
                        LogCategory.Playback,
                        "Muted output position compensated",
                        LogField.Millis("advance", advance.inWholeMilliseconds),
                    )
                }

                override fun onPrepared(probe: HeadsetRouteGuard.Probe, bufferedSeek: Boolean) {
                    logger.info(
                        LogCategory.Playback,
                        "Muted output proof preparation completed",
                        LogField.Public("stage", "SingleRestore"),
                        LogField.Public(
                            "speedMilli",
                            (
                                probe.player.playbackParameters.speed *
                                    PLAYBACK_PARAMETER_SCALE
                                ).toInt(),
                        ),
                        LogField.Public(
                            "pitchMilli",
                            (
                                probe.player.playbackParameters.pitch *
                                    PLAYBACK_PARAMETER_SCALE
                                ).toInt(),
                        ),
                        LogField.Public("bufferedSeek", bufferedSeek),
                        LogField.Public("proofEpoch", probe.preferenceEpoch),
                        LogField.Public(
                            "requestGeneration",
                            probe.transferRequest?.bookGeneration?.toString() ?: "none",
                        ),
                        LogField.Public(
                            "requestSelection",
                            probe.transferRequest?.selection?.sequence?.toString() ?: "none",
                        ),
                    )
                }
            },
        )
    }

    private fun effectiveBookPosition(target: Player): Duration = positionEffects.position(target)

    /**
     * A new Play from Android Auto is an explicit request for car audio, unlike car binding.
     * Keep route changes outside the onCreate forwarding-player lambda: the shared policy
     * remains testable and a duplicate Play does not steal a healthy headset route.
     */
    private fun routeExplicitCarPlay(requestedPlay: Boolean, mediaPlayer: ExoPlayer) {
        val requestingController = session?.controllerForCurrentRequest
        val isCarController = requestingController != null && requestingController.packageName in CAR_PACKAGES
        if (!shouldSelectCarForControllerPlay(requestedPlay, isCarController, mediaPlayer.playWhenReady)) return
        if (!audioOutputs.selectCarFromGenericController()) return
        syncExplicitOutputIntent()
        autoTraces.log("Android Auto Play selected the car audio route", fields = carSnapshotFields())
    }

    /**
     * The controller identity is available only during Media3's request callback. Observe it here, never
     * infer its origin from ExoPlayer's later USER_REQUEST reason. This records intent without changing it.
     */
    private fun logControllerTransportRequest(requestedPlay: Boolean, mediaPlayer: ExoPlayer) {
        val requestingController = session?.controllerForCurrentRequest
        if (audioRouteLab.state.value.recording) {
            audioRouteLab.controllerTransport(requestedPlay, mediaInputController(requestingController))
        }
        autoTraces.log(
            "MediaSession transport request observed",
            fields = buildList {
                add(LogField.Public("requested", if (requestedPlay) "Play" else "Pause"))
                add(
                    LogField.Public(
                        "controllerKind",
                        when {
                            requestingController == null -> "unattributed"
                            requestingController.packageName in CAR_PACKAGES -> "android-auto"
                            requestingController.uid == Process.myUid() -> "same-app"
                            else -> "external"
                        },
                    ),
                )
                requestingController?.let {
                    add(LogField.Identifier("controllerPackage", it.packageName))
                }
                add(LogField.Public("commandGeneration", controllerCommandGeneration))
                addAll(AndroidAutoDiagnostics.playerFields(mediaPlayer))
                addAll(carSnapshotFields())
            },
        )
    }

    /** PLAY-008 — initialize connection truth before the shared timer observes this service's player. */
    private fun attachSleepTimer(exoPlayer: ExoPlayer) {
        synchronizeSleepTimerCarConnection()
        sleepTimer.attach(
            exoPlayer,
            resumeOwner = object : SleepTimerResumeOwner {
                override suspend fun resume(stillAuthorized: () -> Boolean): Boolean =
                    performFreshnessPlay(explicit = true, stillAuthorized = stillAuthorized)
            },
        )
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
        // Guard clauses are intentional: each suspension boundary must fail closed before changing audio.
        @Suppress("ReturnCount")
        private suspend fun load(startPlaying: Boolean) {
            val claim = deferredWork.capture() ?: return
            if (claim.player.mediaItemCount > 0) return
            val profileId = auto.activeProfileId() ?: return
            if (!deferredWork.isCurrent(claim) || lock.isActiveProfileLocked()) return
            val book = auto.lastPlayed() ?: return
            if (!deferredWork.isCurrent(claim)) return

            // A lookup and /play can both suspend. Do not announce a new session until the same listener
            // intent, profile and empty player have won the guarded, main-thread commit.
            val prepared = prepareQueue(book.id, startAt = null) ?: return
            if (prepared.session.profileId != profileId) return
            deferredWork.installIfCurrent(
                claim = claim,
                prepared = prepared,
                startPlaying = startPlaying,
                condition = { it.mediaItemCount == 0 },
                allowed = { auto.isActiveProfile(profileId) && !lock.isActiveProfileLocked() },
            )
        }
    }

    /**
     * PRODUCT_SPEC 5.2 / ROUTE-001 / issue #10 — publish only meaningful Android Auto browse changes.
     *
     * One [AutoBrowseSnapshot] is derived from one profile-scoped accessible-book emission. Ordinary changes
     * compare ordered opaque child membership and notify only stale parents. Profile generation changes are a
     * harder boundary: every profile-scoped static parent and every dynamic Series/Author parent ever emitted
     * is invalidated even when the new profile happens to expose identical counts.
     */
    private fun observeBrowseTreeInvalidation() {
        scope.launch {
            auto.browseSnapshots().collect { snapshot ->
                val current = session ?: return@collect
                val plan = browseInvalidations.next(snapshot, auto.emittedDynamicParents())
                if (plan.notifications.isEmpty()) return@collect

                val deferredParents = plan.notifications
                    .filter { invalidation -> invalidation.childCount == null }
                    .mapTo(linkedSetOf()) { invalidation -> invalidation.parentId }
                val deferredCounts = auto.profileBoundaryCounts(snapshot, nowPlaying(), deferredParents)

                plan.notifications.forEach { invalidation ->
                    val count = invalidation.childCount ?: deferredCounts.getValue(invalidation.parentId)
                    current.notifyChildrenChanged(invalidation.parentId, count, null)
                }
                logger.info(
                    LogCategory.Playback,
                    "The Android Auto browse tree was invalidated",
                    LogField.Public("reason", if (plan.profileBoundary) "profile-boundary" else "shape-change"),
                    LogField.Count("parents", plan.notifications.size),
                )
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        // MediaButtonReceiver starts this service in the foreground before Media3 asks for a session.
        // If the cold-start safety gate consumes that receiver event later in onMediaButtonEvent, no
        // playback starts and Android tears the service down for missing its foreground promotion.
        // Reject only this exact Media3 fallback request when the same gate says to consume its event.
        // Media3's onStartCommand handles a null session through stopSelfSafely(), completing the
        // foreground-service start without manufacturing a playback notification or opening media.
        if (isSuppressedReceiverStart(controllerInfo)) {
            logger.info(LogCategory.Playback, "Cold virtual Bluetooth toggle consumed before service resume")
            return null
        }
        return session
    }

    private fun isSuppressedReceiverStart(controllerInfo: MediaSession.ControllerInfo): Boolean {
        if (!isMediaButtonReceiver(controllerInfo)) return false
        val hints = controllerInfo.connectionHints
        val extras = hints.getBundle(MediaLibraryService.CONNECTION_HINT_KEY_INTENT_EXTRAS) ?: return false
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtras(extras)
        val keyEvent = mediaButtonKeyEvent(intent) ?: return false
        if (audioRouteLab.state.value.recording) {
            audioRouteLab.mediaButtonInput(
                keyEvent,
                mediaInputController(controllerInfo),
                MediaInputEntry.ReceiverStart,
            )
        }
        return shouldConsumeColdStartToggle(keyEvent, controllerInfo.packageName)
    }

    private fun mediaInputController(controller: MediaSession.ControllerInfo?): MediaInputController =
        MediaInputController.capture(controller, Process.myUid(), CAR_PACKAGES)

    private fun isMediaButtonReceiver(controllerInfo: MediaSession.ControllerInfo): Boolean =
        controllerInfo.connectionHints.getString(MediaLibraryService.CONNECTION_HINT_KEY_CONTROLLER_INFO_TYPE) ==
            Intent.ACTION_MEDIA_BUTTON

    private fun mediaButtonKeyEvent(intent: Intent): KeyEvent? {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }

    private fun shouldConsumeColdStartToggle(keyEvent: KeyEvent, controllerPackage: String): Boolean =
        coldStartToggleGate.consume(
            sinceServiceStart = clock.elapsed() - serviceStartedAt,
            keyCode = keyEvent.keyCode,
            keyAction = keyEvent.action,
            inputDeviceId = keyEvent.deviceId,
            bluetoothEndpoints = audioOutputs.outputs.value
                .filter { it.id.startsWith("bluetooth:") }
                .map { it.id }
                .toSet(),
            emptyQueue = (player?.mediaItemCount ?: 0) == 0,
            carConnected = carConnections.isConnected() || carLifecycle.projectionState.carConnected ||
                controllerPackage in CAR_PACKAGES,
        )

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

    /** The actual MediaSession adapter; service-owned effects keep the raw player. */
    internal fun createSessionPlayer(exoPlayer: ExoPlayer): ResumeFreshnessPlayer = ResumeFreshnessPlayer(
        delegate = exoPlayer,
        preparePlay = { bypassFreshness ->
            val generation = controllerCommandGeneration
            // Car routing runs synchronously in onPlayWhenReadyRequest before this future factory.
            val selectionSequence = currentExplicitSelectionSequence()
            // Capture before launching: the scoped Media3 resumption listener ends when it returns.
            val explicit = !callbackFutures.isPlaybackResumptionDelivery()
            future { handleFreshnessPlay(generation, selectionSequence, explicit, bypassFreshness) }
        },
        prepareCurrentOutput = {
            val generation = controllerCommandGeneration
            val selectionSequence = currentExplicitSelectionSequence()
            future { prepareCurrentOutput(generation, selectionSequence) }
        },
        consumeFreshStart = resumeFreshness::consumeFreshStart,
        movementEffects = positionEffects,
        invalidate = ::onControllerCommand,
        onPlayWhenReadyRequest = { requestedPlay -> onControllerTransportRequest(requestedPlay, exoPlayer) },
        skipIntervals = { skips },
        allowEffects = { callbackFutures.playerEffectsAllowed() },
    )

    /** New controller movement supersedes all suspended work, even without a raw player callback. */
    private fun onControllerCommand(origin: ResumeInvalidation): Boolean {
        val movement = when (origin) {
            ResumeInvalidation.MediaChanged -> PlaybackMovement.MediaChanged
            ResumeInvalidation.Seek, ResumeInvalidation.NotificationSkip -> PlaybackMovement.Seek
            ResumeInvalidation.Pause, ResumeInvalidation.Stop, ResumeInvalidation.AutoRewind -> null
        }
        if (movement != null) {
            val finished = positionEffects.before(movement) ?: return false
            finished()
        }
        controllerCommandGeneration += 1
        val expected = positionEffects.authority()
        val current = player
        if (origin == ResumeInvalidation.Stop) audioOutputs.outputIntentOwner.onPlaybackStopped()
        resumeFreshness.invalidate(origin)
        sleepTimer.onResumeInvalidation(origin)
        clearCarContinuity()
        return player === current && positionEffects.authority() == expected
    }

    /** Actual listener/book/queue/service boundaries retire the existing claim and its pending effects. */
    private fun clearCarContinuity() {
        carResumeEffects.cancelPending()
        carRecovery.cancelAll()
        carRecovery.clearAwaiting()
    }

    /** External transport is newer listener intent even when Media3 emits no raw state transition. */
    private fun onControllerTransportRequest(requestedPlay: Boolean, current: ExoPlayer): Boolean {
        controllerCommandGeneration += 1
        val expected = positionEffects.authority()
        logControllerTransportRequest(requestedPlay, current)
        routeExplicitCarPlay(requestedPlay, current)
        // Duplicate Pause still matters. Automatic continuity uses the raw player and bypasses this boundary.
        sleepTimer.onControllerTransportIntent()
        carResumeEffects.cancelPending()
        if (requestedPlay) carRecovery.cancelPending() else carRecovery.cancelAll()
        carRecovery.clearAwaiting()
        return player === current && positionEffects.authority() == expected
    }

    override fun onDestroy() {
        routeObservationEffects.detach()
        audioOutputs.outputIntentOwner.releaseRuntime()
        audioOutputs.lastHeardRouteId = { null }
        audioRouteLab.serviceStopped()
        flushProgress()
        outputDevices.stop()
        metrics.onReleased()
        // PRODUCT_SPEC PLAY-004 — "service shutdown callback". On the application scope inside the
        // coordinator, because `scope` is cancelled two lines below.
        sessionSync.onShutdown()
        sessionSync.detach()
        audioOutputs.positions.clear()
        audioOutputs.currentProjection = { false }
        resumeFreshness.attach(null)
        autoRewind.attach(null)
        sleepTimer.attach(null)
        unregisterReceiver(wallClockChanges)
        carProjection.stop()
        clearCarContinuity()
        audioOutputs.detach()
        journal?.cancel()
        sleepTimerWatch?.cancel()
        skipWatch?.cancel()
        outputWatch?.cancel()
        session?.let { mediaSession ->
            // Clear the attach handle before release so an Activity entering at the same moment cannot pick
            // up a token whose session is already on its way out.
            liveSession.clear(mediaSession.token)
            mediaSession.release()
        }
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
                val snapshot = positionSnapshot() ?: continue
                val claim = deferredWork.capture()
                val playing = player?.isPlaying == true
                val at = clock.now()
                val historySample = if (playing && !volumeEffects.isRouteMuted(player)) {
                    listeningHistory.capture(
                        snapshot.bookId,
                        snapshot.position,
                        at,
                        snapshot.owner,
                    )
                } else {
                    null
                }
                recordPosition(snapshot)
                if (historySample != null) {
                    listeningHistory.persist(historySample)
                    if (historySample.crossed) {
                        sessionSync.request(SyncTrigger.ChapterChanged) {
                            claim != null && deferredWork.isCurrent(claim)
                        }
                    }
                }
            }
        }
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
    private suspend fun onCameToRest(snapshot: PositionSnapshot?, claim: DeferredPlaybackWork.Claim?) {
        val current = { claim != null && deferredWork.isCurrent(claim) }
        if (snapshot == null) {
            // Nothing worth a baseline — a player that has not started, or the single-file fallback whose
            // offsets are not book positions at all (R-61). The next Play has no acknowledged pause and
            // therefore resumes locally, which is the correct answer for a position we cannot vouch for.
            if (current()) resumeBaseline.onLocalMove()
            return
        }
        if (current()) {
            val generation = resumeBaseline.onPaused(snapshot.bookId, snapshot.position)
            logger.debug(
                LogCategory.Playback,
                "The player came to rest, pending the server's acknowledgement",
                LogField.Millis("position", snapshot.position.inWholeMilliseconds),
                LogField.Public("generation", generation.toString()),
            )
        }
        recordPosition(snapshot)
        sessionSync.sync(SyncTrigger.Paused, current)
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
        val item = current.currentMediaItem
        // Issue #88 — this position was copied from cached/server progress solely for system presentation.
        // No audio session owns it, so journalling it would manufacture a local playback write.
        if (item == null || MediaItems.isResumePlaceholder(item)) return null
        val position = effectiveBookPosition(current)
        val positionMs = position.inWholeMilliseconds
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
            position = position,
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
    private suspend fun handleFreshnessPlay(
        expectedCommandGeneration: Long,
        expectedSelectionSequence: Long,
        explicit: Boolean,
        bypassFreshness: Boolean,
    ): Boolean = performFreshnessPlay(
        explicit = explicit,
        bypassFreshness = bypassFreshness,
        stillAuthorized = {
            controllerCommandGeneration == expectedCommandGeneration &&
                currentExplicitSelectionSequence() == expectedSelectionSequence
        },
    )

    /**
     * The one service-owned resume implementation used by both MediaSession Play and sleep-grace Play.
     *
     * The sleep timer contributes only an ownership predicate; it does not get a private raw ExoPlayer Play.
     * The predicate is checked again inside the same main-thread turn as the final raw command, so schedule
     * expiry, teardown, book replacement or newer controller intent can revoke a suspended grace attempt.
     */
    private suspend fun performFreshnessPlay(
        explicit: Boolean,
        stillAuthorized: () -> Boolean = { true },
        bypassFreshness: Boolean = false,
    ): Boolean {
        // Sleep-grace Play enters directly; its output owner must be captured before any suspension.
        val selectionSequence = currentExplicitSelectionSequence()
        val authorized = { stillAuthorized() && currentExplicitSelectionSequence() == selectionSequence }
        // Issue #88 — BookChanges.onBookOpened invalidates sleep Play claims. Materialize the display-only
        // holder first so the claim below belongs to the real book generation it is authorizing.
        if (!authorized() || !materializeHeldResume(authorized)) return false
        val sleepPlayRequest = sleepTimer.onPlayRequest(explicit)
        if (!authorized()) {
            sleepTimer.clearPlayRequest(sleepPlayRequest)
            return false
        }
        val requestedPlay = if (bypassFreshness) {
            resumeLoadedCurrent(authorized)
        } else {
            when (val prepared = resumeFreshness.preparePlay()) {
                ResumePlayPreparation.Bypass -> resumeLoadedCurrent(authorized)
                ResumePlayPreparation.Superseded -> false
                is ResumePlayPreparation.Ready -> applyFreshnessPlan(prepared.plan, authorized)
            }
        }
        if (!requestedPlay) sleepTimer.clearPlayRequest(sleepPlayRequest)
        return requestedPlay
    }

    /**
     * Issue #88 — replaces an idle metadata holder with a fresh playable /play queue only after Play intent.
     *
     * A holder deliberately contains no stream URI. Re-check identity, lock state and controller generation
     * after the network suspension so a profile switch, Pause, Stop, seek or media replacement always wins.
     */
    private suspend fun materializeHeldResume(stillAuthorized: () -> Boolean): Boolean {
        val target = heldResumeTarget() ?: return true
        val profileId = target.profileId
        if (profileId == null || !stillAuthorized() || lock.isActiveProfileLocked()) return false
        val claim = withContext(mainDispatcher) { deferredWork.capture() } ?: return false
        val prepared = prepareQueue(target.bookId, startAt = null)
        return prepared != null && installMaterializedResume(target, prepared, claim, stillAuthorized)
    }

    private suspend fun heldResumeTarget(): HeldResumeTarget? = withContext(mainDispatcher) {
        val item = player?.currentMediaItem ?: return@withContext null
        if (!MediaItems.isResumePlaceholder(item)) return@withContext null
        HeldResumeTarget(
            profileId = MediaItems.ownerOf(item),
            bookId = MediaItems.bookIdOf(item),
        )
    }

    private suspend fun installMaterializedResume(
        target: HeldResumeTarget,
        prepared: CarPostConnectRestorer.Prepared,
        claim: DeferredPlaybackWork.Claim,
        stillAuthorized: () -> Boolean,
    ): Boolean = withContext(mainDispatcher) {
        val authorized: suspend () -> Boolean = {
            val allowed = stillAuthorized() && !lock.isActiveProfileLocked() &&
                target.profileId?.let { auto.isActiveProfile(it) } == true &&
                prepared.session.profileId == target.profileId
            val item = player?.currentMediaItem
            allowed && stillAuthorized() && deferredWork.isMutationCurrent(claim) && item != null &&
                MediaItems.isResumePlaceholder(item) &&
                MediaItems.ownerOf(item) == target.profileId && MediaItems.bookIdOf(item) == target.bookId
        }
        bookChanges.acceptBook(prepared.session, authorized) {
            claim.player.setMediaItem(prepared.queue.item, prepared.queue.startPositionMs)
            logger.info(LogCategory.Playback, "Materialized the held resume book for explicit Play")
        }
    }

    private data class HeldResumeTarget(val profileId: ProfileId?, val bookId: LibraryItemId)

    private suspend fun applyFreshnessPlan(
        plan: ResumeFreshnessPlan,
        stillAuthorized: () -> Boolean = { true },
    ): Boolean {
        return when (val decision = plan.decision) {
            is ResumeFreshnessDecision.Current ->
                resumeFreshness.withCurrentPlan(plan) {
                    if (!stillAuthorized()) {
                        false
                    } else {
                        recordFreshnessCheck(plan)
                        resumeLoadedCurrent(stillAuthorized)
                    }
                } ?: false

            is ResumeFreshnessDecision.Adopt -> {
                if (!stillAuthorized()) return false
                val outcome = resumeFreshness.withCurrentPlan(plan) {
                    if (!stillAuthorized()) {
                        null
                    } else {
                        recordFreshnessCheck(plan)
                        resumeAt(plan, decision.position, stillAuthorized)
                    }
                } ?: return false
                recordRemoteProgress(plan, outcome)
                outcome == ResumeOutcome.Resumed
            }
        }
    }

    /** Local resume after the shared freshness decision, using the same guarded command boundaries. */
    private suspend fun resumeLoadedCurrent(stillAuthorized: () -> Boolean = { true }): Boolean =
        withContext(mainDispatcher) {
            if (!stillAuthorized()) return@withContext false
            val current = player ?: return@withContext false
            if (current.mediaItemCount == 0) return@withContext false
            if ((current.playbackState == Player.STATE_IDLE || current.playerError != null) &&
                !playbackCommandEffects.prepare(current, stillAuthorized)
            ) {
                return@withContext false
            }
            if (!stillAuthorized()) return@withContext false
            // The freshness network request may take seconds. Start the muted route
            // probe only NOW, immediately before the actual raw ExoPlayer Play.
            playbackCommandEffects.play(current, stillAuthorized)
        }

    private suspend fun prepareCurrentOutput(
        expectedCommandGeneration: Long,
        expectedSelectionSequence: Long,
    ): Boolean = withContext(mainDispatcher) {
        val current = player ?: return@withContext false
        playbackCommandEffects.prepare(
            current,
            stillAuthorized = {
                controllerCommandGeneration == expectedCommandGeneration &&
                    currentExplicitSelectionSequence() == expectedSelectionSequence
            },
        )
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
    private suspend fun resumeAt(
        plan: ResumeFreshnessPlan,
        target: Duration,
        stillAuthorized: () -> Boolean = { true },
    ): ResumeOutcome = withContext(mainDispatcher) {
        val current = player ?: return@withContext ResumeOutcome.NotLoaded
        val outcome = OwnedPlayer(current, plan, stillAuthorized).seekAndResume(
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
     * `AtomicResumeTest` verifies prepare/seek/confirm/Play ordering. Command helpers additionally enforce
     * route preparation and current ownership around synchronous callbacks; seek confirmation still reads
     * this exact raw player's events on its application thread.
     */
    private inner class OwnedPlayer(
        private val media: ExoPlayer,
        private val plan: ResumeFreshnessPlan,
        private val stillAuthorized: () -> Boolean,
    ) : ResumeTarget {

        override fun loadedBookId(): LibraryItemId? =
            media.currentMediaItem?.takeIf { media.mediaItemCount > 0 }?.let(MediaItems::bookIdOf)

        override fun needsPreparing(): Boolean = media.playbackState == Player.STATE_IDLE || media.playerError != null

        override fun prepare(): Boolean = playbackCommandEffects.prepare(media, stillAuthorized, holdForSeek = true)

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
            playbackCommandEffects.play(media, stillAuthorized)
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
        override fun onPlaybackParametersChanged(parameters: androidx.media3.common.PlaybackParameters) {
            autoTraces.log(
                "Media3 playback parameters changed",
                fields = listOf(
                    LogField.Public("speedMilli", (parameters.speed * PLAYBACK_PARAMETER_SCALE).toInt()),
                    LogField.Public("pitchMilli", (parameters.pitch * PLAYBACK_PARAMETER_SCALE).toInt()),
                ),
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) autoRewind.onResumed()
            audioTrackRouteMonitor.sample()
            audioOutputs.verifyOutputLock()
            autoTraces.log(
                "Media3 playback activity changed",
                fields = listOf(LogField.Public("isPlaying", isPlaying)) +
                    AndroidAutoDiagnostics.playerFields(player) + carSnapshotFields(),
            )
            // The listening-time interval closes before the sync reads it, or a pause would report less
            // listening than happened.
            sessionSync.onPlayingChanged(isPlaying)
            // BW-SLEEP-01 — service lifecycle reports playback; the controller owns schedule policy.
            sleepTimer.onPlaybackChanged(isPlaying)
            if (isPlaying) {
                // Audio is coming out, so whatever went wrong is over and the next failure starts from one.
                recovery.onPlaying()
                pendingRecoveryRetry?.cancel()
                // ROUTE-002 — real playback is the gate for creating route-heard evidence.
                syncExplicitOutputIntent()
                observeCurrentAudibleRoute()
                // PRODUCT_SPEC SYNC-002 — the book is moving again, so the position it was resting at is no
                // longer a description of where this device is. See `ResumeBaseline.onLocalMove`.
                resumeBaseline.onLocalMove()
                // PRODUCT_SPEC PLAY-009 — before anything else on the resume path: a rewind that landed after
                // playback had started would be audible as a stutter.
                // Rewind runs above, before any route proof can release verification mute.
            } else {
                // PRODUCT_SPEC SYNC-002 — capture, journal, then *await* the sync that acknowledges it. See
                // `onCameToRest` for why the awaiting is the point, and why it is not on this service's
                // scope.
                val snapshot = positionSnapshot()
                val claim = deferredWork.capture()
                applicationScope.launch(mainDispatcher) { onCameToRest(snapshot, claim) }
            }
        }

        /**
         * PRODUCT_SPEC PLAY-009 — why playback stopped, which decides whether a rewind may follow.
         *
         * `onIsPlayingChanged` does not carry a reason, and the reason is the requirement: an audio-focus loss
         * is not a pause the listener asked for, and rewinding out of one would replay ten seconds every time
         * a satnav spoke.
         */
        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            autoTraces.log(
                "Media3 playback suppression changed",
                fields = listOf(LogField.Public("reasonCode", playbackSuppressionReason)) +
                    AndroidAutoDiagnostics.playerFields(player) + carSnapshotFields(),
            )
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Record transport intent, not buffer or route changes, and never invent a physical key origin.
            val routeSafetyPause = !playWhenReady && audioOutputs.isRouteSafetyPause(player)
            val input = PlaybackTransportInput.interpret(
                playWhenReady,
                reason,
                routeSafetyPause = routeSafetyPause,
            )
            carResumeEffects.cancelPending()
            if (!routeSafetyPause) recordTransport(if (input.isPlay) PlaybackEvent.Play else PlaybackEvent.Pause)
            logger.debug(
                LogCategory.Playback,
                "Playback was asked to change",
                LogField.Public("playWhenReady", playWhenReady.toString()),
                LogField.Public("reason", input.reasonName),
            )
            if (input.isPlay) {
                // New Play supersedes any pending continuity result, including a repeated system Play.
                carRecovery.cancelPending()
                return
            }

            autoRewind.onPaused(wasUserInitiated = input.isUserPause)
            val interruption = input.interruptionReason
            if (interruption != null) {
                onCarPlatformInterruption(interruption)
            } else {
                // Deliberate pause, end-of-item or unknown causes invalidate recovery evidence.
                carRecovery.cancelAll()
                carRecovery.clearAwaiting()
            }
        }

        /**
         * Platform pause is observable once; becoming-noisy can suppress a later focus-loss transition.
         * The gate pairs either event with a car lifecycle boundary before any automatic Play.
         */
        private fun onCarPlatformInterruption(reason: Int) {
            val focusLoss = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
            val source = if (focusLoss) "audio-focus-loss" else "becoming-noisy"
            val trace = autoTraces.ensure(source)
            autoTraces.log(
                "Android Auto lifecycle input",
                trace,
                buildList {
                    add(LogField.Public("event", source))
                    addAll(AndroidAutoDiagnostics.playerFields(player))
                    addAll(carSnapshotFields())
                },
            )
            syncExplicitOutputIntent()
            val decision = carRecovery.platformInterruption(
                clock.elapsed(),
                continuityInputs(),
                focusLoss,
            )
            logCarContinuityDecision(source, decision)
            decision.target
                ?.takeIf { decision.status == CarArrivalResumeGate.Status.Ready }
                ?.let(carResumeEffects::onRecoveryReady)
            if (!focusLoss) carRecovery.clearAwaiting()
        }

        /**
         * A track boundary. Chapter crossings are also observed by the UI/controller and by the service's
         * History sampler. Chapter lists remain in-process rather than duplicated in MediaItem extras.
         */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            positionEffects.retireForItemTransition()
            mediaItemGeneration += 1
            pendingRecoveryRetry?.cancel()
            autoTraces.activeTrace?.let { trace ->
                autoTraces.log(
                    "Media3 current item changed during Android Auto trace",
                    trace,
                    buildList {
                        add(LogField.Public("reason", AndroidAutoDiagnostics.mediaTransitionReason(reason)))
                        addAll(AndroidAutoDiagnostics.itemFields(mediaItem, prefix = "new"))
                        addAll(AndroidAutoDiagnostics.playerFields(player))
                    },
                )
            }
            recovery.onBookChanged()
            // PRODUCT_SPEC PLAY-006 — the startup stopwatch starts here rather than at `prepare()`, because
            // this fires for every book including one started from a car or by a media button, and the wait
            // to hear a book is the wait for *that* book.
            if (mediaItem != null && !MediaItems.isResumePlaceholder(mediaItem)) metrics.onItemPrepared()
            // PRODUCT_SPEC PLAY-002 — route evidence belongs only to this loaded book generation.
            routeOwnership.onBookChanged(mediaItem != null)
            audioOutputs.clearMeasuredPlayingHeadsetProtection()
            // Retire the old recovery before observing playback into the incoming book's generation.
            clearCarContinuity()
            if (mediaItem != null) observeCurrentAudibleRoute()
            // PRODUCT_SPEC SYNC-002 — a baseline is per book and per position, and this is both changing.
            resumeBaseline.onBookClosed()
            // Record before the sync that follows it, so the row the sync uploads is this item's own.
            val snapshot = positionSnapshot()
            val claim = deferredWork.capture()
            scope.launch {
                if (snapshot != null) recordPosition(snapshot)
                sessionSync.sync(SyncTrigger.TrackChanged) { claim != null && deferredWork.isCurrent(claim) }
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
                val current = player ?: return
                if (positionEffects.onSeek(current)) return
                // PRODUCT_SPEC PLAY-009 — "rewind is not applied after a user seek". A listener who chose a
                // position chose it; moving it afterwards is the app overruling them.
                autoRewind.onSeeked()
                // PRODUCT_SPEC SYNC-002 — the book has moved, so whatever pause was acknowledged no longer
                // describes it. Including the adopting seek itself: the pause it belonged to is over.
                resumeBaseline.onLocalMove()
                // Record before the sync that follows it, so the row the sync uploads is the seek's own.
                val snapshot = positionSnapshot()
                val claim = deferredWork.capture()
                scope.launch {
                    if (snapshot != null) recordPosition(snapshot)
                    sessionSync.sync(SyncTrigger.SeekCompleted) { claim != null && deferredWork.isCurrent(claim) }
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
                val snapshot = positionSnapshot()
                val endedClaim = deferredWork.capture()
                // PRODUCT_SPEC 6.4 step 6 — after the position is journalled and the sync is asked for,
                // never before: the book that just ended has to be recorded as finished whether or not
                // anything follows it, and an advance that failed must not have cost the last write.
                // Capture the ended item before scheduling work; a newer manual selection must not
                // become the "finished" book when this coroutine eventually starts.
                scope.launch {
                    if (snapshot != null) recordPosition(snapshot)
                    sessionSync.sync(SyncTrigger.BookChanged) {
                        endedClaim != null && deferredWork.isCurrent(endedClaim)
                    }
                    if (endedClaim != null) advanceToNextInSeries(endedClaim)
                }
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
            pendingRecoveryRetry?.cancel()
            val retryIn = recovery.onError(error)
            logger.warn(
                LogCategory.Playback,
                if (retryIn == null) "Playback stopped on an error" else "Playback hit an error and will retry",
                LogField.Public("errorCode", error.errorCodeName),
                LogField.Count("attempt", recovery.attemptCount),
            )
            val snapshot = positionSnapshot()
            scope.launch { if (snapshot != null) recordPosition(snapshot) }
            reportFailureToControllers(error, willRetry = retryIn != null)
            val claim = deferredWork.capture() ?: return
            if (retryIn == null) return
            pendingRecoveryRetry = scope.launch {
                // The retry belongs to this exact failed item, not whichever book is selected later.
                // Only an idle, still-errored player is re-prepared; never start an unrelated book.
                deferredWork.retryIfCurrent(claim, retryIn)
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
                // Also projects the countdown (or restores the title), see publishMediaButtons.
                publishMediaButtons()
            }
        }
    }

    /**
     * BW-SLEEP-02 / PRODUCT_SPEC PLAY-008 — make the countdown visible in the primary text of compact and
     * expanded system media controls, not merely in a custom action label or secondary byline.
     *
     * Physical acceptance showed that those secondary surfaces can be icon-only or hidden by System UI.
     * The timer therefore uses a compact clock label and [SleepTimerMediaMetadata] puts it first in the
     * current item's primary title while preserving the exact ordinary book metadata for restoration.
     */
    private fun publishSleepTimerMetadata(timer: SleepTimerState) {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        // PD-002 (2026-10-03): never while a car controller is bound; the book title stays.
        val timerLabel = SleepTimerMediaMetadata.projectionLabel(timer, carConnections.isConnected())
        val replacement = SleepTimerMediaMetadata.project(item, timerLabel) ?: return
        val index = current.currentMediaItemIndex
        if (index !in 0 until current.mediaItemCount) return
        current.replaceMediaItem(index, replacement)
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
     * PRODUCT_SPEC PLAY-002 — observe Android route-policy changes and explicit listener intent separately.
     *
     * A repeated Automatic choice is newer intent even when [AudioOutputRouter.selectedId] stays null, while
     * a framework route callback remains weaker evidence. Both paths feed the single [routeOwnership] owner.
     */
    private fun observeAudioOutputs() {
        outputWatch = scope.launch {
            launch {
                combine(audioOutputs.outputs, audioOutputs.selectedId, ::Pair).collect { (outputs, _) ->
                    syncExplicitOutputIntent()
                    val isPlaying = routeObservationEffects.isAudible()
                    if ((player?.mediaItemCount ?: 0) == 0) {
                        routeOwnership.onQueueEmptied()
                        audioOutputs.clearMeasuredPlayingHeadsetProtection()
                        clearCarContinuity()
                    } else {
                        routeOwnership.onOutputsChanged(outputs, isPlaying = isPlaying)
                        audioOutputs.verifyOutputLock()
                        if (isPlaying) observeCarContinuityHeadset(outputs)
                    }
                    republishOutputButtons()
                }
            }
            launch {
                audioOutputs.explicitSelection.collect { selection ->
                    if (selection != null) {
                        syncExplicitOutputIntent()
                        // #34 — Car and generic Automatic both have selectedId=null, so the selected-id flow
                        // cannot announce that semantic transition. Explicit intent must republish directly.
                        republishOutputButtons()
                    }
                }
            }
        }
    }

    private fun syncExplicitOutputIntent() {
        val selection = audioOutputs.explicitSelection.value ?: return
        val changed = routeOwnership.onExplicitSelection(
            selection = selection,
            outputs = audioOutputs.outputs.value,
            isAudible = routeObservationEffects.isAudible(),
        )
        if (changed) {
            clearCarContinuity()
            sleepTimer.onControllerTransportIntent()
        }
    }

    private fun currentExplicitSelectionSequence(): Long = audioOutputs.explicitSelection.value?.sequence ?: 0L

    private fun observeCurrentAudibleRoute(target: Player? = player) {
        routeObservationEffects.observe(target)
    }

    private fun onRouteAudible() {
        val outputs = audioOutputs.outputs.value
        routeOwnership.onPlaybackObserved(outputs)
        observeCarContinuityHeadset(outputs)
        carRecovery.playbackBecameActive(
            routeOwnership.currentGeneration,
            currentExplicitSelectionSequence(),
        )?.let { recoveryTarget ->
            autoTraces.log(
                "Car lifecycle continuity playback became active",
                fields = listOf(
                    LogField.Public("phase", recoveryTarget.phase.name),
                    LogField.Public("kind", recoveryTarget.outputId.substringBefore(':')),
                ) + carSnapshotFields(),
            )
        }
    }

    /**
     * Refreshes #36's car-session continuity target only from positive route evidence while audio is playing.
     *
     * A transient missing device intentionally supplies no negative evidence here: the route owner may retire
     * its live record, while the continuity owner retains the last positively proven target until newer
     * listener/book/playback intent makes it ineligible.
     */
    private fun continuityInputs(outputs: List<AudioOutput> = audioOutputs.outputs.value) =
        CarContinuityRecoveryCoordinator.Inputs(
            heardRoute = routeOwnership.heardRoute,
            headsetId = routeOwnership.headsetForCar(outputs),
            generation = routeOwnership.currentGeneration,
            selectionSequence = currentExplicitSelectionSequence(),
            carConnected = carLifecycle.sessionEstablished,
        )

    private fun observeCarContinuityHeadset(outputs: List<AudioOutput>) {
        val generation = routeOwnership.currentGeneration
        val headset = routeOwnership.measuredPlayingHeadsetForContinuity(outputs)
        if (generation != null && headset != null) {
            audioOutputs.protectMeasuredPlayingHeadset(
                headset,
                generation,
                currentExplicitSelectionSequence(),
            ) {
                routeOwnership.currentGeneration == generation &&
                    routeOwnership.measuredPlayingHeadsetForContinuity(audioOutputs.outputs.value) == headset
            }
        }
        carRecovery.observePlayingHeadset(continuityInputs(outputs))
    }

    /**
     * Recomputes the two output buttons and rewrites the notification only if they moved.
     *
     * Called from the output collector and from both halves of a car binding, because a car is a reason the
     * car button appears without any audio device having changed.
     */
    private fun republishOutputButtons() {
        val outputs = audioOutputs.outputs.value
        val carBound = carConnections.isConnected()
        val next = AudioOutputRoles.buttons(
            outputs = outputs,
            selectedId = audioOutputs.selectedId.value,
            carConnected = carBound,
            carRequested = audioOutputs.explicitSelection.value?.destination ==
                OutputIntentOwner.Destination.Car,
        )
        if (!mediaButtonPublishing.needsPublish(next, carBound)) {
            outputButtons = next
            return
        }
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
        val fields = listOf(
            LogField.Public("api", Build.VERSION.SDK_INT),
            LogField.Public("carBound", carConnections.isConnected()),
            LogField.Public("routeKnown", outputs.any(AudioOutput::isActive)),
            LogField.Public(
                "outputs",
                outputs.joinToString("+") { output ->
                    "${output.role}${if (output.isActive) "*" else ""}"
                },
            ),
            LogField.Public("onCar", state.onCar),
            LogField.Public("onHeadset", state.onHeadset),
        )
        logger.log(
            LogEvent(
                level = LogLevel.Info,
                category = LogCategory.Playback,
                message = "The car output actions were recomputed",
                fields = fields,
            ),
        )
        if (autoTraces.activeTrace != null) {
            autoTraces.log(
                "The car output actions were recomputed",
                fields = fields + carSnapshotFields(),
            )
        }
    }

    /** Recovery-to-Play effects share one coordinator claim; no second lifecycle or player. */
    private val carResumeEffects by lazy {
        CarContinuityResumeEffects(
            recovery = carRecovery,
            outputs = audioOutputs,
            owner = routeOwnership,
            trace = { message, fields -> autoTraces.log(message, fields = fields) },
            currentPlayer = { player },
            selectionSequence = ::currentExplicitSelectionSequence,
            snapshot = ::carSnapshotFields,
            resumePlayer = { current, stillAuthorized, beforeRawPlay ->
                if (!stillAuthorized()) {
                    false
                } else {
                    sleepTimer.onServiceResumeIntent()
                    playbackCommandEffects.play(current, stillAuthorized, beforeRawPlay)
                }
            },
            scope = scope,
            synchronizeOutputIntent = ::syncExplicitOutputIntent,
            onOutputsChanged = ::republishOutputButtons,
        )
    }

    private fun carSnapshotFields(): List<LogField> = listOf(
        LogField.Public("projection", carLifecycle.projectionState.name),
        LogField.Public("projectionOwnsLifecycle", carLifecycle.projectionOwnsCarLifecycle),
        LogField.Public("sessionEstablished", carLifecycle.sessionEstablished),
        LogField.Public("carBound", carConnections.isConnected()),
        LogField.Public("continuityPlayAwaiting", carRecovery.isPlayAwaiting),
        LogField.Public("generation", routeOwnership.currentGeneration?.toString() ?: "none"),
        LogField.Public("selectionSequence", currentExplicitSelectionSequence()),
    )

    private fun logCarContinuityDecision(source: String, decision: CarArrivalResumeGate.Decision) {
        autoTraces.log(
            "Car lifecycle continuity decision",
            fields = buildList {
                add(LogField.Public("source", source))
                add(LogField.Public("phase", decision.phase.name))
                add(LogField.Public("status", decision.status.name))
                add(LogField.Public("reason", decision.reason.name))
                add(LogField.Public("kind", decision.target?.outputId?.substringBefore(':') ?: "none"))
                addAll(carSnapshotFields())
            },
        )
    }

    private fun onCarControllerConnected(controllerPackage: String) {
        val trace = autoTraces.ensure("controller-connect")
        val carWasConnected = carConnections.isConnected()
        val carArrivedAt = if (carWasConnected) null else clock.elapsed()
        carLifecycle.onControllerConnected()
        // Preserve positive evidence before the host can change routing or focus; muted playback is ignored.
        observeCurrentAudibleRoute()
        // The first arrival must be registered synchronously. Routing resettle runs in a later coroutine,
        // and Media3 focus loss can arrive before that coroutine is scheduled.
        val arrivalDecision = carArrivedAt?.let { at ->
            carRecovery.carArrival(at, routeOwnership.currentGeneration, currentExplicitSelectionSequence())
        }
        arrivalDecision?.let { logCarContinuityDecision("first-car-bind", it) }
        synchronizeSleepTimerCarConnection()
        // PD-002: publish car-safe media buttons synchronously before Media3 publishes initial state.
        publishMediaButtons()
        autoTraces.log(
            "A car connected to the media session",
            trace,
            buildList {
                add(LogField.Public("controller", controllerPackage))
                add(LogField.Public("firstArrival", !carWasConnected))
                addAll(AndroidAutoDiagnostics.playerFields(player))
                addAll(carSnapshotFields())
            },
        )
        if (carWasConnected) {
            autoTraces.log(
                "A later car controller bind was ignored as a new arrival",
                trace,
                listOf(LogField.Public("controller", controllerPackage)) + carSnapshotFields(),
            )
        }
        carResumeEffects.onArrival(arrivalDecision)
    }

    private fun onCarControllerDisconnected(controllerPackage: String) {
        val trace = autoTraces.ensure("controller-disconnect")
        val carWasConnected = carConnections.isConnected()
        carLifecycle.onControllerDisconnected()
        val carStillConnected = carConnections.isConnected()
        synchronizeSleepTimerCarConnection()
        val finalControllerDeparture = carWasConnected && !carStillConnected
        autoTraces.log(
            "A car controller disconnected from the media session",
            trace,
            buildList {
                add(LogField.Public("controller", controllerPackage))
                add(LogField.Public("finalDeparture", finalControllerDeparture))
                add(LogField.Public("carStillConnected", carStillConnected))
                addAll(AndroidAutoDiagnostics.playerFields(player))
                addAll(carSnapshotFields())
            },
        )

        if (!finalControllerDeparture) {
            scope.launch { republishOutputButtons() }
            return
        }

        // Once a positive projection state has been observed for this drive, projection owns the physical
        // boundary. Legacy controllers may disappear/reappear independently and cannot end that car session.
        if (carLifecycle.projectionOwnsCarLifecycle) {
            scope.launch { republishOutputButtons() }
            return
        }

        // Without a positive projection edge the last controller is the fallback physical boundary.
        // The lifecycle authority invalidates the established-session latch before recovery assessment.
        carLifecycle.onFinalLegacyControllerDeparture()
        completeCarDeparture("final-car-disconnect")
    }

    private fun onCarProjectionUpdate(update: AndroidAutoProjectionMonitor.Update) {
        val previous = update.previous
        val trace = if (update.current.carConnected || previous?.carConnected == true) {
            autoTraces.ensure("projection-state")
        } else {
            autoTraces.activeTrace
        }
        carLifecycle.onProjectionReported(update.current)
        audioOutputs.refreshPresentationForLifecycle()
        autoTraces.log(
            "Android Auto projection state changed",
            trace,
            buildList {
                add(LogField.Public("previous", previous?.name ?: "Initial"))
                add(LogField.Public("current", update.current.name))
                addAll(AndroidAutoDiagnostics.playerFields(player))
                addAll(carSnapshotFields())
            },
        )

        val edge = carLifecycle.onProjectionBoundary(update)
        if (update.initial) {
            synchronizeSleepTimerCarConnection()
            return
        }
        when (edge) {
            CarLifecycleAuthority.ProjectionEdge.Arrival -> {
                // Focus may precede the stronger physical arrival; the service remains the Play owner.
                val decision = carRecovery.carArrival(
                    at = clock.elapsed(),
                    generation = routeOwnership.currentGeneration,
                    sequence = currentExplicitSelectionSequence(),
                )
                logCarContinuityDecision("projection-connect", decision)
                carResumeEffects.onArrival(decision)
            }

            CarLifecycleAuthority.ProjectionEdge.PhysicalDeparture -> {
                // Controller bindings have already been retired by the lifecycle authority.
                completeProjectionDeparture()
            }

            CarLifecycleAuthority.ProjectionEdge.None -> Unit
        }
        synchronizeSleepTimerCarConnection()
    }

    /** The timer owns eligibility; this adapter supplies controller/projection connection truth. */
    private fun synchronizeSleepTimerCarConnection() {
        sleepTimer.onCarConnectionChanged(carLifecycle.connectedForSleepTimer)
    }

    private fun completeProjectionDeparture() {
        val selection = audioOutputs.explicitSelection.value
        val decision = completeCarDeparture("projection-disconnect", physicalProjection = true)
        if (decision.status == CarArrivalResumeGate.Status.Rejected &&
            decision.reason == CarArrivalResumeGate.Reason.NoPlayingCarHeadset &&
            audioOutputs.handoffs.retirePausedCar(selection)
        ) {
            logger.info(
                LogCategory.Playback,
                "A paused Car destination expired after projection disconnection",
                LogField.Public("requestSelection", selection?.sequence?.toString() ?: "none"),
                LogField.Public("selectionSequence", currentExplicitSelectionSequence()),
            )
        }
    }

    private fun completeCarDeparture(
        source: String,
        physicalProjection: Boolean = false,
    ): CarArrivalResumeGate.Decision {
        val departedAt = clock.elapsed()
        val generation = routeOwnership.currentGeneration
        val selectionSequence = currentExplicitSelectionSequence()
        val decision = carRecovery.carDeparture(
            at = departedAt,
            generation = generation,
            sequence = selectionSequence,
            physicalProjection = physicalProjection,
        )
        logCarContinuityDecision(source, decision)
        carResumeEffects.onDeparture(decision)
        return decision
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
        val carBound = carConnections.isConnected()
        val buttons = mediaButtons(carBound)
        MediaButtonPublishing.publish(
            buttons = buttons,
            toAllControllers = current::setMediaButtonPreferences,
            toNotificationController = current.mediaNotificationControllerInfo?.let { controller ->
                { published -> current.setMediaButtonPreferences(controller, published) }
            },
        )
        mediaButtonPublishing.markPublished(outputButtons, carBound)
        // Every car transition and every timer tick reaches here, so the countdown projection follows the
        // car-bound state (PD-002, 2026-10-03). project() returns null when nothing changes.
        publishSleepTimerMetadata(sleepTimerState)
    }

    /**
     * PRODUCT_SPEC PLAY-002 / PLAY-007 — the shared system-media layout.
     *
     * Android Auto and API-33+ System UI consume the same media-button preferences, so issue #38 switches
     * priority from the actual car-controller binding state instead of pretending the surfaces can receive
     * simultaneous independent layouts. With no car bound, skips lead. While a car is bound, Car/Headset
     * lead when those actions are available.
     *
     * SLOT_BACK remains occupied in both states. Vacating it lets Media3 expose raw Previous again, which
     * can reach Player.seekToPrevious() and restart this single-window audiobook.
     */
    private fun mediaButtons(carBound: Boolean = carConnections.isConnected()): List<CommandButton> =
        MediaButtonLayout.inPriorityOrder(
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
            activeTimerActions = listOfNotNull(sleepTimerButton()),
            overflowActions = emptyList(),
            carBound = carBound,
        )

    /** PRODUCT_SPEC PLAY-008 — the running timer's remaining minutes, or `null` when no timer is set. */
    private fun sleepTimerButton(): CommandButton? =
        NotificationButtons.sleepTimerButton(sleepTimerState) { remaining ->
            getString(R.string.player_sleep_remaining, remaining.asMinutesLabel())
        }

    /** Media3 item command shown beside inactive profile rows where the host supports browse actions. */
    private fun profileSwitchButton(): CommandButton = CommandButton.Builder(CommandButton.ICON_ARTIST)
        .setDisplayName(getString(R.string.car_profile_use))
        .setSessionCommand(SessionCommand(AutoLibrary.ACTION_SWITCH_PROFILE, Bundle.EMPTY))
        .build()

    /**
     * PRODUCT_SPEC PLAY-002 — the car button and the headset button, or as many of them as apply.
     *
     * **These hold the car's two app-claimable bar positions while a car controller is bound.** Android Auto
     * reserves the *previous* and *next* positions and hands them to an app's custom actions when the app
     * does not advertise those transport commands — which BookWave does not, because a book is one timeline
     * window (ADR-0016). Two earlier attempts asked for the secondary slots instead; a device run showed
     * the bar unchanged, because the legacy conversion a car is served by branches on the back, forward and
     * overflow slots and on nothing else. So these now name the real primary slots.
     *
     * Issue #38 narrows the original trade: the skips move to overflow only while a car controller is
     * actually bound. When no car is bound the same shared layout is reordered so skips reclaim the phone's
     * compact slots; Car/Headset remain available in overflow where Media3 supports it.
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
                    action = NotificationButtons.carAction(state.onCar),
                    label = getString(
                        if (state.onCar) R.string.player_car_action_active else R.string.player_car_action,
                    ),
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
        val at = effectiveBookPosition(current)
        // PRODUCT_SPEC 6.5 — the owner is read now, on the main thread with the position, not inside the
        // launch. See [flushProgress] for why the far end of an application-scoped launch is the wrong
        // place to ask who is signed in.
        val owner = MediaItems.ownerOf(item)
        val happenedAt = clock.now()
        applicationScope.launch { history.record(bookId, event, from = null, to = at, at = happenedAt, owner = owner) }
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
        return NowPlaying(bookId, effectiveBookPosition(current))
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
        return MediaSession.MediaItemsWithStartPosition(
            listOf(loaded),
            0,
            effectiveBookPosition(current).inWholeMilliseconds.coerceAtLeast(0L),
        )
    }

    private val callbackFutures by lazy { SessionCallbackFutures(applicationScope, mainDispatcher, logger) }

    private val controllerQueueResolution by lazy {
        ControllerQueueResolution(
            commandGeneration = { controllerCommandGeneration },
            profileGeneration = auto::activeProfileGeneration,
            explicitSelectionSequence = ::currentExplicitSelectionSequence,
            serviceOwner = { player.takeIf { scope.isActive } },
            activeProfileId = auto::activeProfileId,
            isProfileLocked = lock::isActiveProfileLocked,
            prepare = ::prepareQueue,
            accept = { prepared, authorized, install ->
                bookChanges.acceptBook(prepared.session, authorized, install)
            },
        )
    }

    private suspend fun resolveQueue(item: MediaItem, claim: ControllerQueueResolution.Claim): MediaItems.Queue? {
        controllerQueueResolution.requireCurrent(claim)
        val target = AutoLibrary.resolve(item.mediaId) ?: return null
        return controllerQueueResolution.resolve(claim, target.bookId, target.startAt)
    }

    /**
     * PRODUCT_SPEC 6.4 step 6 — plays the next book in the series, if there is one and the listener wants it.
     *
     * ### Why the listener captures the finished item before launching
     *
     * `STATE_ENDED` says the timeline ran out, not which book it was. Its current item is captured in
     * the synchronous Media3 listener turn; a delayed coroutine must never reinterpret a manually
     * selected replacement as the book that ended. The claim also owns the controller, item and profile
     * generations until the guarded commit.
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
    // Keep race-owner checks explicit at every await rather than nesting stale-state fallthrough.
    @Suppress("ReturnCount")
    private suspend fun advanceToNextInSeries(claim: DeferredPlaybackWork.Claim) {
        if (!deferredWork.isCurrent(claim) || claim.player.playbackState != Player.STATE_ENDED) return
        val finished = claim.item?.let(MediaItems::bookIdOf) ?: return
        val profileId = auto.activeProfileId() ?: return
        if (!deferredWork.isCurrent(claim)) return
        val next = nextInSeries(finished) ?: return
        if (!deferredWork.isCurrent(claim)) return

        // Do not publish a session for an automatic candidate before it wins. Both the next-book lookup
        // and /play may suspend while the listener chooses a different book from phone or Android Auto.
        val prepared = prepareQueue(next.id, startAt = null) ?: return
        if (prepared.session.profileId != profileId ||
            MediaItems.ownerOf(claim.item)?.let { it != profileId } == true
        ) {
            return
        }
        val installed = deferredWork.installIfCurrent(
            claim = claim,
            prepared = prepared,
            startPlaying = true,
            condition = { it.playbackState == Player.STATE_ENDED },
            allowed = { auto.isActiveProfile(profileId) && !lock.isActiveProfileLocked() },
        )
        if (installed) {
            // No title or id (14.5). Only the fact of the advance is safe to log.
            logger.info(LogCategory.Playback, "A book ended, so the next in its series was started")
        }
    }

    /** Resolves media without publishing a book change; car restore owns a later guarded acceptance. */
    private suspend fun prepareQueue(bookId: LibraryItemId, startAt: Duration?): CarPostConnectRestorer.Prepared? =
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
                val queue = MediaItems.queueFor(playbackSession)
                val positioned = if (startAt == null) {
                    queue
                } else {
                    queue.copy(startPositionMs = startAt.inWholeMilliseconds.coerceAtLeast(0))
                }
                CarPostConnectRestorer.Prepared(playbackSession, positioned)
            }
        }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> = callbackFutures.submit(block)

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
        val at = Bookmark.roundedFrom(effectiveBookPosition(current))
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
        val target = effectiveBookPosition(current) + delta
        // Issue #91 — this custom notification command bypasses ResumeFreshnessPlayer.handleSeek.
        val finished = positionEffects.before(PlaybackMovement.Seek) ?: return
        try {
            if (!onControllerCommand(ResumeInvalidation.NotificationSkip)) return
            current.seekTo(target.inWholeMilliseconds.coerceAtLeast(0))
        } finally {
            finished()
        }
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

        /** #174 — car profile selections run one at a time; see [switchProfileFromCar]. */
        private val resumption = PlaybackResumptionCallbacks(
            lastPlayedBookId = { auto.lastPlayedAfter(::refreshResumeAccount)?.id },
            resumeItem = auto::resumeItem,
            resolution = controllerQueueResolution,
            lock = lock,
            coldResumeStartPosition = coldResumeStartPosition,
            logger = logger,
        )

        private val profileSwitcher = CarProfileSwitcher(
            isActive = auto::isActiveProfile,
            switch = { switchProfile(it) },
            restore = { profileId -> scope.launch { restoreProfilePlayback(profileId) } },
            unavailableMessage = { getString(R.string.car_profile_unavailable) },
            unlockMessage = { getString(R.string.car_profile_unlock_phone) },
            failedMessage = { getString(R.string.car_profile_switch_failed) },
        )

        private val browse = LibraryBrowseCallbacks(
            auto = auto,
            futures = callbackFutures,
            autoTraces = autoTraces,
            clock = clock,
            logger = logger,
            nowPlaying = ::nowPlaying,
            player = { player },
            mayBrowse = { session, controller -> session.mayBrowse(controller) },
            carPackages = CAR_PACKAGES,
        )

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = browse.onGetLibraryRoot(session, browser, params)

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            browse.onGetChildren(session, browser, parentId, page, pageSize, params)

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = browse.onGetItem(session, browser, mediaId)

        @Suppress("ForbiddenVoid")
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = browse.onSearch(session, browser, query, params)

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            browse.onGetSearchResult(session, browser, query, page, pageSize, params)

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
        ): ListenableFuture<MutableList<MediaItem>> {
            val trace = autoTraces.forController(controller.packageName, CAR_PACKAGES)
            autoTraces.logItems(trace, "onAddMediaItems", "asked", mediaItems)
            if (!onControllerCommand(ResumeInvalidation.MediaChanged)) return Futures.immediateCancelledFuture()
            val claim = controllerQueueResolution.capture()
            return future {
                controllerQueueResolution.requireCurrent(claim)
                val trusted = controller.isThisApplication()
                val matches = mediaItems.mapNotNull { item ->
                    if (trusted && MediaItems.isReadyToPlay(item)) {
                        Triple(item, item, null)
                    } else {
                        AutoLibrary.resolve(item.mediaId)?.let { target ->
                            controllerQueueResolution.prepareForRequest(claim, target.bookId, target.startAt)
                        }?.let { prepared -> Triple(item, prepared.queue.item, prepared) }
                    }
                }
                val prepared = matches.firstOrNull()?.third
                if (prepared == null) {
                    controllerQueueResolution.requireCurrent(claim)
                } else {
                    controllerQueueResolution.acceptPrepared(claim, prepared)
                }
                val resolved = matches.map { it.second }.toMutableList()
                logSelection(
                    callback = "onAddMediaItems",
                    asked = mediaItems,
                    selection = Selection(
                        branch = if (trusted) "passthrough" else "browse",
                        resolved = matches.isNotEmpty(),
                        kind = actedKind(mediaItems, matches.firstOrNull()?.first),
                        answer = MediaSession.MediaItemsWithStartPosition(resolved.toList(), 0, 0L),
                    ),
                    trace = trace,
                )
                autoTraces.logItems(trace, "onAddMediaItems", "returned", resolved)
                resolved
            }
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
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val trace = autoTraces.forController(controller.packageName, CAR_PACKAGES)
            autoTraces.logItems(
                trace = trace,
                callback = "onSetMediaItems",
                direction = "asked",
                items = mediaItems,
                extraFields = listOf(
                    LogField.Public("startIndex", startIndex),
                    LogField.Millis("startAt", startPositionMs),
                ),
            )
            AutoLibrary.profileSelectionOf(mediaItems)?.let { profileId ->
                return selectProfileFromRow(session, controller, profileId)
            }
            if (!onControllerCommand(ResumeInvalidation.MediaChanged)) return Futures.immediateCancelledFuture()
            val claim = controllerQueueResolution.capture()
            return future {
                setMediaItems(session, controller, mediaItems, startIndex, startPositionMs, trace, claim)
            }
        }

        /**
         * #174 — selecting a Profiles row is the profile switch itself, and never a media request.
         *
         * Android Auto turns a tap on a playable row into `playFromMediaId`, which Media3 delivers to
         * [onSetMediaItems]. A profile is not audio, so [ProfileRowSelection] never answers with media: no set,
         * no prepare and no Play follow, which keeps the tap from starting or replacing a book (PRODUCT_SPEC
         * 6.5.3: a switch pauses, it never plays).
         *
         * The switch is the one the row's media-item command already runs, so the lock policy, flush/pause
         * and the browse invalidation that follows are not re-implemented for the tap. A refusal reaches the
         * car as the session error the command would have returned.
         */
        private fun selectProfileFromRow(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            profileId: ProfileId,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (!session.mayBrowse(controller)) {
                denied(controller, "onSetMediaItems")
                return ProfileRowSelection.refused()
            }
            return ProfileRowSelection.answer(
                switched = future { switchProfileFromCar(profileId) },
                whenSwitchThrows = {
                    val message = getString(R.string.car_profile_switch_failed)
                    SessionResult(SessionError(SessionError.ERROR_UNKNOWN, message))
                },
            ) { result ->
                // PRODUCT_SPEC 14.5 — the outcome code only: which profile was chosen is not a log fact.
                logger.info(
                    LogCategory.Playback,
                    "A car selected a profile row",
                    LogField.Public("controller", controller.packageName),
                    LogField.Public("resultCode", result.resultCode),
                )
                result.sessionError?.let { error -> session.sendError(controller, error) }
            }
        }

        private suspend fun setMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
            trace: AndroidAutoTraceRecorder.Trace?,
            claim: ControllerQueueResolution.Claim,
        ): MediaSession.MediaItemsWithStartPosition {
            controllerQueueResolution.requireCurrent(claim)
            val spokenRequest = mediaItems
                .firstNotNullOfOrNull { item -> item.requestMetadata.searchQuery?.let { q -> item to q } }
                ?.takeIf { session.mayBrowse(controller) }
            val query = spokenRequest?.second
            val spoken = query?.let { asked -> auto.search(asked).firstOrNull() }
            val selection = when {
                query != null -> spoken?.let { match ->
                    resolveQueue(match, claim)
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
                            resolveQueue(item, claim)?.let { queue -> item to queue }
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
            if (!selection.resolved) controllerQueueResolution.requireCurrent(claim)
            logSelection("onSetMediaItems", mediaItems, selection, trace)
            traceSetMediaItemsResult(trace, selection)
            return selection.answer
        }

        private fun traceSetMediaItemsResult(trace: AndroidAutoTraceRecorder.Trace?, selection: Selection) {
            autoTraces.logItems(
                trace = trace,
                callback = "onSetMediaItems",
                direction = "returned",
                items = selection.answer.mediaItems,
                extraFields = listOf(
                    LogField.Public("startIndex", selection.answer.startIndex),
                    LogField.Millis("startAt", selection.answer.startPositionMs),
                ),
            )
        }

        private fun actedKind(asked: List<MediaItem>, acted: MediaItem? = null): String =
            (acted ?: asked.firstOrNull())?.mediaId?.let(AutoLibrary::kindOf) ?: "none"

        /**
         * Selection diagnostics intentionally carry only public routing facts: callback/branch/kind, counts,
         * resolution status and a numeric start position. They never log a search query, title or stream URI;
         * those are private library/server data and are not needed to diagnose controller resolution.
         */
        private fun logSelection(
            callback: String,
            asked: List<MediaItem>,
            selection: Selection,
            trace: AndroidAutoTraceRecorder.Trace? = null,
        ) {
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
            if (trace != null) {
                autoTraces.log(
                    "Android Auto controller selection resolved",
                    trace,
                    listOf(
                        LogField.Public("callback", callback),
                        LogField.Public("branch", selection.branch),
                        LogField.Public("kind", selection.kind),
                        LogField.Public("resolved", selection.resolved),
                        LogField.Count("asked", asked.size),
                        LogField.Count("handedBack", selection.answer.mediaItems.size),
                        LogField.Public("startIndex", selection.answer.startIndex),
                        LogField.Millis("startAt", selection.answer.startPositionMs),
                    ),
                )
            }
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
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val trace = autoTraces.forController(controller.packageName, CAR_PACKAGES)
            val startedAt = clock.elapsed()
            if (trace != null) {
                autoTraces.log(
                    "Android Auto playback resumption requested",
                    trace,
                    buildList {
                        add(LogField.Public("forPlayback", isForPlayback))
                        addAll(AndroidAutoDiagnostics.playerFields(player))
                    },
                )
            }
            val claim = controllerQueueResolution.capture()
            val resolve: suspend () -> MediaSession.MediaItemsWithStartPosition = {
                val result = if (isForPlayback) resumption.forPlayback(claim) else resumption.describe(claim)
                if (trace != null) {
                    autoTraces.logItems(
                        trace = trace,
                        callback = "onPlaybackResumption",
                        direction = "returned",
                        items = result.mediaItems,
                        extraFields = listOf(
                            LogField.Public("forPlayback", isForPlayback),
                            LogField.Public("startIndex", result.startIndex),
                            LogField.Millis("startAt", result.startPositionMs),
                            LogField.Millis(
                                "callbackElapsed",
                                (clock.elapsed() - startedAt).inWholeMilliseconds.coerceAtLeast(0L),
                            ),
                        ),
                    )
                }
                result
            }
            return if (isForPlayback) {
                callbackFutures.submitPlayback(
                    authorized = { controllerQueueResolution.isUnchanged(claim) },
                    playable = { it.mediaItems.isNotEmpty() },
                    block = resolve,
                )
            } else {
                future(resolve)
            }
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
            if (controller.isCar()) onCarControllerConnected(controller.packageName)
            val access = session.accessFor(controller)
            val commands = when (access) {
                ControllerAccess.LibraryAndPlayback ->
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        .buildUpon()
                        .apply {
                            // PD-002 (2026-10-03): a car controller is never granted the sleep timer.
                            if (!controller.isCar()) {
                                add(SessionCommand(NotificationButtons.ACTION_EXTEND_SLEEP_TIMER, Bundle.EMPTY))
                            }
                        }
                        .add(SessionCommand(NotificationButtons.ACTION_SKIP_BACK, Bundle.EMPTY))
                        .add(SessionCommand(NotificationButtons.ACTION_SKIP_FORWARD, Bundle.EMPTY))
                        .add(SessionCommand(NotificationButtons.ACTION_ADD_BOOKMARK, Bundle.EMPTY))
                        .apply {
                            NotificationButtons.carOutputActions.forEach { action ->
                                add(SessionCommand(action, Bundle.EMPTY))
                            }
                        }
                        .add(SessionCommand(NotificationButtons.ACTION_CYCLE_HEADSET_OUTPUT, Bundle.EMPTY))
                        .add(SessionCommand(AutoLibrary.ACTION_SWITCH_PROFILE, Bundle.EMPTY))
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
                .apply {
                    ControllerTrust.withheldPlayerCommands(access).forEach(::remove)
                    MediaButtonPublishing.platformPlayerCommandsToWithhold(
                        isMediaNotificationController = session.isMediaNotificationController(controller),
                    ).forEach(::remove)
                }
                .build()

            if (controller.isCar()) {
                autoTraces.log(
                    "Android Auto initial playback surface offered",
                    fields = listOf(
                        LogField.Public("playPauseAvailable", playerCommands.contains(Player.COMMAND_PLAY_PAUSE)),
                    ) + AndroidAutoDiagnostics.playerFields(player) + carSnapshotFields(),
                )
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(playerCommands)
                .setMediaButtonPreferences(mediaButtons())
                .build()
        }

        /** Passive media-button observation only. Returning super preserves Media3's default handling. */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = mediaButtonKeyEvent(intent)
            if (audioRouteLab.state.value.recording) {
                audioRouteLab.mediaButtonInput(
                    keyEvent,
                    mediaInputController(controllerInfo),
                    MediaInputEntry.SessionCallback,
                )
            }
            val inputDevice = keyEvent?.device
            val inputEvidence = MediaButtonInputEvidence.classify(
                deviceId = keyEvent?.deviceId,
                isVirtual = inputDevice?.isVirtual,
                inputName = inputDevice?.name,
                outputs = audioOutputs.outputs.value,
            )
            autoTraces.log(
                "MediaSession hardware media button observed",
                fields = listOf(
                    LogField.Identifier("controllerPackage", controllerInfo.packageName),
                    LogField.Public("keyCode", keyEvent?.keyCode ?: -1),
                    LogField.Public("keyAction", keyEvent?.action ?: -1),
                    LogField.Public("keyInputId", keyEvent?.deviceId ?: -1),
                    LogField.Public("keyInputSource", keyEvent?.source ?: -1),
                    LogField.Public("inputOrigin", inputEvidence.origin.name),
                    LogField.Count("headsetNameMatches", inputEvidence.headsetNameMatches),
                ) + carSnapshotFields(),
            )
            // #273: one virtual toggle can arrive just from powering on a Bluetooth device.
            // Only cold, empty-player toggles are suspect; genuine PLAY, car intent and warm
            // headset buttons still flow to Media3. This can intentionally require a second
            // PLAY_PAUSE press during the three-second cold-start window.
            val suppressed = keyEvent?.let { shouldConsumeColdStartToggle(it, controllerInfo.packageName) } == true
            if (suppressed) {
                autoTraces.log("Cold virtual Bluetooth toggle consumed before playback resumption")
                return true
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        /** A controller command batch confirms the requesting package without authorizing playback. */
        override fun onPlayerInteractionFinished(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            playerCommands: Player.Commands,
        ) {
            if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE)) {
                autoTraces.log(
                    "MediaSession controller transport batch completed",
                    fields = listOf(
                        LogField.Identifier("controllerPackage", controllerInfo.packageName),
                        LogField.Public("controllerIsCar", controllerInfo.packageName in CAR_PACKAGES),
                    ) + AndroidAutoDiagnostics.playerFields(player),
                )
            }
            super.onPlayerInteractionFinished(session, controllerInfo, playerCommands)
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (!controller.isCar()) return
            onCarControllerDisconnected(controller.packageName)
        }

        /**
         * Applies the user's remembered car policy only after Media3 accepted the controller connection.
         * Arm means set/prepare and stay silent; ArmAndPlay is a deliberate service-owned direct start. Both
         * operate on the raw ExoPlayer, so neither needs an external forwarding-player fresh-start token.
         */
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (!controller.isCar()) return
            val trace = autoTraces.forController(
                controller.packageName,
                CAR_PACKAGES,
            ) ?: autoTraces.ensure("post-connect")
            val current = player
            if (current == null) {
                autoTraces.log("Android Auto post-connect found no Player", trace)
                return
            }
            autoTraces.log(
                "Android Auto post-connect entered",
                trace,
                AndroidAutoDiagnostics.playerFields(current),
            )
            if (current.mediaItemCount > 0) {
                autoTraces.log(
                    "Android Auto post-connect kept the existing current item",
                    trace,
                    AndroidAutoDiagnostics.playerFields(current),
                )
                return
            }
            // The accepted-connect request owns both snapshots before any coroutine dispatch.
            val requestGeneration = controllerCommandGeneration
            val selectionSequence = currentExplicitSelectionSequence()
            scope.launch { runCarPostConnect(current, trace, requestGeneration, selectionSequence) }
        }

        private suspend fun runCarPostConnect(
            current: ExoPlayer,
            trace: AndroidAutoTraceRecorder.Trace,
            requestGeneration: Long,
            selectionSequence: Long,
        ) {
            val startedAt = clock.elapsed()
            val action = CarConnection.decide(devices, lock, clock.now())
            val actionName = when (action) {
                AutoStartAction.ArmAndPlay -> "ArmAndPlay"
                AutoStartAction.Arm -> "Arm"
                AutoStartAction.Suppressed -> "Suppressed"
                AutoStartAction.None -> "None"
            }
            autoTraces.log(
                "Android Auto post-connect policy selected",
                trace,
                listOf(LogField.Public("action", actionName)) + AndroidAutoDiagnostics.playerFields(current),
            )
            carPostConnectRestorer(trace, requestGeneration, selectionSequence).restore(action, current)
            autoTraces.log(
                "Android Auto post-connect async work completed",
                trace,
                buildList {
                    add(LogField.Public("action", actionName))
                    add(
                        LogField.Millis(
                            "callbackElapsed",
                            (clock.elapsed() - startedAt).inWholeMilliseconds.coerceAtLeast(0L),
                        ),
                    )
                    addAll(AndroidAutoDiagnostics.playerFields(current))
                },
            )
        }

        private fun carPostConnectRestorer(
            trace: AndroidAutoTraceRecorder.Trace?,
            requestGeneration: Long,
            selectionSequence: Long,
        ): CarPostConnectRestorer = CarPostConnectRestorer(
            activeProfileId = auto::activeProfileId,
            profileGeneration = auto::activeProfileGeneration,
            isRequestCurrent = {
                controllerCommandGeneration == requestGeneration &&
                    currentExplicitSelectionSequence() == selectionSequence
            },
            commands = playbackCommandEffects,
            isProfileLocked = lock::isActiveProfileLocked,
            lastPlayedBookId = { auto.lastPlayedAfter(::refreshResumeAccount)?.id },
            heldResume = { auto.heldResumeAfter(::refreshResumeAccount) },
            openQueue = { bookId -> prepareQueue(bookId, startAt = null) },
            acceptQueue = { prepared, authorized, install ->
                bookChanges.acceptBook(prepared.session, authorized, install)
            },
            observer = object : CarPostConnectRestorer.Observer {
                override fun note(message: String, player: Player?) {
                    if (player == null) {
                        autoTraces.log(message, trace)
                    } else {
                        autoTraces.log(message, trace, AndroidAutoDiagnostics.playerFields(player))
                    }
                }

                override fun installing(direction: String, item: MediaItem, extraFields: List<LogField>) {
                    autoTraces.logItems(
                        trace = trace,
                        callback = "onPostConnect",
                        direction = direction,
                        items = listOf(item),
                        extraFields = extraFields,
                    )
                }
            },
        )

        private suspend fun refreshResumeAccount() {
            val trace = autoTraces.activeTrace
            autoTraces.log(
                "Android Auto resume candidate cache was empty; refreshing account",
                trace,
            )
            val startedAt = clock.elapsed()
            syncAccount()
            autoTraces.log(
                "Android Auto resume account refresh completed",
                trace,
                listOf(
                    LogField.Millis(
                        "refreshElapsed",
                        (clock.elapsed() - startedAt).inWholeMilliseconds.coerceAtLeast(0L),
                    ),
                ),
            )
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

        /**
         * PD-001 / AUTH-005 / #174 — switch to the profile a car selected, by its row or by its media-item
         * command.
         *
         * Both carry only the opaque media id Media3 supplied for the selected row. The domain use case
         * remains the lock/flush/pause/context owner; the car never receives a credential-entry surface.
         *
         * One switch at a time, and selecting the profile already in use does nothing. A host can deliver a
         * double tap as two requests: serialised, the second finds the first one's profile active instead of
         * racing it (PRODUCT_SPEC 6.5, "atomically"), and the no-op keeps a stray tap from re-arming — and so
         * interrupting — the book that profile is already playing (product priority 1).
         */
        private suspend fun switchProfileFromCar(profileId: ProfileId?): SessionResult =
            profileSwitcher.select(profileId)

        /** The error code to refuse [customCommand] with, or `null` when this controller may run it. */
        private fun customCommandRefusal(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
        ): Int? = when {
            !session.mayBrowse(controller) -> {
                denied(controller, "onCustomCommand")
                SessionError.ERROR_PERMISSION_DENIED
            }

            // PD-002 (2026-10-03): defense in depth for a stale cached button; onConnect does not grant it.
            customCommand.customAction == NotificationButtons.ACTION_EXTEND_SLEEP_TIMER && controller.isCar() -> {
                logger.info(
                    LogCategory.Playback,
                    "A car controller tried to extend the sleep timer and was refused",
                    LogField.Public("controller", controller.packageName),
                )
                SessionError.ERROR_NOT_SUPPORTED
            }

            else -> null
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
            val refusal = customCommandRefusal(session, controller, customCommand)
            if (refusal != null) return Futures.immediateFuture(SessionResult(refusal))
            when (customCommand.customAction) {
                NotificationButtons.ACTION_EXTEND_SLEEP_TIMER -> sleepTimer.extend()

                NotificationButtons.ACTION_SKIP_BACK -> skipBy(-skips.back)

                NotificationButtons.ACTION_SKIP_FORWARD -> skipBy(skips.forward)

                NotificationButtons.ACTION_ADD_BOOKMARK -> bookmarkHere()

                // #34 — accept both identities forever. A host may send the inactive id from a cached
                // layout after BookWave has already published the active one.
                in NotificationButtons.carOutputActions -> {
                    // Car is Automatic at the ExoPlayer layer, but it is not generic Automatic at the UI
                    // layer: preserving that semantic intent is what lets projected Auto show #34's state.
                    audioOutputs.selectCar()
                }

                NotificationButtons.ACTION_CYCLE_HEADSET_OUTPUT ->
                    AudioOutputRoles
                        .nextHeadset(audioOutputs.outputs.value, audioOutputs.selectedId.value)
                        ?.let(audioOutputs::select)

                AutoLibrary.ACTION_SWITCH_PROFILE -> return future {
                    switchProfileFromCar(AutoLibrary.profileIdOf(args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)))
                }

                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * BW-SLEEP-01 — observe explicit wall-clock/timezone edits without creating a wakeup mechanism.
     *
     * A DST transition needs no broadcast: the next boundary was resolved to an Instant using zone rules
     * before the delay was scheduled. These broadcasts cover the cases that invalidate that calculation
     * while the service is alive.
     */
    private fun registerWallClockChanges() {
        val filter = IntentFilter(Intent.ACTION_TIME_CHANGED).apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wallClockChanges, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wallClockChanges, filter)
        }
    }

    private companion object {
        private const val PLAYBACK_PARAMETER_SCALE = 1_000

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
