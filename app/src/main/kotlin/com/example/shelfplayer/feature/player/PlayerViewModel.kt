package com.example.shelfplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.Chapter
import com.example.shelfplayer.core.model.playback.SleepTimerMode
import com.example.shelfplayer.core.model.playback.SleepTimerState
import com.example.shelfplayer.playback.PlaybackController
import com.example.shelfplayer.playback.PlaybackUiState
import com.example.shelfplayer.playback.SleepTimerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val sleepTimer: SleepTimerController,
    private val surface: PlayerSurface,
) : ViewModel() {

    val playback: StateFlow<PlaybackUiState> = controller.state

    /** PRODUCT_SPEC PLAY-008 — the running timer, straight from the object that owns it. */
    val timer: StateFlow<SleepTimerState> = sleepTimer.state

    /** PRODUCT_SPEC PLAY-001 — whether the full-screen player is showing. */
    val isExpanded: StateFlow<Boolean> = surface.isExpanded

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
                is AppResult.Success -> surface.expand()
            }
        }
    }

    fun onExpand() = surface.expand()

    fun onCollapse() = surface.collapse()

    /** PRODUCT_SPEC PLAY-003 — a dragged seek bar, on the book's timeline. */
    fun onSeekTo(position: Duration) = controller.seekTo(position)

    /** PRODUCT_SPEC PLAY-003 — jumps to a chapter chosen from the list. */
    fun onChapterSelected(chapter: Chapter) = controller.seekToChapter(chapter)

    /** PRODUCT_SPEC PLAY-007 — the skip controls. Negative skips back. */
    fun onSkipBy(delta: Duration) = controller.skipBy(delta)

    fun onTogglePlayPause() = controller.togglePlayPause()

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

    fun onMessageShown() {
        _message.value = null
    }
}
