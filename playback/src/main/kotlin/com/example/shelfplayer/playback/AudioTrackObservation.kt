package com.example.shelfplayer.playback

/** In-memory evidence from one current playing AudioTrack; no route name, address or platform ID escapes. */
internal data class AudioTrackObservation(val observerEpoch: Long, val path: AudioTrackPath)

internal sealed interface AudioTrackPath {
    data class Endpoint(val id: String) : AudioTrackPath

    /** A platform submix path. Only positive current projection context can authorize Auto delegation. */
    data object RemoteSubmix : AudioTrackPath

    data object Unresolved : AudioTrackPath

    data object Multiple : AudioTrackPath
}
