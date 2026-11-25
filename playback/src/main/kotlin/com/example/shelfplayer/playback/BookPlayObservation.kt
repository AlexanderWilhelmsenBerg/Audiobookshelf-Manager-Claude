package com.example.shelfplayer.playback

import androidx.media3.common.Player

/** Main-thread observation only. A diagnostic audio sample yields immediately when the listener starts a book. */
internal object BookPlayObservation {
    var current: (() -> Player?)? = null
    var onPlay: (() -> Unit)? = null
}
