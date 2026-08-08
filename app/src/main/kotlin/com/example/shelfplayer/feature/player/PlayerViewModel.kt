package com.example.shelfplayer.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.playback.PlaybackController
import com.example.shelfplayer.playback.PlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
class PlayerViewModel @Inject constructor(private val controller: PlaybackController) : ViewModel() {

    val playback: StateFlow<PlaybackUiState> = controller.state

    private val _message = MutableStateFlow<String?>(null)

    /** The last failure worth showing, or `null`. Cleared by [onMessageShown]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun onPlay(bookId: LibraryItemId) {
        viewModelScope.launch {
            val result = controller.play(bookId)
            if (result is AppResult.Failure) _message.value = result.error.summary
        }
    }

    fun onTogglePlayPause() = controller.togglePlayPause()

    fun onStop() = controller.stop()

    fun onMessageShown() {
        _message.value = null
    }
}
