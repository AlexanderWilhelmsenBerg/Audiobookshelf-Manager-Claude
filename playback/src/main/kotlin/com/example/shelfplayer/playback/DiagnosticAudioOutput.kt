package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import java.nio.ByteBuffer

/** Media3's output still owns every buffer, return value and playback effect. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DiagnosticAudioOutput(
    delegate: AudioOutput,
    private val trace: AudioOutputWriteTrace,
    private val epoch: Long,
    private val format: AudioOutputWriteTrace.Format,
) : ForwardingAudioOutput(delegate) {
    private var blockedCapture: AudioOutputWriteTrace.Capture? = null

    init {
        event("audio-output-open")
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val capture = trace.active ?: return super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        val offset = buffer.position()
        val completed = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        val accepted = buffer.position() - offset
        // A retry loop with no accepted data must not hide the preceding progress in the rolling buffer.
        if (accepted > 0 || blockedCapture !== capture) {
            trace.write(
                capture,
                epoch,
                AudioOutputWriteTrace.Write(format, presentationTimeUs, offset, accepted, completed),
            )
            blockedCapture = capture.takeIf { accepted == 0 }
        }
        return completed
    }

    override fun flush() {
        val capture = trace.active
        super.flush()
        blockedCapture = null
        capture?.let { trace.outputEvent(it, epoch, "audio-output-flush") }
    }

    override fun release() {
        val capture = trace.active
        super.release()
        capture?.let { trace.outputEvent(it, epoch, "audio-output-release") }
    }

    private fun event(kind: String) {
        trace.active?.let { trace.outputEvent(it, epoch, kind) }
    }
}
