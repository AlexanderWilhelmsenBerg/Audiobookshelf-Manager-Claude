package com.example.shelfplayer.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRODUCT_SPEC PLAY-001 — whether the player is showing full screen or as the bar.
 *
 * ### Why this is a singleton and not ViewModel state
 *
 * Two places decide it and they are not the same ViewModel. The book screen expands the player by
 * pressing play; the activity draws whichever form is current. `hiltViewModel()` scopes to the
 * navigation entry, so those two get *different* `PlayerViewModel`s — a flag on either would be
 * invisible to the other.
 *
 * ### Why it is not on `PlaybackController`
 *
 * Because it is not playback. The controller's state is what the media session says, and everything in
 * it is equally true of a headset and of Android Auto. Whether *this app's* player is expanded is a
 * fact about one screen on one device, and putting it in `:playback` would mean the module that owns
 * the audio also owned a piece of window state.
 *
 * It deliberately does not survive process death. A player expanded when the app was killed reopening
 * over whatever the user launched next is worse than starting on the shelf.
 */
@Singleton
class PlayerSurface @Inject constructor() {
    private val _isExpanded = MutableStateFlow(false)

    /** `true` while the full-screen player is showing. */
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    fun expand() {
        _isExpanded.value = true
    }

    fun collapse() {
        _isExpanded.value = false
    }
}
