package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** Observes the media clock returned to the renderer, after the sink's output-to-media time mapping. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DiagnosticAudioSink(delegate: AudioSink, private val trace: AudioOutputWriteTrace) :
    ForwardingAudioSink(delegate) {
    private val position = trace.ClockSampler(AudioOutputWriteTrace.ClockStage.Media3Sink)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val capture = trace.active
        val positionUs = super.getCurrentPositionUs(sourceEnded)
        capture?.let { position.sample(it, positionUs) }
        return positionUs
    }

    override fun handleDiscontinuity() {
        event("audio-sink-discontinuity")
        super.handleDiscontinuity()
    }

    override fun flush() {
        event("audio-sink-flush")
        super.flush()
    }

    private fun event(kind: String) {
        trace.active?.let { trace.outputEvent(it, null, kind) }
    }
}
