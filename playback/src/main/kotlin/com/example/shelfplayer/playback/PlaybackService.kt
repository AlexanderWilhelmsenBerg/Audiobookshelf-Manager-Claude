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
import androidx.media3.session.MediaLibraryService
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
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.playback.FinishedThreshold
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
 * ### What is not here yet
 *
 * No browse tree — Android Auto and Wear reach a [MediaLibraryService] through `onGetLibraryRoot`, and
 * the default rejects it. That is the honest answer for wave 1 rather than a stub returning an empty
 * root, which would look supported and browse to nothing.
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
    internal lateinit var bookRemaining: BookRemaining

    @Inject
    internal lateinit var notifications: BookNotificationProvider

    @Inject
    internal lateinit var playbackSettings: PlaybackSettingsRepository

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
        val exoPlayer = players.create(buffer = runBlocking { playbackSettings.observeSettings().first().buffer })
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
        // PRODUCT_SPEC PLAY-001 — the notification's second line carries the *book's* remaining time. Set
        // before the session is built, so the first notification already has it rather than gaining it a
        // minute later.
        setMediaNotificationProvider(notifications)
        bookRemaining.attach(exoPlayer)
        sleepTimer.attach(exoPlayer)
        // PRODUCT_SPEC PLAY-004 — the remote cadence reads the same player the journal does. It is given the
        // player rather than owning one, for the same reason the timer is: there is exactly one.
        sessionSync.attach(exoPlayer)
        autoRewind.attach(exoPlayer)
        startJournal()
        observeSleepTimer()
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
        // PRODUCT_SPEC PLAY-004 — "service shutdown callback". On the application scope inside the
        // coordinator, because `scope` is cancelled two lines below.
        sessionSync.onShutdown()
        sessionSync.attach(null)
        autoRewind.attach(null)
        bookRemaining.attach(null)
        notifications.release()
        sleepTimer.attach(null)
        journal?.cancel()
        sleepTimerWatch?.cancel()
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
                // PRODUCT_SPEC PLAY-001 — piggybacked on the journal rather than given a timer of its own.
                // The label is minute-granular, so this is a no-op on eleven ticks out of twelve.
                notifications.refreshIfChanged()
            }
        }
    }

    private suspend fun recordPosition() {
        val snapshot = positionSnapshot() ?: return
        playbackRepository.recordPosition(
            bookId = snapshot.bookId,
            position = snapshot.position,
            duration = snapshot.duration,
            isFinished = FinishedThreshold.isFinished(snapshot.position, snapshot.duration),
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
                isFinished = FinishedThreshold.isFinished(snapshot.position, snapshot.duration),
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
        if (positionMs <= 0L && current.currentMediaItemIndex == 0) return null
        return PositionSnapshot(
            bookId = MediaItems.bookIdOf(item),
            position = MediaItems.globalPositionOf(item, positionMs),
            duration = MediaItems.bookDurationOf(item),
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
            if (playbackState == Player.STATE_ENDED) {
                scope.launch { recordPosition() }
                sessionSync.request(SyncTrigger.BookChanged)
            }
        }

        /**
         * PRODUCT_SPEC 14.4 / 14.5 — the error is recorded, and the book it happened to is not.
         *
         * `errorCodeName` is a Media3 constant and says what went wrong. The exception's message can
         * contain the failing URL, which is a path on someone's private server, so it is deliberately
         * not logged.
         */
        override fun onPlayerError(error: PlaybackException) {
            logger.warn(
                LogCategory.Playback,
                "Playback stopped on an error",
                LogField.Public("errorCode", error.errorCodeName),
            )
            scope.launch { recordPosition() }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-008 — the remaining time, in the notification.
     *
     * Media3's notification is built from the session, so the way to put anything of our own in it is a
     * custom-layout button. This one carries the countdown as its **display name** and extends the timer
     * when pressed, which is both of PLAY-008's notification requirements in a single control: "displays
     * remaining time in notification" and "a notification action extends the timer".
     *
     * The button disappears when no timer is running rather than sitting there greyed out. A control
     * that does nothing is a control a half-asleep listener will press anyway.
     */
    private fun observeSleepTimer() {
        sleepTimerWatch = scope.launch {
            sleepTimer.state.collect { timer -> publishSleepTimerButton(timer) }
        }
    }

    private fun publishSleepTimerButton(timer: SleepTimerState) {
        val current = session ?: return
        val buttons = if (!timer.isActive) {
            emptyList()
        } else {
            listOf(
                CommandButton.Builder(CommandButton.ICON_PLUS_CIRCLE_FILLED)
                    .setDisplayName(getString(R.string.player_sleep_remaining, timer.remaining.asMinutesLabel()))
                    .setSessionCommand(SessionCommand(ACTION_EXTEND_SLEEP_TIMER, Bundle.EMPTY))
                    .setEnabled(true)
                    .build(),
            )
        }
        current.setCustomLayout(buttons)
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
     * Connections and transport controls, plus the sleep timer's own command. No browse tree.
     *
     * `MediaLibrarySession.Callback`'s defaults accept a connection with the standard command set and
     * reject `onGetLibraryRoot`, which is the accurate answer until a browse tree exists. The custom
     * command has to be granted here, or the button in the notification would be rendered and then
     * rejected when pressed.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(ACTION_EXTEND_SLEEP_TIMER, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_EXTEND_SLEEP_TIMER) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            // PLAY-008 says the notification action *extends*; the shake *restarts*. See ADR-0014.
            sleepTimer.extend()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private companion object {
        /** PRODUCT_SPEC PLAY-004 — "at least every five seconds". */
        const val JOURNAL_INTERVAL_MS = 5_000L

        const val SECONDS_PER_MINUTE = 60L

        /** PRODUCT_SPEC PLAY-008 — the notification action. Namespaced, as a session command must be. */
        const val ACTION_EXTEND_SLEEP_TIMER = "com.example.shelfplayer.playback.EXTEND_SLEEP_TIMER"
    }
}
