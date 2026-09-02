package com.example.shelfplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Bookmark
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.core.model.playback.PlaybackEvent
import com.example.shelfplayer.core.model.playback.PlaybackHistoryEntry
import com.example.shelfplayer.core.model.playback.PlaybackSettings
import com.example.shelfplayer.core.model.playback.PlaybackSpeed
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.core.model.playback.SyncTrigger
import com.example.shelfplayer.domain.repository.BookmarkRepository
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import com.example.shelfplayer.playback.AutoRewindController
import com.example.shelfplayer.playback.NotificationAccessReader
import com.example.shelfplayer.playback.PlaybackController
import com.example.shelfplayer.playback.PlaybackUiState
import com.example.shelfplayer.playback.SessionSyncCoordinator
import com.example.shelfplayer.playback.SleepTimerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * PRODUCT_SPEC PLAY-003 / SYNC-002 — the two playback repositories this screen reads and writes through.
 *
 * Bundled for the reason `SettingsViewModel`'s `DeviceReaders` is: they are the same kind of collaborator,
 * and a ViewModel that already holds a controller, a sleep timer, a sync coordinator and a surface has a
 * real parameter budget — detekt's constructor limit is ten and the freshness check's arrival made it ten
 * exactly.
 *
 * They stayed two repositories rather than becoming one. [history] answers "what has happened to this
 * book"; [positions] answers "where is the listener and what does the server think" — different lifetimes,
 * and the second is written every few seconds while a book plays.
 *
 * `@Inject constructor` so Hilt assembles it; nothing constructs one by hand except a test.
 */
data class PlaybackData @Inject constructor(val history: PlaybackHistoryRepository, val positions: PlaybackRepository)

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
    private val playbackData: PlaybackData,
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
            if (bookId == null) flowOf(emptyList()) else playbackData.history.observe(bookId)
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
        viewModelScope.launch { playbackData.history.refreshServerSessions(bookId) }
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

    /** At most one server-freshness check may own the next in-app Play. */
    private var resumeJob: Job? = null

    /**
     * When this ViewModel last paused, or `null` if it has not.
     *
     * Only pauses that came through [onTogglePlayPause] are seen; a pause from the notification, the car or
     * a headset button goes straight to the service. That is why `null` and a stale mark both mean *check*
     * — see [shouldCheckServer].
     */
    private var pausedAt: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * PRODUCT_SPEC PLAY-001 — starts a book and opens the player over it.
     *
     * Expanding only on success. A play that failed leaves the user on the book screen with a message,
     * which is where they can do something about it; a full-screen player showing nothing would hide
     * both the message and the way back.
     */
    fun onPlay(bookId: LibraryItemId) {
        startPlayback(bookId, onSuccess = ::onExpand)
    }

    /**
     * PRODUCT_SPEC PLAY-001 — starts a shelf book while preserving the listener's browse context.
     *
     * The media session update makes the mini player appear. Expanding here would turn the shelf's
     * compact play affordance into an unexpected navigation action and discard the value of keeping
     * the shelf composed beneath the player.
     */
    fun onPlayFromShelf(bookId: LibraryItemId) {
        startPlayback(bookId, onSuccess = {})
    }

    private fun startPlayback(bookId: LibraryItemId, onSuccess: () -> Unit) {
        cancelResumeCheck()
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

    /** Called when the player becomes visible, which is the only time the answer is worth showing. */
    fun refreshNotificationAccess() {
        _isNotificationBlocked.value = notifications.read().isBlocked
    }

    fun onCollapse() = surface.collapse()

    /** PRODUCT_SPEC PLAY-003 — a dragged seek bar, on the book's timeline. */
    fun onSeekTo(position: Duration) = controller.seekTo(position)

    /**
     * PRODUCT_SPEC PLAY-002 — sends the book to a chosen output, or `null` to let the system decide.
     *
     * Not remembered across restarts, deliberately: see ADR-0027.
     */
    fun onOutputSelected(id: String?) = controller.selectOutput(id)

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

    /**
     * PRODUCT_SPEC SYNC-002 — an in-app Play checks the server's position before resuming, sometimes.
     *
     * ### What it costs, which is the part that had to be got right
     *
     * The first version read the account's whole listening history, page by page, before any audio. On a
     * busy account that is several round trips for one tap, and it was reported from a device as *"pressing
     * play takes very long time"*. Three things fix it, and each is what one of the two reference clients
     * does — `docs/api-compatibility.md` § SYNC-002 records the evidence:
     *
     *  1. **one request for one book**, `GET /api/me/progress/{id}`, instead of an account-wide sweep;
     *  2. **capped** — see [SERVER_CHECK_TIMEOUT] — so a slow server delays a resume by two seconds at most;
     *  3. **skipped entirely for a resume that cannot have been overtaken** — see [MIN_PAUSE_BEFORE_CHECK].
     *
     * ### Only from here
     *
     * This is the app's own transport. A media button, the notification, the car and a headset all reach
     * `PlaybackController` without passing through this ViewModel, so none of them waits on the network to
     * start — which is the behaviour absorb arrived at deliberately and this gets for free from where the
     * check happens to live.
     *
     * ### And when it says the server is ahead
     *
     * The player **seeks**, rather than being torn down and reloaded. It is already loaded with the right
     * book; the position is the only thing that was wrong, and the reload was a second source of the delay
     * this is fixing.
     *
     * Whichever of the three outcomes it lands on is written into the book's history by
     * [recordCheckOutcome], so "resumed on a verified position" and "resumed because the server was
     * unreachable" are distinguishable afterwards rather than only in the moment.
     */
    fun onTogglePlayPause() {
        val current = playback.value
        if (current.isPlaying) {
            cancelResumeCheck()
            pausedAt = TimeSource.Monotonic.markNow()
            controller.togglePlayPause()
            return
        }
        val bookId = current.bookId
        if (bookId == null || !shouldCheckServer()) {
            controller.togglePlayPause()
            return
        }
        if (resumeJob?.isActive == true) return
        val pressedAt = current.position
        resumeJob = viewModelScope.launch {
            var outcome: ExternalSessionCheck = ExternalSessionCheck.Unavailable
            try {
                // The cap on the whole thing. A late answer is discarded rather than applied: a seek
                // arriving after audio has started is worse than the position it would have corrected,
                // which is the conclusion absorb reached from the other direction and wrote down.
                outcome = withTimeoutOrNull(SERVER_CHECK_TIMEOUT) {
                    playbackData.positions.checkServerPosition(bookId, pressedAt)
                } ?: ExternalSessionCheck.Unavailable
                val latest = playback.value
                // A slower answer may outlive the Play it belonged to. The book and state that now own the
                // player win; an old request must never move or toggle them.
                if (latest.bookId != bookId || latest.isPlaying) return@launch
                (outcome as? ExternalSessionCheck.Ahead)?.let { ahead -> controller.seekTo(ahead.position) }
                controller.togglePlayPause()
            } finally {
                recordCheckOutcome(bookId, pressedAt, outcome)
                resumeJob = null
            }
        }
    }

    /**
     * Whether this resume is one that could plausibly have been overtaken.
     *
     * A pause of a few seconds — a knock at the door, a wrong button, scrubbing while paused — cannot have
     * been followed by somebody else listening on another device, so asking is a round trip spent to learn
     * nothing. Absorb draws the same line at two minutes and it is the single biggest reason its Play feels
     * instant; this is the same idea with a shorter fuse, because BookWave's check is capped and absorb's
     * whole session refresh is not.
     *
     * A monotonic mark rather than a clock: the question is "how long since", which is an interval, and an
     * interval read from wall-clock time is wrong across a time-zone change or an NTP correction. `null` —
     * nothing has paused through this ViewModel yet, which is every resume after a cold start or a pause
     * from the notification — means **check**. Erring towards asking is the safe direction: the cost is two
     * seconds at worst and the alternative is resuming on a position somebody else has moved.
     */
    private fun shouldCheckServer(): Boolean =
        pausedAt?.let { mark -> mark.elapsedNow() >= MIN_PAUSE_BEFORE_CHECK } ?: true

    /**
     * PRODUCT_SPEC SYNC-002 — writes down which of the three outcomes this Play resumed under.
     *
     * The check is otherwise invisible: verified-and-current and could-not-reach-the-server produce the same
     * audio from the same position, and the difference only matters later, when somebody asks why a position
     * is not where they left it. The row is drawn as a small cloud on the Play row it belongs to rather than
     * as a row of its own — see `HistorySheet` — so the record costs a listener nothing to scroll past.
     *
     * `NonCancellable` because the outcome that most needs writing down is the one that was interrupted, and
     * a `finally` block in a cancelled coroutine cannot suspend otherwise. It wraps one local database write,
     * so there is no unbounded work being made uninterruptible.
     */
    private suspend fun recordCheckOutcome(bookId: LibraryItemId, position: Duration, outcome: ExternalSessionCheck) =
        withContext(NonCancellable) {
            playbackData.history.record(
                bookId = bookId,
                event = when (outcome) {
                    is ExternalSessionCheck.Ahead -> PlaybackEvent.ServerCheckAhead
                    ExternalSessionCheck.Current -> PlaybackEvent.ServerCheckCurrent
                    ExternalSessionCheck.Unavailable -> PlaybackEvent.ServerCheckUnavailable
                },
                from = null,
                to = position,
            )
        }

    /** PRODUCT_SPEC PLAY-001 — re-prepares a player the service gave up on. */
    fun onRetry() = controller.retry()

    /** Stopping closes the player as well: there is nothing left for it to show. */
    fun onStop() {
        cancelResumeCheck()
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

    private fun cancelResumeCheck() {
        resumeJob?.cancel()
        resumeJob = null
    }

    private companion object {
        /**
         * PRODUCT_SPEC SYNC-002 — how long a pause has to have lasted before a resume asks the server.
         *
         * Thirty seconds. Nobody listening on a second device gets a book open, played and synced inside
         * that window, so a shorter pause cannot have been overtaken and the request would buy nothing.
         *
         * The number matches the constant the official app used for the same gate before it disabled its
         * whole check (`PlayerListener.PAUSE_LEN_BEFORE_RECHECK`, 30 seconds); absorb draws the line at two
         * minutes. The shorter of the two, because a capped two-second read is a cheaper thing to spend on
         * a maybe than absorb's uncapped session refresh.
         */
        val MIN_PAUSE_BEFORE_CHECK: Duration = 30.seconds

        /**
         * PRODUCT_SPEC SYNC-002 — the longest a Play waits on the freshness check before resuming anyway.
         *
         * Two seconds, and the number is not arbitrary: `docs/api-compatibility.md` § SYNC-002 records
         * where the two reference clients landed — absorb caps the same read at two seconds, and the
         * official app's (disabled) version used a three-second client built for exactly this. Two is the
         * shorter of the two observed choices, and this check is the only thing between a tap and audio.
         *
         * Here rather than in the repository because it is a decision about *this* caller. A background
         * refresh reading the same position has no reason to give up after two seconds.
         */
        val SERVER_CHECK_TIMEOUT: Duration = 2.seconds

        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
