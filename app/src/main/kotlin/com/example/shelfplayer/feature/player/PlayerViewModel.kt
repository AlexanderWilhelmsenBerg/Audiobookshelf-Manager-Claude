package com.example.shelfplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Chapter
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

    /**
     * PRODUCT_SPEC PLAY-001 — whether the notification this book should have is being blocked.
     *
     * Re-read when the player opens rather than observed: notification state has no change callback, and the
     * one moment it matters is when a listener is looking at the player wondering where their controls went.
     * A trip to the system settings and back re-opens the player, which re-reads it.
     */
    private val _isNotificationBlocked = MutableStateFlow(false)

    val isNotificationBlocked: StateFlow<Boolean> = _isNotificationBlocked.asStateFlow()

    /**
     * Where the last bookmark landed, so the button can confirm without opening anything.
     *
     * A position rather than a boolean: "Bookmarked at 2:41:07" tells a listener the thing they would check
     * for themselves, and a bare "Bookmarked" leaves them opening the sheet to find out.
     */
    private val _bookmarkAdded = MutableStateFlow<Duration?>(null)

    val bookmarkAdded: StateFlow<Duration?> = _bookmarkAdded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** The last failure worth showing, or `null`. Cleared by [onMessageShown]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * PRODUCT_SPEC PLAY-001 — starts a book and opens the player over it.
     *
     * Expanding only on success. A play that failed leaves the user on the book screen with a message,
     * which is where they can do something about it; a full-screen player showing nothing would hide
     * both the message and the way back.
     */
    fun onPlay(bookId: LibraryItemId) {
        viewModelScope.launch {
            when (val result = controller.play(bookId)) {
                is AppResult.Failure -> _message.value = result.error.summary
                is AppResult.Success -> onExpand()
            }
        }
    }

    fun onExpand() {
        refreshNotificationAccess()
        surface.expand()
    }

    /** Called when the player becomes visible, which is the only time the answer is worth showing. */
    fun refreshNotificationAccess() {
        _isNotificationBlocked.value = notifications.read().isBlocked
    }

    fun onCollapse() = surface.collapse()

    /** PRODUCT_SPEC PLAY-003 — a dragged seek bar, on the book's timeline. */
    fun onSeekTo(position: Duration) = controller.seekTo(position)

    /** PRODUCT_SPEC PLAY-003 — jumps to a chapter chosen from the list. */
    fun onChapterSelected(chapter: Chapter) = controller.seekToChapter(chapter)

    /** PRODUCT_SPEC PLAY-007 — the skip controls, at the configured intervals. */
    fun onSkipBack() = controller.skipBy(-settings.value.skips.back)

    fun onSkipForward() = controller.skipBy(settings.value.skips.forward)

    /** PRODUCT_SPEC PLAY-007 — sets the speed for the book playing now, remembering it for that book. */
    fun onSpeedSelected(speed: PlaybackSpeed) = controller.setSpeed(speed)

    /** Returns the book to the profile default, which is not the same as setting it to 1.0×. */
    fun onSpeedCleared() = controller.clearSpeedOverride()

    /** PRODUCT_SPEC PLAY-009 — puts the position back where the pause left it. */
    fun onUndoRewind() = autoRewind.undo()

    fun onRewindNoticeShown() = autoRewind.dismissUndo()

    fun onTogglePlayPause() = controller.togglePlayPause()

    /** PRODUCT_SPEC PLAY-001 — re-prepares a player the service gave up on. */
    fun onRetry() = controller.retry()

    /** Stopping closes the player as well: there is nothing left for it to show. */
    fun onStop() {
        surface.collapse()
        controller.stop()
    }

    /**
     * PRODUCT_SPEC PLAY-008 — sets a timer, or turns one off when [mode] is `null`.
     *
     * One entry point rather than a set and a cancel, because the sheet presents them as one row of
     * choices and "off" is one of them.
     */
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

    /**
     * PRODUCT_SPEC PLAY-004 — "app background transition when possible".
     *
     * Called from the composition's lifecycle rather than from an `Activity` override, because this is the
     * lifecycle the player surface actually shares. It reaches the service's session directly:
     * `SessionSyncCoordinator` is a `@Singleton` and the service declares no `android:process`, so there is
     * one of it and both halves of the app hold the same one.
     *
     * A rotation also stops the activity, so this can fire without the app having gone anywhere. That costs a
     * request the server would have had thirty seconds later anyway, and the alternative — inferring a real
     * background transition from state — is the kind of guess that misses the case it exists for.
     */
    fun onAppBackgrounded() {
        sessionSync.request(SyncTrigger.AppBackgrounded)
        sessionSync.drain()
    }

    /**
     * PRODUCT_SPEC 11.1 — keeps wherever the listener is, with no note.
     *
     * The note comes later, from the sheet, and that ordering is the point: a listener presses this because
     * they just heard something, and a dialog between the button and the bookmark is a dialog that loses the
     * moment. The confirmation says where it landed, because the sheet does not open.
     *
     * The position is truncated to the second by [Bookmark.roundedFrom] — the server keys bookmarks by it,
     * so a finer position is one the app could never ask to delete.
     */
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

    /**
     * PRODUCT_SPEC 11.1 — what a bookmark row can do, in the shape the sheet asks for.
     *
     * A property rather than three methods, and not only to keep this class under detekt's function count:
     * `BookmarkSheet` takes a [BookmarkActions] bundle, so building it anywhere else means a caller
     * assembling three references that only ever travel together. Going to a bookmark is a seek, so it is
     * the same [onSeekTo] every other jump uses — a bookmark is a position, not a special kind of playback.
     */
    val bookmarkActions: BookmarkActions = BookmarkActions(
        onAdd = ::onAddBookmark,
        onGoTo = ::onSeekTo,
        onRename = { at, title -> withBookmark { bookId -> bookmarks.rename(bookId, at, title) } },
        onRemove = { at -> withBookmark { bookId -> bookmarks.remove(bookId, at) } },
    )

    /**
     * Runs a bookmark write against the playing book, surfacing a failure as a message.
     *
     * Silent with nothing playing: the sheet is part of the player, so there is no route to it without a
     * book, and a message about a book that is not there would be a message about nothing.
     */
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
