package com.example.shelfplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.debug
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.domain.playback.GlobalTimeline
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-001 — the app's one handle on playback.
 *
 * ### Why the app talks to a controller rather than to the service
 *
 * A [MediaController] is a client of the media session, which means it is the *same* client Android
 * Auto, a headset and the notification are. Anything this class can do, those can do too, and anything
 * they do shows up in [state]. Reaching into the service directly would create a second control path
 * that the notification did not know about, which is how a play button and a lock screen end up
 * disagreeing about what is playing.
 *
 * ### Opening the session is part of playing
 *
 * [play] does both halves — asks the server for a session, then hands the playlist to the controller.
 * They are one action from the caller's point of view, and separating them would put a half-started
 * state (a session opened, nothing playing, a listening entry already recorded on the server) in a
 * ViewModel's hands.
 */
@Singleton
class PlaybackController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val playbackRepository: PlaybackRepository,
    private val sleepTimer: SleepTimerController,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(PlaybackUiState.Idle)

    /** What is playing, for a mini player, a lock screen the app draws itself, or a book screen. */
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null

    /**
     * PRODUCT_SPEC PLAY-003 — the current book's chapters.
     *
     * Held here rather than sent through the playlist. A forty-chapter book's list in every `MediaItem`'s
     * extras would be tens of kilobytes across the binder, and the only readers are in this process: the
     * player screen and the sleep timer. It is cleared when a book changes so a chapter list can never
     * outlive the book it describes.
     */
    private var chapters: List<Chapter> = emptyList()

    /**
     * PRODUCT_SPEC PLAY-001 — opens a session and starts it at the position the server reported.
     *
     * Returns a failure the caller can show. The two that a user will actually meet are "you are not
     * signed in" and "this server could not be reached", and both are worth a message rather than a
     * play button that does nothing.
     */
    suspend fun play(bookId: LibraryItemId): AppResult<Unit> {
        _state.value = _state.value.copy(isLoading = true)
        return when (val opened = playbackRepository.openSession(bookId)) {
            is AppResult.Failure -> {
                _state.value = _state.value.copy(isLoading = false)
                opened
            }

            is AppResult.Success -> start(opened.value)
        }
    }

    private suspend fun start(session: PlaybackSession): AppResult<Unit> = withContext(mainDispatcher) {
        val media = connect() ?: return@withContext AppResult.Failure(
            AppError.Playback(summary = "The player could not be started.", isRetryable = true),
        )
        // PRODUCT_SPEC PLAY-008 — the timer needs the chapters, and this is where they exist. They
        // travel here rather than in the playlist because a long book's chapter list in every
        // `MediaItem`'s extras would be tens of kilobytes across the binder to answer one question.
        sleepTimer.onBookChanged(session.chapters)
        chapters = session.chapters
        val queue = MediaItems.queueFor(session)
        media.setMediaItems(queue.items, queue.startIndex, queue.startPositionMs)
        media.prepare()
        media.play()
        AppResult.Success(Unit)
    }

    /** The transport control a mini player and a notification share. */
    fun togglePlayPause() {
        applicationScope.launch(mainDispatcher) {
            val media = connect() ?: return@launch
            if (media.isPlaying) media.pause() else media.play()
        }
    }

    /**
     * PRODUCT_SPEC PLAY-003 — seeks to a position on the **book's** timeline.
     *
     * The conversion goes through [GlobalTimeline], on a track list rebuilt from the playlist's own
     * metadata. That is deliberate rather than convenient: the resume seek uses the same function, so a
     * dragged seek bar and a resumed book cannot disagree about where a position is — which on a
     * multi-file book is the difference between landing in the right chapter and the wrong file.
     *
     * Clamped by [GlobalTimeline] at both ends, so a bar dragged to its extreme lands on a real
     * position rather than past the last track.
     */
    fun seekTo(position: Duration) {
        applicationScope.launch(mainDispatcher) {
            val media = controller ?: return@launch
            if (media.mediaItemCount == 0) return@launch
            val items = (0 until media.mediaItemCount).map(media::getMediaItemAt)
            val cursor = GlobalTimeline.cursorFor(MediaItems.tracksOf(items), position)
            media.seekTo(cursor.index, cursor.offset.inWholeMilliseconds)
            publish(media)
        }
    }

    /**
     * PRODUCT_SPEC PLAY-003 — chapter navigation, independent of file boundaries.
     *
     * Both go through [seekTo], so a chapter that begins mid-file lands mid-file. That is the whole point
     * of PLAY-003: a chapter boundary and a track boundary are different things, and navigating by the
     * former must not be limited to the latter.
     *
     * Doing nothing at either end of the book is deliberate. "Next" on the last chapter has nowhere to
     * go, and wrapping to the start — or stopping playback — would be a surprise rather than a feature.
     */
    fun skipToNextChapter() {
        applicationScope.launch(mainDispatcher) {
            val target = GlobalTimeline.nextChapterStart(chapters, currentPosition() ?: return@launch)
            target?.let(::seekTo)
        }
    }

    fun skipToPreviousChapter() {
        applicationScope.launch(mainDispatcher) {
            val target = GlobalTimeline.previousChapterStart(chapters, currentPosition() ?: return@launch)
            target?.let(::seekTo)
        }
    }

    /** PRODUCT_SPEC PLAY-003 — jumps to a chapter chosen from a list. */
    fun seekToChapter(chapter: Chapter) = seekTo(chapter.start)

    /** Where the book is now, or `null` when nothing is loaded. Main thread only. */
    private fun currentPosition(): Duration? {
        val media = controller ?: return null
        val item = media.currentMediaItem ?: return null
        return MediaItems.globalPositionOf(item, media.currentPosition)
    }

    /**
     * PRODUCT_SPEC PLAY-007 — skips [delta] along the book, forwards or backwards.
     *
     * Expressed as a seek on the **book's** timeline rather than as `Player.seekForward`, because
     * Media3's own skip is per-item: thirty seconds forward from twenty seconds before the end of a
     * track would stop at the boundary instead of crossing into the next file.
     */
    fun skipBy(delta: Duration) {
        applicationScope.launch(mainDispatcher) {
            val media = controller ?: return@launch
            val item = media.currentMediaItem ?: return@launch
            seekTo(MediaItems.globalPositionOf(item, media.currentPosition) + delta)
        }
    }

    /**
     * PRODUCT_SPEC PLAY-004 — stops playback and lets the service go.
     *
     * `stop()` rather than `pause()`: this is the user saying they are done, and a paused foreground
     * service holds a notification they cannot dismiss. The position is written by the service on its
     * way out.
     */
    fun stop() {
        applicationScope.launch(mainDispatcher) {
            controller?.stop()
            controller?.clearMediaItems()
        }
    }

    /**
     * Releases the controller.
     *
     * Not the *service*: a released controller leaves playback running, which is the point. This only
     * exists so a test — or a future profile switch, which must not leave one account's controller
     * pointed at another's session — can drop the connection deliberately.
     */
    fun release() {
        ticker?.cancel()
        controller?.release()
        controller = null
        chapters = emptyList()
        _state.value = PlaybackUiState.Idle
    }

    /**
     * Connects to the session, building the controller on first use.
     *
     * Lazily rather than at construction: building a controller starts the service, and a service that
     * starts when the app launches would show a media notification to a user who has not pressed play.
     */
    private suspend fun connect(): MediaController? {
        controller?.let { return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val built = awaitController(token) ?: return null
        built.addListener(ControllerEvents())
        controller = built
        publish(built)
        startTicker()
        return built
    }

    /**
     * Bridges Media3's `ListenableFuture` to a suspending call.
     *
     * The two exceptions are caught by name rather than through a `runCatching`, which would swallow
     * cancellation as well (ADR-0003, and the "no broad catch" rule). `ExecutionException` is what a
     * failed session build arrives as; `InterruptedException` is the executor being torn down, and the
     * interrupt is reasserted rather than eaten.
     */
    private suspend fun awaitController(token: SessionToken): MediaController? =
        suspendCancellableCoroutine { continuation ->
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener(
                {
                    val media = try {
                        future.get()
                    } catch (failure: ExecutionException) {
                        connectionFailed(failure)
                        null
                    } catch (failure: InterruptedException) {
                        Thread.currentThread().interrupt()
                        connectionFailed(failure)
                        null
                    }
                    if (continuation.isActive) continuation.resume(media)
                },
                MoreExecutors.directExecutor(),
            )
            continuation.invokeOnCancellation {
                // The returned flag says whether the build was still in flight. Either answer is fine —
                // the continuation is already gone and nothing is waiting for a controller — but it is
                // worth a debug line when a connection is abandoned this way.
                val wasPending = future.cancel(true)
                logger.debug(
                    LogCategory.Playback,
                    "Abandoned a pending session connection",
                    LogField.Public("wasPending", wasPending),
                )
            }
        }

    private fun connectionFailed(failure: Throwable) {
        // The cause's class, not its message: a session-build failure can carry a component name and a
        // package, and PRODUCT_SPEC 14.5 keeps that out of a log the user might share.
        logger.warn(
            LogCategory.Playback,
            "Could not connect to the playback session",
            LogField.Public("cause", failure.javaClass.simpleName),
        )
    }

    /**
     * The position clock.
     *
     * Media3 reports state changes but not the passage of time, so a progress bar needs a tick. Half a
     * second is what makes a seek bar look continuous without waking the main thread more often than a
     * frame.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = applicationScope.launch(mainDispatcher) {
            while (isActive) {
                controller?.let(::publish)
                delay(TICK_MS)
            }
        }
    }

    private fun publish(media: MediaController) {
        val item: MediaItem? = media.currentMediaItem
        val position = item?.let { MediaItems.globalPositionOf(it, media.currentPosition) } ?: Duration.ZERO
        _state.value = PlaybackUiState(
            bookId = item?.let(MediaItems::bookIdOf),
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            author = item?.mediaMetadata?.artist?.toString(),
            artworkUri = item?.mediaMetadata?.artworkUri?.toString(),
            isPlaying = media.isPlaying,
            isLoading = media.playbackState == Player.STATE_BUFFERING,
            position = position,
            duration = item?.let(MediaItems::bookDurationOf) ?: Duration.ZERO,
            chapters = if (item == null) emptyList() else GlobalTimeline.ordered(chapters),
            currentChapter = if (item == null) null else GlobalTimeline.chapterAt(chapters, position),
        )
    }

    private inner class ControllerEvents : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            controller?.let(::publish)
        }

        override fun onPlayerError(error: PlaybackException) {
            // The book and the URL are not logged — see `PlaybackService.PlayerEvents`.
            logger.warn(
                LogCategory.Playback,
                "Playback reported an error to the app",
                LogField.Public("errorCode", error.errorCodeName),
            )
        }
    }

    private companion object {
        const val TICK_MS = 500L
    }
}

/**
 * What the app shows about playback.
 *
 * Every field is derived from the media session rather than from whatever started playback, so a book
 * started from a headset button or from Android Auto renders identically to one started from a tap.
 */
data class PlaybackUiState(
    val bookId: LibraryItemId?,
    val title: String,
    val author: String?,
    val artworkUri: String?,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val position: Duration,
    val duration: Duration,
    /** PRODUCT_SPEC PLAY-003 — in time order, so a list renders in the order it plays. */
    val chapters: List<Chapter> = emptyList(),
    val currentChapter: Chapter? = null,
) {
    /** Fraction in `0.0..1.0`; `0` when the duration is not known yet rather than a division by zero. */
    val fractionComplete: Float
        get() = when {
            duration.inWholeMilliseconds <= 0L -> 0f
            else -> (position.inWholeMilliseconds.toDouble() / duration.inWholeMilliseconds)
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

    companion object {
        val Idle = PlaybackUiState(
            bookId = null,
            title = "",
            author = null,
            artworkUri = null,
            isPlaying = false,
            isLoading = false,
            position = Duration.ZERO,
            duration = Duration.ZERO,
        )
    }
}
