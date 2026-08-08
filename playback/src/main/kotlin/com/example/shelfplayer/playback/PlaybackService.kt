package com.example.shelfplayer.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.domain.playback.FinishedThreshold
import com.example.shelfplayer.domain.repository.PlaybackRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * root, which would look supported and browse to nothing. No session sync either: progress is journaled
 * locally and wave 3 adds the outbox that sends it back to the server.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    internal lateinit var players: PlayerFactory

    @Inject
    internal lateinit var playbackRepository: PlaybackRepository

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
        val exoPlayer = players.create().also { player = it }
        exoPlayer.addListener(PlayerEvents())
        session = MediaLibrarySession.Builder(this, exoPlayer, LibraryCallback())
            .setBitmapLoader(players.bitmapLoader())
            .build()
        startJournal()
        logger.info(LogCategory.Playback, "Playback service started")
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
        journal?.cancel()
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
            if (!isPlaying) scope.launch { recordPosition() }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            scope.launch { recordPosition() }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) scope.launch { recordPosition() }
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
     * Wave 1's callback: connections and transport controls, no browse tree.
     *
     * `MediaLibrarySession.Callback`'s defaults accept a connection with the standard command set and
     * reject `onGetLibraryRoot`, which is the accurate answer until a browse tree exists. A named class
     * rather than an anonymous object so that wave 4 has an obvious place for the custom commands —
     * speed, skip, sleep timer — that PLAY-006 through PLAY-009 need.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback

    private companion object {
        /** PRODUCT_SPEC PLAY-004 — "at least every five seconds". */
        const val JOURNAL_INTERVAL_MS = 5_000L
    }
}
