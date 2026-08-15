package com.example.shelfplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
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
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.SkipIntervals
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.core.model.resultOf
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import kotlin.time.Duration

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

    @Inject
    internal lateinit var bookChanges: BookChanges

    /** PRODUCT_SPEC ROUTE-002 — what happens when a headset, a car or a speaker connects. */
    @Inject
    internal lateinit var outputDevices: OutputDeviceWatcher

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

    /** The two inputs to the notification's own buttons. Main thread only, like everything that reads them. */
    private var skips: SkipIntervals = SkipIntervals.Default
    private var sleepTimerState: SleepTimerState = SleepTimerState.Idle

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
        autoRewind.attach(exoPlayer)
        startJournal()
        observeSleepTimer()
        observeSkipIntervals()
        outputDevices.start(scope, DeviceActions())
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /**
     * PRODUCT_SPEC product priority 1 — swiping the app away does not stop the book.
     *
     * Only a service that is *not* playing is stopped here. Paused with the app gone is a session
     * nobody is coming back to in this process, and holding a foreground notification for it would be a
     * notification the user cannot dismiss. Playing is the case that must survive, and it does.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            flushProgress()
            stopSelf()
        }
    }

    override fun onDestroy() {
        flushProgress()
        outputDevices.stop()
        metrics.onReleased()
        // PRODUCT_SPEC PLAY-004 — "service shutdown callback". On the application scope inside the
        // coordinator, because `scope` is cancelled two lines below.
        sessionSync.onShutdown()
        sessionSync.attach(null)
        autoRewind.attach(null)
        sleepTimer.attach(null)
        journal?.cancel()
        sleepTimerWatch?.cancel()
        skipWatch?.cancel()
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
        val snapshot = positionSnapshot() ?: return
        playbackRepository.recordPosition(
            bookId = snapshot.bookId,
            position = snapshot.position,
            duration = snapshot.duration,
        )
    }

    /**
     * The last write, on a scope that outlives this service.
     *
     * [scope] is cancelled in [onDestroy], so a `launch` on it would be cancelled before the row reached
     * Room — precisely the moment the position matters most. The application scope is PRODUCT_SPEC
     * 22.10's sanctioned alternative to `GlobalScope`, and this is the case it exists for.
     */
    private fun flushProgress() {
        val snapshot = positionSnapshot() ?: return
        applicationScope.launch {
            playbackRepository.recordPosition(
                bookId = snapshot.bookId,
                position = snapshot.position,
                duration = snapshot.duration,
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
        if (positionMs <= 0L) return null
        // ADR-0016 — the player's timeline is the book, so the position and the duration are read straight
        // off it. There is no per-file arithmetic left to get wrong.
        return PositionSnapshot(
            bookId = MediaItems.bookIdOf(item),
            position = current.bookPosition(),
            duration = current.bookDuration(),
        )
    }

    private data class PositionSnapshot(val bookId: LibraryItemId, val position: Duration, val duration: Duration)

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
                // PRODUCT_SPEC PLAY-009 — before anything else on the resume path: a rewind that landed after
                // playback had started would be audible as a stutter.
                autoRewind.onResumed()
            } else {
                scope.launch { recordPosition() }
                sessionSync.request(SyncTrigger.Paused)
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
            if (playWhenReady) return
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
            scope.launch { recordPosition() }
            sessionSync.request(SyncTrigger.TrackChanged)
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
                scope.launch { recordPosition() }
                sessionSync.request(SyncTrigger.SeekCompleted)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // PRODUCT_SPEC PLAY-006 — the recorder decides what counts; this only reports what happened.
            when (playbackState) {
                Player.STATE_BUFFERING -> metrics.onBuffering()
                Player.STATE_READY -> metrics.onReady()
                else -> Unit
            }
            if (playbackState == Player.STATE_ENDED) {
                scope.launch { recordPosition() }
                sessionSync.request(SyncTrigger.BookChanged)
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
                publishMediaButtons()
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
        val timer = sleepTimerState
        if (timer.isActive) {
            add(
                CommandButton.Builder(CommandButton.ICON_PLUS_CIRCLE_FILLED)
                    .setDisplayName(getString(R.string.player_sleep_remaining, timer.remaining.asMinutesLabel()))
                    .setSessionCommand(SessionCommand(NotificationButtons.ACTION_EXTEND_SLEEP_TIMER, Bundle.EMPTY))
                    // Not a transport control, so it goes where the extra actions go rather than displacing
                    // one of the two a listener reaches for without looking.
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
        applicationScope.launch { history.record(bookId, event, from = null, to = at) }
    }

    /**
     * PRODUCT_SPEC 11.1 — the book the browse tree's Chapters and History tabs describe.
     *
     * Whatever is loaded, or `null` for a car opened with nothing playing — in which case [AutoLibrary]
     * falls back to the last book with progress, which is what "it always opens the last played book"
     * means when the app has been closed all night.
     */
    private fun currentBookId(): LibraryItemId? = player?.currentMediaItem?.let(MediaItems::bookIdOf)

    /**
     * Turns a browse item into something the player can actually load.
     *
     * A browse item has a media id and no URI: the tree is built from cached rows, and the track URLs come
     * from a session the server has to open. This is where that happens.
     *
     * An item that is **already** complete is returned untouched — see [MediaItems.isReadyToPlay], which is
     * where the reasoning for that lives, because it is the part worth testing.
     */
    private suspend fun resolvePlayable(item: MediaItem): MediaItem? =
        if (MediaItems.isReadyToPlay(item)) item else resolveQueue(item)?.item

    private suspend fun resolveQueue(item: MediaItem): MediaItems.Queue? {
        val target = AutoLibrary.resolve(item.mediaId) ?: return null
        return openQueue(target.bookId, target.startAt)
    }

    /**
     * Opens a session for [bookId] and returns it as a queue, starting at [startAt] when one was asked for.
     *
     * `null` on any failure, and the failure is logged rather than surfaced: the caller is a car or a
     * headset, neither of which has anywhere to show an error. What they get instead is nothing happening,
     * which is what ROUTE-001 asks for — "if no playable item exists, the command does nothing and logs a
     * non-fatal diagnostic".
     */
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
                // The chapters have to reach the sleep timer and the outbox exactly as they do when the app
                // starts a book, or a book started from a car would have no end-of-chapter timer and no
                // outbox row. Same call, one place.
                bookChanges.onBookOpened(session)
                val queue = MediaItems.queueFor(session)
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
                    logger.warn(
                        LogCategory.Playback,
                        "A browse request failed",
                        LogField.Public("error", outcome.error.code),
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
        applicationScope.launch { bookmarks.add(bookId, at, title = "") }
    }

    private fun skipBy(delta: Duration) {
        val current = player ?: return
        if (current.mediaItemCount == 0) return
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
     * Connections and transport controls, plus the three commands the notification's own buttons carry.
     * No browse tree.
     *
     * `MediaLibrarySession.Callback`'s defaults accept a connection with the standard command set and
     * reject `onGetLibraryRoot`, which is the accurate answer until a browse tree exists. The custom
     * commands have to be granted here, or the buttons would be rendered and then rejected when pressed.
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
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(auto.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            // Paged by the caller, so a long continue-listening list arrives a screen at a time rather
            // than as one binder transaction a head unit may refuse.
            val all = auto.children(parentId, currentBookId())
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            auto.item(mediaId, currentBookId())
                ?.let { item -> LibraryResult.ofItem(item, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        /**
         * PRODUCT_SPEC 11.1 — a spoken query. Answers whether there are results; the browser then asks for
         * them through [onGetSearchResult].
         *
         * `notifySearchResultChanged` is how the two halves are joined: the browser is told the count and
         * calls back for the page it wants.
         */
        // `Void` rather than `Unit` because Media3 declares it: `LibraryResult<Void>` is the return type of
        // the interface method, and Kotlin's `Unit` is not the same erasure. detekt's rule is right in
        // general and cannot apply to an override of a Java signature.
        @Suppress("ForbiddenVoid")
        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
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
            val all = auto.search(query)
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        /**
         * PRODUCT_SPEC PLAY-001 / 11.1 — something asked the session to load an item.
         *
         * Two kinds of caller arrive here and they need opposite treatment. A **browser** — a car, an
         * assistant — hands back only the media id this app put in its tree, so the id has to be resolved
         * into an open session before anything can play (see [AutoLibrary]). The **app itself** hands back an
         * item it built from a session it already opened, and the only correct thing to do with that is to
         * give it straight back.
         *
         * Wave 5 added this override for the first case and, in doing so, broke the second.
         * [MediaItems.isReadyToPlay] is the distinction it was missing.
         *
         * An item that is neither is dropped rather than passed on: Media3 would hand the player an item with
         * no URI and no tracks, and the player would report an error a driver cannot act on.
         */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.mapNotNull { item -> resolvePlayable(item) }.toMutableList()
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            // "Play <something>" arrives as an item with no media id and a search query on it, which is a
            // different question from "play this id" and has to be answered before either of the others.
            val spoken = mediaItems.firstNotNullOfOrNull { item ->
                item.requestMetadata.searchQuery?.let { query -> auto.search(query).firstOrNull() }
            }
            when {
                spoken != null -> resolveQueue(spoken).asItems(startIndex, startPositionMs)

                // The app's own call. The index and the position are the caller's — it opened the session and
                // knows where the book resumes — and the list is handed back whole rather than collapsed to
                // one item, because this callback is not the place to decide what a queue contains.
                mediaItems.isNotEmpty() && mediaItems.all(MediaItems::isReadyToPlay) ->
                    MediaSession.MediaItemsWithStartPosition(mediaItems.toList(), startIndex, startPositionMs)

                else -> mediaItems.firstNotNullOfOrNull { item -> resolveQueue(item) }
                    .asItems(startIndex, startPositionMs)
            }
        }

        /**
         * A resolved browse target as Media3's answer, or an empty one when nothing resolved.
         *
         * The resolved queue brings its own start position — that is the whole content of an `at/…` id, and
         * of a book resumed from its stored progress — so the caller's is used only for the empty case, where
         * it is what Media3 handed in and echoing it back is the least surprising thing to do.
         */
        private fun MediaItems.Queue?.asItems(startIndex: Int, startPositionMs: Long) = when (this) {
            null -> MediaSession.MediaItemsWithStartPosition(emptyList(), startIndex, startPositionMs)
            else -> MediaSession.MediaItemsWithStartPosition(listOf(item), 0, this.startPositionMs)
        }

        /**
         * PRODUCT_SPEC ROUTE-001 — "a headset Play resumes the last item". The exit criterion, finally.
         *
         * Android calls this when a media button arrives at a session whose player holds nothing — after a
         * process death, or on the first press of the day. What it wants back is a queue and a position,
         * which for this app means the most recently played unfinished book at the position stored for it.
         *
         * `MediaSession` rather than `MediaLibrarySession` in the signature: this is inherited from
         * `MediaSession.Callback`, not declared on the library one.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val book = auto.lastPlayed()
            val queue = book?.let { openQueue(it.id, startAt = null) }
            if (queue == null) {
                // PRODUCT_SPEC ROUTE-001 — "if no playable item exists, the command does nothing and logs a
                // non-fatal diagnostic". This is that diagnostic. No book id: a log a user might share does
                // not need to name what they listen to (14.5).
                logger.info(LogCategory.Playback, "A resume was requested with nothing to resume")
                MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
            } else {
                logger.info(LogCategory.Playback, "Resuming the last book for a media button")
                MediaSession.MediaItemsWithStartPosition(listOf(queue.item), 0, queue.startPositionMs)
            }
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // PRODUCT_SPEC PLAY-001 / 14.5 — who bound to the session, and when.
            //
            // A package name, which is the one fact about a controller that is safe to write down: it names
            // an app, not a book. Two device runs reported the app missing from a car with no way to tell a
            // discovery problem from a browse-tree one, and this is the line that separates them — if
            // `gearhead` never appears here, Android Auto never reached the app at all and nothing in the
            // tree can be at fault. See `CarReadiness`.
            if (controller.isCar()) {
                carConnections.onConnected()
                logger.info(
                    LogCategory.Playback,
                    "A car connected to the media session",
                    LogField.Public("controller", controller.packageName),
                )
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(NotificationButtons.ACTION_EXTEND_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(NotificationButtons.ACTION_SKIP_BACK, Bundle.EMPTY))
                .add(SessionCommand(NotificationButtons.ACTION_SKIP_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(NotificationButtons.ACTION_ADD_BOOKMARK, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(mediaButtons())
                .build()
        }

        /**
         * PRODUCT_SPEC ROUTE-001 / ROUTE-002 — a car connected, and the setting says start playing.
         *
         * The **only** path in this app that starts audio without anybody pressing anything, and it is
         * fenced accordingly: the setting is off by default, the controller has to be a car, and there has
         * to be nothing already loaded. With the setting off a car still opens on the last book — Media3
         * gives it the session, and the driver presses play — which is ROUTE-002's `Arm only` default
         * arriving before per-device policy does.
         *
         * ROUTE-002's per-device policies (`Never react`, `Auto-play`, `Ask`) are not built. One global
         * switch is the honest interim and `docs/phase-2-gaps.md` says so.
         */
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (!controller.isCar()) return
            val current = player ?: return
            if (current.mediaItemCount > 0) return
            scope.launch {
                if (!playbackSettings.observeSettings().first().autoPlayOnCarConnect) return@launch
                val book = auto.lastPlayed() ?: return@launch
                val queue = openQueue(book.id, startAt = null) ?: return@launch
                logger.info(LogCategory.Playback, "A car connected and auto-play is on")
                current.setMediaItem(queue.item, queue.startPositionMs)
                current.prepare()
                current.play()
            }
        }

        /**
         * Whether a connection came from a car.
         *
         * Matched on package name, which is what Media3 gives and what every media app checks. Both are
         * listed: `gearhead` is Android Auto's projected head unit, `androidauto` is Automotive OS.
         */
        private fun MediaSession.ControllerInfo.isCar(): Boolean = packageName in CAR_PACKAGES

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                // PLAY-008 says the notification action *extends*; the shake *restarts*. See ADR-0014.
                NotificationButtons.ACTION_EXTEND_SLEEP_TIMER -> sleepTimer.extend()
                NotificationButtons.ACTION_SKIP_BACK -> skipBy(-skips.back)
                NotificationButtons.ACTION_SKIP_FORWARD -> skipBy(skips.forward)
                NotificationButtons.ACTION_ADD_BOOKMARK -> bookmarkHere()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        /** PRODUCT_SPEC PLAY-004 — "at least every five seconds". */
        const val JOURNAL_INTERVAL_MS = 5_000L

        const val SECONDS_PER_MINUTE = 60L

        /**
         * PRODUCT_SPEC ROUTE-002 — the two packages that are a car.
         *
         * Android Auto projects from `gearhead`; a car running Automotive OS natively connects as
         * `androidauto`. Anything else — a watch, a desktop companion, the app itself — is not a car and
         * does not get the auto-play behaviour whatever the setting says.
         */
        val CAR_PACKAGES = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.autoauto",
            "com.google.android.androidauto",
        )
    }
}
