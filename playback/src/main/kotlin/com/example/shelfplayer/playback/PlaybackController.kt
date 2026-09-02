package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.dispatcher.Dispatcher
import com.example.shelfplayer.core.common.dispatcher.ShelfDispatcher
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.warn
import com.example.shelfplayer.core.model.AppError
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.domain.playback.GlobalTimeline
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.domain.usecase.OpenPlaybackSessionUseCase
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
    private val connector: SessionConnector,
    private val playbackRepository: PlaybackRepository,
    private val openPlaybackSession: OpenPlaybackSessionUseCase,
    private val bookChanges: BookChanges,
    private val playbackSettings: PlaybackSettingsRepository,
    private val history: PlaybackHistoryRepository,
    /** PRODUCT_SPEC PLAY-002 — where the book goes, which is part of what the player screen shows. */
    private val audioOutputs: AudioOutputRouter,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    @param:Dispatcher(ShelfDispatcher.MainImmediate) private val mainDispatcher: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(PlaybackUiState.Idle)

    /** What is playing, for a mini player, a lock screen the app draws itself, or a book screen. */
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /**
     * PRODUCT_SPEC PLAY-002 — the outputs the current book can be sent to.
     *
     * Through this façade rather than injected into the player's view model directly. The screen already
     * takes its playback state from here; routing is part of "what is playing and where it goes", and a
     * second object in the view model would be a second place for the two to disagree.
     */
    val outputs: StateFlow<List<AudioOutput>> = audioOutputs.outputs

    /**
     * PRODUCT_SPEC PLAY-002 — the output the listener *chose*, or `null` for *Automatic*.
     *
     * Not the same question as [AudioOutput.isActive], which is where the platform says the audio went. The
     * chooser shows both because they can disagree — see `AudioOutputRouter`.
     */
    val selectedOutput: StateFlow<String?> = audioOutputs.selectedId

    /** PRODUCT_SPEC PLAY-002 — chooses an output, or `null` for *Automatic*. Not remembered (ADR-0027). */
    fun selectOutput(id: String?) = audioOutputs.select(id)

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
     * PRODUCT_SPEC PLAY-004 — the chapter the last publish saw, so a crossing can be detected.
     *
     * The chapter list only exists in this process, so this is the only place a chapter change *can* be
     * noticed. With the app gone the service's thirty-second cadence covers the same ground half a minute
     * later, which is the honest limit of putting the list here rather than in the playlist.
     */
    private var lastChapter: Chapter? = null

    /**
     * PRODUCT_SPEC PLAY-001 — opens a session and starts it at the position the server reported.
     *
     * Returns a failure the caller can show. The two that a user will actually meet are "you are not
     * signed in" and "this server could not be reached", and both are worth a message rather than a
     * play button that does nothing.
     */
    suspend fun play(bookId: LibraryItemId): AppResult<Unit> = open(bookId, startPlaying = true)

    /**
     * PRODUCT_SPEC ROUTE-003 — loads a book **paused**, ready for a play from anywhere.
     *
     * The same path as [play] with the last call omitted, deliberately: a book that is armed rather than
     * played must be armed *identically*, or the sleep timer, the outbox, auto-rewind and the speed would
     * all be set up differently depending on how the book arrived.
     */
    suspend fun arm(bookId: LibraryItemId): AppResult<Unit> = open(bookId, startPlaying = false)

    private suspend fun open(bookId: LibraryItemId, startPlaying: Boolean): AppResult<Unit> {
        _state.value = _state.value.copy(isLoading = true)
        return when (val opened = openPlaybackSession(bookId)) {
            is AppResult.Failure -> {
                _state.value = _state.value.copy(isLoading = false)
                opened
            }

            is AppResult.Success -> start(opened.value, startPlaying)
        }
    }

    private suspend fun start(session: PlaybackSession, startPlaying: Boolean): AppResult<Unit> =
        withContext(mainDispatcher) {
            val media = connect() ?: return@withContext AppResult.Failure(
                AppError.Playback(summary = "The player could not be started.", isRetryable = true),
            )
            // PRODUCT_SPEC PLAY-004 / PLAY-008 / PLAY-009 — the sleep timer, the outbox and auto-rewind all need
            // to know, in that order. See [BookChanges]; the chapters travel to them here rather than in the
            // playlist, because a long book's list in every `MediaItem`'s extras would be tens of kilobytes
            // across the binder to answer one question.
            bookChanges.onBookOpened(session)
            chapters = session.chapters
            lastChapter = null
            // ADR-0016 — one item for the whole book, and the resume point is a book position because the
            // player's timeline is the book. `BookMediaSourceFactory` turns the item into the concatenation.
            val queue = MediaItems.queueFor(session)
            media.setMediaItem(queue.item, queue.startPositionMs)
            // PRODUCT_SPEC PLAY-007 — the book's own speed if it has one, the profile default otherwise, applied
            // before `prepare` so the first second plays at the right rate rather than snapping a moment later.
            media.setPlaybackSpeed(playbackSettings.speedFor(session.bookId).value)
            media.prepare()
            if (startPlaying) media.play()
            // PRODUCT_SPEC PLAY-003 — the one history entry with no "from": there was no before.
            //
            // Only for a book that is actually playing. Arming one on app open is not listening, and recording
            // it would put a Resume in the history for every launch of a build set to restore.
            if (startPlaying) {
                applicationScope.launch {
                    history.record(
                        session.bookId,
                        PlaybackEvent.Resume,
                        from = null,
                        to = session.startAt,
                        // PRODUCT_SPEC 6.5 — the session says whose it is, so a launch that outlives a
                        // profile switch still files the entry against the account that opened the book.
                        owner = session.profileId,
                    )
                }
            }
            AppResult.Success(Unit)
        }

    /**
     * The transport control a mini player and a notification share.
     *
     * **The `prepare()` is the fix for a real defect.** A player that has hit an error sits in `STATE_IDLE`,
     * and an idle player ignores `play()` and `seekTo()` alike — a device run found a book that stopped and
     * then could not be started, seeked or recovered except by loading a different book. `prepare()` is the
     * only way out of idle, so the play button now takes it whenever the player is not holding a live
     * timeline. Pressing play must always mean something (product priority 1).
     */
    fun togglePlayPause() {
        applicationScope.launch(mainDispatcher) {
            val media = connect() ?: return@launch
            when {
                media.isPlaying -> media.pause()
                media.needsPreparing() -> {
                    media.prepare()
                    media.play()
                }
                else -> media.play()
            }
        }
    }

    /**
     * PRODUCT_SPEC PLAY-001 — retry after a failure the service gave up on.
     *
     * The service retries a transient error a few times on its own (see [PlaybackRecovery]); this is what
     * the *user* presses when it has stopped trying. Same two calls, because there is only one way out of
     * idle — the difference is who decided to take it.
     */
    fun retry() {
        applicationScope.launch(mainDispatcher) {
            val media = connect() ?: return@launch
            if (media.mediaItemCount == 0) return@launch
            media.prepare()
            media.play()
        }
    }

    /**
     * `true` when the player is idle or holding an error, which are the two states `play()` cannot leave.
     *
     * `STATE_IDLE` is reached by a fresh player, a stopped one and a failed one. Only the last is a defect,
     * but preparing the other two is harmless — Media3 documents `prepare()` on a prepared player as a
     * no-op — and telling them apart would be a distinction the caller does not need.
     */
    private fun MediaController.needsPreparing(): Boolean = playerError != null || playbackState == Player.STATE_IDLE

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
    fun seekTo(position: Duration) = seekTo(position, PlaybackEvent.Seek)

    /**
     * The seek every other jump goes through, carrying **why**.
     *
     * The reason is what makes the history pane readable — "chapter" and "back 30" are different events to a
     * listener even though they are the same call — and it is recorded here rather than at each call site so
     * that no route can move the position without leaving a trace.
     */
    private fun seekTo(position: Duration, event: PlaybackEvent) {
        applicationScope.launch(mainDispatcher) {
            val media = controller ?: return@launch
            if (media.mediaItemCount == 0) return@launch
            val bookId = media.currentMediaItem?.let(MediaItems::bookIdOf)
            val owner = media.currentMediaItem?.let(MediaItems::ownerOf)
            val from = media.bookPosition()
            // ADR-0016 — a book position *is* a player position. The clamp that `GlobalTimeline.cursorFor`
            // used to apply is now Media3's own: it refuses a seek past the window's duration.
            val to = position.coerceAtLeast(Duration.ZERO)
            media.seekTo(to.inWholeMilliseconds)
            publish(media)
            // After the seek, never before: a jump that did not happen is not history. Launched rather than
            // awaited because a database write must not sit between a finger and a position.
            if (bookId != null) applicationScope.launch { history.record(bookId, event, from, to, owner = owner) }
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
            target?.let { seekTo(it, PlaybackEvent.Chapter) }
        }
    }

    fun skipToPreviousChapter() {
        applicationScope.launch(mainDispatcher) {
            val target = GlobalTimeline.previousChapterStart(chapters, currentPosition() ?: return@launch)
            target?.let { seekTo(it, PlaybackEvent.Chapter) }
        }
    }

    /** PRODUCT_SPEC PLAY-003 — jumps to a chapter chosen from a list. */
    fun seekToChapter(chapter: Chapter) = seekTo(chapter.start, PlaybackEvent.Chapter)

    /**
     * PRODUCT_SPEC PLAY-007 — sets the speed for the book playing now, and remembers it for that book.
     *
     * Applied to the player *and* stored, in that order: the listener hears the change immediately and the
     * write happens off the main thread behind it. Storing first would make a fast tap on the plus button
     * wait for a database write between each step.
     *
     * Pitch is preserved because `setPlaybackSpeed` leaves it at 1.0 and Media3's Sonic processor stretches
     * time rather than resampling — PLAY-007's "pitch is preserved" needs no further work.
     */
    fun setSpeed(speed: PlaybackSpeed) {
        applicationScope.launch(mainDispatcher) {
            val media = controller ?: return@launch
            val bookId = media.currentMediaItem?.let(MediaItems::bookIdOf) ?: return@launch
            media.setPlaybackSpeed(speed.value)
            _state.value = _state.value.copy(speed = speed)
            playbackSettings.setSpeedFor(bookId, speed)
        }
    }

    /**
     * Clears the book's override so it follows the profile default again, and applies that default now.
     *
     * Two different things — "no override" and "an override that happens to equal the default" — and this is
     * the first (see `PlaybackSettingsRepository.setSpeedFor`).
     */
    fun clearSpeedOverride() {
        applicationScope.launch(mainDispatcher) {
            val media = controller ?: return@launch
            val bookId = media.currentMediaItem?.let(MediaItems::bookIdOf) ?: return@launch
            playbackSettings.setSpeedFor(bookId, null)
            val fallback = playbackSettings.speedFor(bookId)
            media.setPlaybackSpeed(fallback.value)
            _state.value = _state.value.copy(speed = fallback)
        }
    }

    /** Where the book is now, or `null` when nothing is loaded. Main thread only. */
    private fun currentPosition(): Duration? {
        val media = controller ?: return null
        media.currentMediaItem ?: return null
        return media.bookPosition()
    }

    /**
     * PRODUCT_SPEC PLAY-007 — skips [delta] along the book, forwards or backwards.
     *
     * Still expressed as a seek rather than as `Player.seekForward`, even though ADR-0016 removed the
     * reason it had to be: Media3's own skip uses its configured increment, and PLAY-007's is ours and is
     * configurable per direction.
     */
    fun skipBy(delta: Duration) {
        applicationScope.launch(mainDispatcher) {
            val media = controller ?: return@launch
            media.currentMediaItem ?: return@launch
            seekTo(media.bookPosition() + delta, PlaybackEvent.Skip)
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
     * PRODUCT_SPEC 6.5 steps 2–3 — ends [outgoing]'s playback and does not return until its position is
     * written.
     *
     * ### Suspending is the whole feature
     *
     * 6.5 orders the switch: flush, pause, *then* change the profile context. Every other write in this
     * class is launched and forgotten, deliberately, because a database write must not sit between a finger
     * and a position. This one is awaited, because the thing on the other side of it is the profile context
     * changing — and "flush, then switch" written as two launches is not an order, it is a hope.
     *
     * ### And clearing the queue is what stops the next write
     *
     * Pausing alone leaves the book loaded, and a loaded book keeps a five-second journal, a session sync
     * and a metrics recorder pointed at it. `stop()` plus `clearMediaItems()` empties the player, so
     * `PlaybackService.positionSnapshot` returns `null` and there is no further write to get wrong. The
     * writes that were already in flight when this began are covered by the other half of 6.5 — they carry
     * [outgoing] in the item's extras and land on the right row whatever the active profile becomes.
     *
     * ### 6.5.8 — nothing resumes
     *
     * "Optional continue playing across profile switch is not supported in version 1." The incoming account
     * gets a stopped player, and the listener presses play.
     *
     * Doing nothing when no controller was ever built is correct rather than lazy: no controller means the
     * service was never started, which means nothing is loaded and there is no position to lose.
     */
    suspend fun handOver(outgoing: ProfileId) {
        withContext(mainDispatcher) {
            val media = controller ?: return@withContext
            // Paused first. The flush that follows reads a position that has stopped moving, and a listener
            // hears the switch take effect immediately rather than after a database write.
            media.pause()
            val item = media.currentMediaItem
            if (item != null && media.currentPosition > 0) {
                // Awaited. This is the step 6.5 puts before the context change, and the only way to put it
                // there is to be here when it finishes.
                playbackRepository.recordPosition(
                    bookId = MediaItems.bookIdOf(item),
                    position = media.bookPosition(),
                    duration = media.bookDuration(),
                    // The outgoing profile by name, not by lookup: `setActiveProfile` may already be queued
                    // behind this call, and the item's own owner is the same answer read a different way.
                    owner = MediaItems.ownerOf(item) ?: outgoing,
                )
            }
            media.stop()
            media.clearMediaItems()
        }
        // The connection itself, so the incoming account does not inherit a controller aimed at a session
        // opened as somebody else.
        release()
    }

    /**
     * Releases the controller.
     *
     * Not the *service*: a released controller leaves playback running, which is the point. This only
     * exists so a test — or [handOver], which must not leave one account's controller pointed at another's
     * session — can drop the connection deliberately.
     */
    fun release() {
        ticker?.cancel()
        controller?.release()
        controller = null
        chapters = emptyList()
        lastChapter = null
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
        val built = connector.connect() ?: return null
        built.addListener(ControllerEvents())
        controller = built
        publish(built)
        startTicker()
        return built
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
        val position = if (item == null) Duration.ZERO else media.bookPosition()
        val chapter = if (item == null) null else GlobalTimeline.chapterAt(chapters, position)
        if (hasCrossedInto(chapter)) bookChanges.onChapterCrossed()
        lastChapter = chapter
        _state.value = PlaybackUiState(
            bookId = item?.let(MediaItems::bookIdOf),
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            author = item?.mediaMetadata?.artist?.toString(),
            artworkUri = item?.mediaMetadata?.artworkUri?.toString(),
            isPlaying = media.isPlaying,
            isLoading = media.playbackState == Player.STATE_BUFFERING,
            position = position,
            duration = if (item == null) Duration.ZERO else media.bookDuration(),
            chapters = if (item == null) emptyList() else GlobalTimeline.ordered(chapters),
            currentChapter = chapter,
            // Read from the player rather than from the settings, so the state always reports what is
            // actually happening — including a speed something else set through the media session.
            speed = PlaybackSpeed.of(media.playbackParameters.speed),
            // PRODUCT_SPEC PLAY-001 — a stopped book says so. The service retries a transient error a few
            // times first (`PlaybackRecovery`); by the time this is non-null it has given up, and the only
            // thing left is to tell the listener and offer the button.
            hasFailed = media.playerError != null,
        )
    }

    /**
     * PRODUCT_SPEC PLAY-004 — "chapter change", detected by comparison rather than by counting.
     *
     * A seek can cross several chapters at once and that is still one crossing to report. Both `null` cases are
     * excluded deliberately: the first publish of a book has no previous chapter to have left, and a book with
     * no chapter metadata has nothing to cross.
     */
    private fun hasCrossedInto(chapter: Chapter?): Boolean {
        val previous = lastChapter ?: return false
        return chapter != null && chapter != previous
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
    /** PRODUCT_SPEC PLAY-007 — what the player is actually running at, not what a setting says. */
    val speed: PlaybackSpeed = PlaybackSpeed.Normal,
    /**
     * PRODUCT_SPEC PLAY-001 — playback stopped on an error and the service has stopped retrying.
     *
     * A separate flag rather than an [AppError], because the player is the source and Media3's own message
     * can carry the failing URL — a path on somebody's private server (14.5). What the user needs is that it
     * stopped and that there is a button; what a *diagnostic* needs is the error code, and that is in the
     * event log.
     */
    val hasFailed: Boolean = false,
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
