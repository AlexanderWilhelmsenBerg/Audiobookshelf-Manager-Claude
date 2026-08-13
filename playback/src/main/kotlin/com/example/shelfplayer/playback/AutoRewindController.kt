package com.example.shelfplayer.playback

import androidx.media3.common.Player
import com.example.shelfplayer.core.common.dispatcher.ApplicationScope
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info
import com.example.shelfplayer.core.common.time.AppClock
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.AutoRewind
import com.example.shelfplayer.core.model.playback.PlaybackJump
import com.example.shelfplayer.domain.playback.AutoRewindMath
import com.example.shelfplayer.domain.repository.PlaybackHistoryRepository
import com.example.shelfplayer.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-009 — rewinding a few seconds when a listener comes back after a pause.
 *
 * ### Why this is not "on resume, seek backwards"
 *
 * Three of the requirement's five criteria are about when *not* to do it, and each one exists because the
 * naive version is wrong in a way the user experiences as the app losing their place:
 *
 *  - **After a user seek.** Somebody who dragged the bar to a position chose that position. Moving it is the
 *    app overruling them.
 *  - **After an audio-focus interruption.** A navigation prompt or a phone call pauses playback without the
 *    listener doing anything, and rewinding on the way out would replay ten seconds every time a satnav
 *    spoke.
 *  - **Before a chapter or book start.** Handled by [AutoRewindMath] — a rewind that crosses a boundary
 *    replays the end of a scene the listener finished.
 *
 * So the controller is told *why* playback stopped rather than inferring it, for the reason
 * `SessionSyncCoordinator` gives about triggers: a reason inferred from state is a reason that is wrong on
 * the one path nobody tested.
 *
 * ### A singleton, attached to the player
 *
 * Same shape as [SleepTimerController]: the service and the app's UI share one, so the player screen can
 * show the undo for a rewind the *service* applied while the app was in the background.
 *
 * ### The chapter floor needs the app process
 *
 * [chapters] is set when the app starts a book, because the chapter list deliberately does not travel in the
 * playlist (see `PlaybackController`). A resume driven by a headset after the app process has gone therefore
 * has no chapter floor and clamps to the book start only. That is a smaller rewind than intended rather than
 * a larger one, which is the safe direction.
 */
@Singleton
class AutoRewindController @Inject constructor(
    private val repository: PlaybackSettingsRepository,
    private val clock: AppClock,
    private val history: PlaybackHistoryRepository,
    private val logger: Logger,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    private var player: Player? = null
    private var chapters: List<Chapter> = emptyList()

    /**
     * When playback stopped, on the monotonic clock, or `null` when a rewind must not be applied.
     *
     * One field carries both "how long ago" and "should we": a pause the requirement excludes simply does not
     * record a time, so [onResumed] has nothing to act on. That is one state to reason about rather than a
     * timestamp plus a flag that can disagree with it.
     */
    private var pausedAt: Duration? = null

    @Volatile
    private var settings: AutoRewind = AutoRewind.Default

    private val _lastApplied = MutableStateFlow<Applied?>(null)

    /** PRODUCT_SPEC PLAY-009 — "applied rewind is visible briefly and can be undone". */
    val lastApplied: StateFlow<Applied?> = _lastApplied.asStateFlow()

    init {
        applicationScope.launch {
            repository.observeSettings().collect { settings = it.autoRewind }
        }
    }

    fun attach(player: Player?) {
        this.player = player
        if (player == null) pausedAt = null
    }

    /** The chapter floor for the book now playing. Cleared with the book, so it cannot outlive it. */
    fun onBookChanged(chapters: List<Chapter>) {
        this.chapters = chapters
        pausedAt = null
        _lastApplied.value = null
    }

    /**
     * Records that playback stopped, and whether a rewind may follow.
     *
     * @param wasUserInitiated `false` for an audio-focus loss or anything else the listener did not ask for.
     *   PLAY-009 excludes those, and the exclusion is here rather than at the call site so every caller gets
     *   it.
     */
    fun onPaused(wasUserInitiated: Boolean) {
        pausedAt = if (wasUserInitiated) clock.elapsed() else null
    }

    /**
     * Cancels a pending rewind because the listener chose a position themselves.
     *
     * PLAY-009: "rewind is not applied after a user seek". Called for any seek, including one made while
     * paused — which is exactly the case the requirement is about.
     */
    fun onSeeked() {
        pausedAt = null
    }

    /**
     * Applies the rewind, if one is due. Main thread: every [Player] read has to be.
     *
     * Reading the position and seeking in the same call rather than posting the seek: a rewind that landed
     * after playback had already started would be audible as a stutter, which is worse than no rewind.
     */
    fun onResumed() {
        val stoppedAt = pausedAt ?: return
        pausedAt = null
        val media = player ?: return
        media.currentMediaItem ?: return
        val from = media.bookPosition()
        val resumeAt = AutoRewindMath.resumeAt(
            from = from,
            pausedFor = clock.elapsed() - stoppedAt,
            settings = settings,
            chapters = chapters,
        )
        val applied = AutoRewindMath.appliedAmount(from, resumeAt)
        if (applied <= Duration.ZERO) return
        seekTo(media, resumeAt)
        _lastApplied.value = Applied(amount = applied, returnTo = from)
        // PRODUCT_SPEC PLAY-003 — the app moved the position, so it goes in the history like any other jump.
        // The transient undo notice lasts seconds; this outlives it, which matters for a rewind somebody only
        // notices two chapters later.
        media.currentMediaItem?.let(MediaItems::bookIdOf)?.let { bookId ->
            applicationScope.launch { history.record(bookId, PlaybackJump.AutoRewind, from, resumeAt) }
        }
        logger.info(
            LogCategory.Playback,
            "Rewound after a pause",
            LogField.Millis("amount", applied.inWholeMilliseconds),
        )
    }

    /**
     * PRODUCT_SPEC PLAY-009 — puts the position back where the pause left it.
     *
     * Seeks to the *remembered* position rather than adding the amount to wherever the player is now. By the
     * time somebody taps undo, several seconds of the rewound audio have played, and adding would land them
     * ahead of where they paused.
     */
    fun undo() {
        val applied = _lastApplied.value ?: return
        _lastApplied.value = null
        val media = player ?: return
        seekTo(media, applied.returnTo)
    }

    /** Dismisses the undo without moving anything, once the listener has had time to see it. */
    fun dismissUndo() {
        _lastApplied.value = null
    }

    // ADR-0016 — a book position is a player position, so there is nothing left to convert.
    private fun seekTo(media: Player, position: Duration) {
        media.seekTo(position.inWholeMilliseconds.coerceAtLeast(0))
    }

    /**
     * @property amount how far the position actually moved, after clamping.
     * @property returnTo where the listener paused, which is what undo restores.
     */
    data class Applied(val amount: Duration, val returnTo: Duration)
}
