package com.example.shelfplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.playback.AutoRewindController
import com.example.shelfplayer.playback.NotificationAccessReader
import com.example.shelfplayer.playback.PlaybackController
import com.example.shelfplayer.playback.PlaybackUiState
import com.example.shelfplayer.playback.SessionSyncCoordinator
import com.example.shelfplayer.playback.SleepTimerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-001 — the screens' view of playback.
 *
 * It owns no player state of its own. Everything in [playback] comes from the media session, so a book
 * started from a headset button renders here identically to one started from a tap, and this view model
 * being recreated on rotation changes nothing about what is playing.
 *
 * The only state it does own is [message]: the failure from the last attempt to start something, which
 * belongs to the screen that asked rather than to the session.
 */
/*
 * `TooManyFunctions` is suppressed, and the reasoning is the same as `AppSettingsDataSource`'s.
 *
 * This is the player's façade: one method per control the player offers, each a single line delegating to
 * the object that owns the behaviour. The count therefore tracks the number of controls, and it crossed the
 * threshold when bookmarks arrived — nineteen before, twenty after, with three of the four bookmark methods
 * already folded into `bookmarkActions` because the sheet wants them as a bundle anyway.
 *
 * The alternatives are worse. Splitting it would give the player two view models over one media session,
 * which is how a play button and a lock screen end up disagreeing about what is playing. Inventing more
 * bundles purely to get under a number would group methods by arithmetic rather than by meaning.
 *
 * The rule protects against a class that does many *kinds* of thing. This one does one kind — forward a
 * user action to the session — many times. If it should be split, the split is by *screen* (the mini player
 * and the full player want different subsets), and that is a change to make deliberately rather than as a
 * side effect of adding a feature.
 */
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val sleepTimer: SleepTimerController,
    private val sessionSync: SessionSyncCoordinator,
    private val notifications: NotificationAccessReader,
    private val autoRewind: AutoRewindController,
    private val playbackHistory: PlaybackHistoryRepository,
    private val bookmarks: BookmarkRepository,
    playbackSettings: PlaybackSettingsRepository,
    private val surface: PlayerSurface,
    private val clock: AppClock,
) : ViewModel() {

    val playback: StateFlow<PlaybackUiState> = controller.state

    /** PRODUCT_SPEC PLAY-008 — the running timer, straight from the object that owns it. */
    val timer: StateFlow<SleepTimerState> = sleepTimer.state

    /** PRODUCT_SPEC PLAY-001 — whether the full-screen player is showing. */
    val isExpanded: StateFlow<Boolean> = surface.isExpanded

    /**
     * PRODUCT_SPEC PLAY-007 — the configured skip intervals, so the buttons match what they do.
     *
     * The whole settings object rather than the two durations: the player also needs to know whether the
     * speed it is showing is a per-book override, and one flow is one recomposition.
     */
    val settings: StateFlow<PlaybackSettings> = playbackSettings.observeSettings().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = PlaybackSettings.Default,
    )

    /**
     * PRODUCT_SPEC PLAY-002 — the outputs the book can be sent to, for the chooser on the player card.
     *
     * Through [PlaybackController], which is where the rest of this screen's playback state comes from —
     * so the tick in the menu and the device the player was told to use are one value, not two.
     */
    val outputs: StateFlow<List<AudioOutput>> = controller.outputs

    /**
     * PRODUCT_SPEC PLAY-002 — which output the listener picked, for the tick in the menu.
     *
     * Deliberately separate from the routed flag on each [AudioOutput]: the tick is what was asked for and
     * the "playing here" label is what the platform did with it, and a device run proved the two disagree
     * when a request is declined.
     */
    val selectedOutput: StateFlow<String?> = controller.selectedOutput

    /** PRODUCT_SPEC PLAY-009 — "applied rewind is visible briefly and can be undone". */
    val rewind: StateFlow<AutoRewindController.Applied?> = autoRewind.lastApplied

    /**
     * PRODUCT_SPEC PLAY-003 — the playing book's jumps, for the history pane.
     *
     * Keyed off whatever is playing rather than off a book id the screen passes in: the pane is part of the
     * player, and the player follows the session. `flatMapLatest` so switching book switches the list
     * instead of leaving the previous book's on screen.
     */
    val history: StateFlow<List<PlaybackHistoryEntry>> = controller.state
        .map { it.bookId }
        .distinctUntilChanged()
        .flatMapLatest { bookId ->
            if (bookId == null) flowOf(emptyList()) else playbackHistory.observe(bookId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    /**
     * PRODUCT_SPEC PLAY-003 — pulls the **server's** own session records in, when the pane is opened.
     *
     * ### Why here and not on every sync
     *
     * The endpoint is account-wide and paged — the server has no per-book session route — so this reads a
     * page of the account's sessions and keeps the ones for this book. Putting that on `SyncAccountUseCase`
     * would add a request to the app's cheapest and most frequent call, for data exactly one screen reads.
     * Opening the pane is when somebody is asking the question.
     *
     * The rows are **persisted**, not merged for display, so [history] emits them from Room like any other
     * event and they are still there with no network. Failure is silent inside the repository: a pane whose
     * local half is good must not become an error because the server was unreachable.
     */
    fun onOpenHistory() {
        val bookId = controller.state.value.bookId ?: return
        viewModelScope.launch { playbackHistory.refreshServerSessions(bookId) }
    }

    /**
     * PRODUCT_SPEC 11.1 — the playing book's bookmarks.
     *
     * Keyed off whatever is playing, like [history] and for the same reason: the sheet is part of the
     * player, and the player follows the session. `flatMapLatest` so switching book switches the list
     * instead of leaving the previous book's on screen.
     */
    val bookmarkList: StateFlow<List<Bookmark>> = controller.state
        .map { it.bookId }
        .distinctUntilChanged()
        .flatMapLatest { bookId ->
            if (bookId == null) flowOf(emptyList()) else bookmarks.observe(bookId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = emptyList(),
        )

    private val _isNotificationBlocked = MutableStateFlow(false)
    val isNotificationBlocked: StateFlow<Boolean> = _isNotificationBlocked.asStateFlow()

    private val _bookmarkAdded = MutableStateFlow<Duration?>(null)
    val bookmarkAdded: StateFlow<Duration?> = _bookmarkAdded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The moment BookWave itself most recently paused this loaded book. */
    private var locallyPausedAt: Instant? = null

    fun onPlay(bookId: LibraryItemId) {
        startPlayback(bookId, onSuccess = ::onExpand)
    }

    fun onPlayFromShelf(bookId: LibraryItemId) {
        startPlayback(bookId, onSuccess = {})
    }

    private fun startPlayback(bookId: LibraryItemId, onSuccess: () -> Unit) {
        viewModelScope.launch {
            when (val result = controller.play(bookId)) {
                is AppResult.Failure -> _message.value = result.error.summary
                is AppResult.Success -> onSuccess()
            }
        }
    }

    fun onExpand() {
        refreshNotificationAccess()
        surface.expand()
    }

    fun refreshNotificationAccess() {
        _isNotificationBlocked.value = notifications.read().isBlocked
    }

    fun onCollapse() = surface.collapse()

    fun onSeekTo(position: Duration) = controller.seekTo(position)

    fun onOutputSelected(id: String?) = controller.selectOutput(id)

    fun onChapterSelected(chapter: Chapter) = controller.seekToChapter(chapter)

    fun onSkipBack() = controller.skipBy(-settings.value.skips.back)

    fun onSkipForward() = controller.skipBy(settings.value.skips.forward)

    fun onSpeedSelected(speed: PlaybackSpeed) = controller.setSpeed(speed)

    fun onSpeedCleared() = controller.clearSpeedOverride()

    fun onUndoRewind() = autoRewind.undo()

    fun onRewindNoticeShown() = autoRewind.dismissUndo()

    /**
     * Pauses immediately; before resuming, checks whether another client changed this same book afterwards.
     *
     * No "furthest position wins" rule: a newer external rewind is intentional progress too. If the session
     * read fails, the repository answers false and the loaded player resumes locally, so server lag or an
     * outage cannot move a book backwards.
     */
    fun onTogglePlayPause() {
        val current = playback.value
        if (current.isPlaying) {
            locallyPausedAt = clock.now()
            controller.togglePlayPause()
            return
        }
        val bookId = current.bookId
        if (bookId == null) {
            controller.togglePlayPause()
            return
        }
        val pausedAt = locallyPausedAt
        if (pausedAt == null) {
            startPlayback(bookId, onSuccess = {})
            return
        }
        viewModelScope.launch {
            if (playbackHistory.hasNewerExternalSession(bookId, pausedAt)) {
                when (val result = controller.play(bookId)) {
                    is AppResult.Failure -> _message.value = result.error.summary
                    is AppResult.Success -> locallyPausedAt = null
                }
            } else {
                controller.togglePlayPause()
            }
        }
    }

    fun onRetry() = controller.retry()

    fun onStop() {
        surface.collapse()
        locallyPausedAt = null
        controller.stop()
    }

    fun onSleepTimerSelected(mode: SleepTimerMode?) {
        viewModelScope.launch {
            if (mode == null) {
                sleepTimer.cancel()
                return@launch
            }
            val result = sleepTimer.start(mode)
            if (result is AppResult.Failure) _message.value = result.error.summary
        }
    }

    fun onAppBackgrounded() {
        sessionSync.request(SyncTrigger.AppBackgrounded)
        sessionSync.drain()
    }

    fun onAddBookmark() {
        val state = playback.value
        val bookId = state.bookId ?: return
        val at = Bookmark.roundedFrom(state.position)
        viewModelScope.launch {
            when (val result = bookmarks.add(bookId, at, title = "")) {
                is AppResult.Failure -> _message.value = result.error.summary
                is AppResult.Success -> _bookmarkAdded.value = at
            }
        }
    }

    fun onBookmarkAddedShown() {
        _bookmarkAdded.value = null
    }

    val bookmarkActions: BookmarkActions = BookmarkActions(
        onAdd = ::onAddBookmark,
        onGoTo = ::onSeekTo,
        onRename = { at, title -> withBookmark { bookId -> bookmarks.rename(bookId, at, title) } },
        onRemove = { at -> withBookmark { bookId -> bookmarks.remove(bookId, at) } },
    )

    private fun withBookmark(write: suspend (LibraryItemId) -> AppResult<Unit>) {
        val bookId = playback.value.bookId ?: return
        viewModelScope.launch {
            val result = write(bookId)
            if (result is AppResult.Failure) _message.value = result.error.summary
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
