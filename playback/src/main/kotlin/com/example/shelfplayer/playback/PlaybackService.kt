package com.example.shelfplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
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
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.DeviceRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.usecase.NextInSeriesUseCase
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
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
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
 * implementation rejects. Wave 5 answers it: [AutoLibrary] builds three tabs — Continue, Chapters,
 * History — and [LibraryCallback] serves them. A car also needs the app to *declare* itself, which is a
 * manifest `meta-data` entry pointing at `automotive_app_desc.xml`; without it the app is invisible in the
 * dashboard no matter how good its tree is, which is exactly what a device run found.
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
     * PRODUCT_SPEC PLAY-002 — what the audio-output button is currently labelled with, or `null` for
     * *Automatic*. Read on the main thread like the two above.
     */
    private var currentOutput: AudioOutput? = null

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
        session = MediaLibrarySession.Builder(this, exoPlayer, LibraryCallback())
            .setBitmapLoader(players.bitmapLoader())
            .apply { launchIntent()?.let(::setSessionActivity) }
            .build()
        sleepTimer.attach(exoPlayer)
        sessionSync.attach(exoPlayer)
        autoRewind.attach(exoPlayer)
        audioOutputs.attach(exoPlayer)
        startJournal()
        observeSleepTimer()
        observeSkipIntervals()
        observeAudioOutputs()
        outputDevices.start(scope, DeviceActions())
        observeBrowseTreeInvalidation()
        logger.info(LogCategory.Playback, "Playback service started")
    }

    private fun launchIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private inner class DeviceActions : OutputDeviceWatcher.Actions {
        override fun isBusy(): Boolean = (player?.mediaItemCount ?: 0) > 0

        override suspend fun arm() {
            load(startPlaying = false)
        }

        override suspend fun armAndPlay() {
            load(startPlaying = true)
        }

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
        sessionSync.onShutdown()
        sessionSync.attach(null)
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

    private fun startJournal() {
        journal = scope.launch {
            while (isActive) {
                delay(JOURNAL_INTERVAL_MS)
                recordPosition()
            }
        }
    }

    private suspend fun recordPosition() {
        val snapshot = positionSnapshot() ?: return
        playbackRepository.recordPosition(
            bookId = snapshot.bookId,
            position = snapshot.position,
            duration = snapshot.duration,
            owner = snapshot.owner,
        )
    }

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

    private fun positionSnapshot(): PositionSnapshot? {
        val current = player ?: return null
        val item = current.currentMediaItem ?: return null
        val positionMs = current.currentPosition
        if (positionMs <= 0L || MediaItems.isSingleFileFallback(item)) return null
        return PositionSnapshot(
            bookId = MediaItems.bookIdOf(item),
            position = current.bookPosition(),
            duration = current.bookDuration(),
            owner = MediaItems.ownerOf(item),
        )
    }

    private data class PositionSnapshot(
        val bookId: LibraryItemId,
        val position: Duration,
        val duration: Duration,
        val owner: ProfileId?,
    )

    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            sessionSync.onPlayingChanged(isPlaying)
            if (isPlaying) {
                recovery.onPlaying()
                autoRewind.onResumed()
            } else {
                scope.launch { recordPosition() }
                sessionSync.request(SyncTrigger.Paused)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            recordTransport(if (playWhenReady) PlaybackEvent.Play else PlaybackEvent.Pause)
            logger.debug(
                LogCategory.Playback,
                "Playback was asked to change",
                LogField.Public("playWhenReady", playWhenReady.toString()),
                LogField.Public("reason", playWhenReadyReason(reason)),
            )
            if (playWhenReady) return
            autoRewind.onPaused(
                wasUserInitiated = reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ||
                    reason == Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            recovery.onBookChanged()
            if (mediaItem != null) metrics.onItemPrepared()
            scope.launch { recordPosition() }
            sessionSync.request(SyncTrigger.TrackChanged)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            ) {
                autoRewind.onSeeked()
                scope.launch { recordPosition() }
                sessionSync.request(SyncTrigger.SeekCompleted)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            logger.debug(
                LogCategory.Playback,
                "The player changed state",
                LogField.Public("state", stateName(playbackState)),
            )
            when (playbackState) {
                Player.STATE_BUFFERING -> metrics.onBuffering()
                Player.STATE_READY -> metrics.onReady()
                else -> Unit
            }
            if (playbackState == Player.STATE_ENDED) {
                scope.launch { recordPosition() }
                sessionSync.request(SyncTrigger.BookChanged)
                scope.launch { advanceToNextInSeries() }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val retryIn = recovery.onError(error)
            logger.warn(
                LogCategory.Playback,
                if (retryIn == null) "Playback stopped on an error" else "Playback hit an error and will retry",
                LogField.Public("errorCode", error.errorCodeName),
                LogField.Count("attempt", recovery.attemptCount),
            )
            scope.launch { recordPosition() }
            if (retryIn == null) return
            scope.launch {
                delay(retryIn)
                val current = player ?: return@launch
                if (current.mediaItemCount == 0) return@launch
                current.prepare()
            }
        }
    }

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
                publishMediaButtons()
            }
        }
    }

    private fun observeAudioOutputs() {
        outputWatch = scope.launch {
            combine(audioOutputs.outputs, audioOutputs.selectedId, ::Pair).collect { (outputs, selected) ->
                val next = AudioOutputCycle.current(outputs, selected)
                if (next != currentOutput) {
                    currentOutput = next
                    publishMediaButtons()
                }
            }
        }
    }

    private fun publishMediaButtons() {
        val current = session ?: return
        current.setMediaButtonPreferences(mediaButtons())
    }

    private fun mediaButtons(): List<CommandButton> = buildList {
        add(
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
        )
        add(
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
        )
        add(
            CommandButton.Builder(CommandButton.ICON_UNDEFINED)
                .setCustomIconResId(R.drawable.ic_audio_output)
                .setDisplayName(
                    getString(
                        R.string.player_output_action,
                        currentOutput?.displayName ?: getString(R.string.car_output_automatic),
                    ),
                )
                .setSessionCommand(SessionCommand(NotificationButtons.ACTION_CYCLE_AUDIO_OUTPUT, Bundle.EMPTY))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .setEnabled(true)
                .build(),
        )
        val timer = sleepTimerState
        if (timer.isActive) {
            add(
                CommandButton.Builder(CommandButton.ICON_PLUS_CIRCLE_FILLED)
                    .setDisplayName(getString(R.string.player_sleep_remaining, timer.remaining.asMinutesLabel()))
                    .setSessionCommand(SessionCommand(NotificationButtons.ACTION_EXTEND_SLEEP_TIMER, Bundle.EMPTY))
                    .setSlots(CommandButton.SLOT_OVERFLOW)
                    .setEnabled(true)
                    .build(),
            )
        }
    }

    private fun skipButton(icon: Int, action: String, label: String, slot: Int): CommandButton =
        CommandButton.Builder(icon)
            .setDisplayName(label)
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .setSlots(slot)
            .setEnabled(true)
            .build()

    private fun recordTransport(event: PlaybackEvent) {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        if (current.mediaItemCount == 0) return
        val bookId = MediaItems.bookIdOf(item)
        val at = current.bookPosition()
        val owner = MediaItems.ownerOf(item)
        applicationScope.launch { history.record(bookId, event, from = null, to = at, owner = owner) }
    }

    private fun nowPlaying(): NowPlaying? {
        val current = player ?: return null
        val bookId = current.currentMediaItem?.let(MediaItems::bookIdOf) ?: return null
        return NowPlaying(bookId, current.currentPosition.coerceAtLeast(0).milliseconds)
    }

    private data class Selection(
        val branch: String,
        val resolved: Boolean,
        val kind: String,
        val answer: MediaSession.MediaItemsWithStartPosition,
    )

    private fun playWhenReadyReason(reason: Int): String = when (reason) {
        Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "userRequest"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audioFocusLoss"
        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "becomingNoisy"
        Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
        Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "endOfItem"
        Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressedTooLong"
        else -> "unknown"
    }

    private fun stateName(playbackState: Int): String = when (playbackState) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown"
    }

    private fun loadedNow(): MediaSession.MediaItemsWithStartPosition? {
        val current = player ?: return null
        val loaded = current.currentMediaItem ?: return null
        return MediaSession.MediaItemsWithStartPosition(listOf(loaded), 0, current.currentPosition.coerceAtLeast(0))
    }

    private suspend fun resolvePlayable(item: MediaItem, trusted: Boolean): MediaItem? =
        if (trusted && MediaItems.isReadyToPlay(item)) item else resolveQueue(item)?.item

    private suspend fun resolveQueue(item: MediaItem): MediaItems.Queue? {
        val target = AutoLibrary.resolve(item.mediaId) ?: return null
        return openQueue(target.bookId, target.startAt)
    }

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
        logger.info(LogCategory.Playback, "A book ended, so the next in its series was started")
    }

    private suspend fun openQueue(bookId: LibraryItemId, startAt: Duration?): MediaItems.Queue? =
        when (val opened = playbackRepository.openSession(bookId)) {
            is AppResult.Failure -> {
                logger.warn(
                    LogCategory.Playback,
                    "Could not open a session for a browse or resume request",
                    LogField.Public("error", opened.error.code),
                )
                null
            }

            is AppResult.Success -> {
                val session = opened.value
                bookChanges.onBookOpened(session)
                val queue = MediaItems.queueFor(session)
                if (startAt == null) queue else queue.copy(startPositionMs = startAt.inWholeMilliseconds.coerceAtLeast(0))
            }
        }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val settable = SettableFuture.create<T>()
        val job = applicationScope.launch {
            when (val outcome = resultOf { block() }) {
                is AppResult.Success -> settable.set(outcome.value)
                is AppResult.Failure -> {
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

    private fun bookmarkHere() {
        val current = player ?: return
        val item = current.currentMediaItem ?: return
        val bookId = MediaItems.bookIdOf(item)
        val at = Bookmark.roundedFrom(current.bookPosition())
        val owner = MediaItems.ownerOf(item)
        applicationScope.launch { bookmarks.add(bookId, at, title = "", owner = owner) }
    }

    private fun skipBy(delta: Duration) {
        val current = player ?: return
        if (current.mediaItemCount == 0) return
        current.seekTo((current.bookPosition() + delta).inWholeMilliseconds.coerceAtLeast(0))
    }

    private fun Duration.asMinutesLabel(): String {
        val seconds = inWholeSeconds
        if (seconds < SECONDS_PER_MINUTE) return getString(R.string.player_sleep_seconds, seconds)
        val minutes = (seconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE
        return getString(R.string.player_sleep_minutes, minutes)
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
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
                query != null -> spoken?.let { match -> resolveQueue(match) }.let { queue ->
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
                        .firstNotNullOfOrNull { item -> resolveQueue(item)?.let { queue -> item to queue } }
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

        private suspend fun MediaItems.Queue?.asItems(startIndex: Int, startPositionMs: Long) = when (this) {
            null -> unresolved(startIndex, startPositionMs)
            else -> MediaSession.MediaItemsWithStartPosition(listOf(item), 0, this.startPositionMs)
        }

        private suspend fun unresolved(
            startIndex: Int,
            startPositionMs: Long,
        ): MediaSession.MediaItemsWithStartPosition = withContext(mainDispatcher) { loadedNow() }
            ?: MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            if (isForPlayback) resumeForPlayback() else describeResumable()
        }

        private suspend fun resumeForPlayback(): MediaSession.MediaItemsWithStartPosition {
            val book = auto.lastPlayed()
            val queue = book?.let { openQueue(it.id, startAt = null) }
            return if (queue == null) {
                logger.info(LogCategory.Playback, "A resume was requested with nothing to resume")
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            } else {
                logger.info(LogCategory.Playback, "Resuming the last book for a media button")
                MediaSession.MediaItemsWithStartPosition(listOf(queue.item), 0, queue.startPositionMs)
            }
        }

        private suspend fun describeResumable(): MediaSession.MediaItemsWithStartPosition {
            val item = auto.resumeItem()
            if (item == null) {
                logger.info(LogCategory.Playback, "A resumable book was asked for and there is none")
                return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            }
            logger.info(LogCategory.Playback, "Described the resumable book without opening a session")
            return MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
        }

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
                        .add(SessionCommand(NotificationButtons.ACTION_CYCLE_AUDIO_OUTPUT, Bundle.EMPTY))
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

        private fun MediaSession.ControllerInfo.isCar(): Boolean = packageName in CAR_PACKAGES

        private fun MediaSession.ControllerInfo.isThisApplication(): Boolean = uid == Process.myUid()

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
                NotificationButtons.ACTION_CYCLE_AUDIO_OUTPUT -> audioOutputs.selectNext()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        const val JOURNAL_INTERVAL_MS = 5_000L
        const val SECONDS_PER_MINUTE = 60L
        val CAR_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.autoauto",
            "com.google.android.androidauto",
        )
    }
}
