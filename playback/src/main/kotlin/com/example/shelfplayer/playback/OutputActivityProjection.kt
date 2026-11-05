package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource

/** One captured publication keeps measured track, policy prediction and selected preference distinct. */
internal object OutputActivityProjection {
    fun rows(
        available: List<AudioOutput>,
        actual: Set<String>?,
        predicted: Set<String>?,
        selected: String?,
    ): List<AudioOutput> {
        val routed = actual ?: predicted
        val source = when {
            actual != null -> AudioOutputActivitySource.PlayingAudioTrack
            predicted != null -> AudioOutputActivitySource.FrameworkMediaPolicy
            selected != null -> AudioOutputActivitySource.SelectedPreference
            else -> AudioOutputActivitySource.Unmeasured
        }
        return available.map { output ->
            output.copy(isActive = routed?.contains(output.id) ?: (output.id == selected), activitySource = source)
        }
    }
}
