package com.example.shelfplayer.core.model.playback

/** Device-wide tone settings for decoded audiobook audio. All zero gains reproduce the original. */
data class PlaybackEqualizer(val bass: Int = 0, val presence: Int = 0, val treble: Int = 0) {
    fun bounded(): PlaybackEqualizer = PlaybackEqualizer(
        bass.coerceIn(GainRange),
        presence.coerceIn(GainRange),
        treble.coerceIn(GainRange),
    )

    companion object {
        val GainRange = -6..6
        val Original = PlaybackEqualizer()
        val Warm = PlaybackEqualizer(bass = 3, treble = -1)
        val Clear = PlaybackEqualizer(bass = -2, presence = 3, treble = 1)
    }
}
