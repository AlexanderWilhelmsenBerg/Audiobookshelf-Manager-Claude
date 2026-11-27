package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
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
    private val playerProbe: DriveFlushProbe.Player,
    private val observeClock: (Long?) -> Unit = {},
    private val samplePreference: (String) -> Unit,
) : ForwardingAudioOutput(delegate) {
    private var blockedCapture: AudioOutputWriteTrace.Capture? = null
    private val position = trace.ClockSampler(AudioOutputWriteTrace.ClockStage.AudioOutput, epoch)

    init {
        playerProbe.trackEpoch = epoch
        event("audio-output-open")
    }

    override fun setPreferredDevice(device: AudioDeviceInfo?) {
        samplePreference("before-preference-write")
        super.setPreferredDevice(device)
        samplePreference("after-preference-write")
    }

    override fun getPositionUs(): Long {
        val capture = trace.active
        val positionUs = super.getPositionUs()
        observeClock(positionUs)
        capture?.let { position.sample(it, positionUs) }
        return positionUs
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val capture = trace.active
        val drive = playerProbe.recording
        if (capture == null && drive == null) return super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        val offset = buffer.position()
        val completed = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        val accepted = buffer.position() - offset
        drive?.accepted(playerProbe, epoch, format, buffer, offset, accepted)
        // A retry loop with no accepted data must not hide the preceding progress in the rolling buffer.
        if (capture != null && (accepted > 0 || blockedCapture !== capture)) {
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
        observeClock(null)
        val capture = trace.active
        super.flush()
        blockedCapture = null
        capture?.let { trace.outputEvent(it, epoch, "audio-output-flush") }
    }

    override fun release() {
        observeClock(null)
        val capture = trace.active
        super.release()
        capture?.let { trace.outputEvent(it, epoch, "audio-output-release") }
    }

    private fun event(kind: String) {
        trace.active?.let { trace.outputEvent(it, epoch, kind) }
    }
}
