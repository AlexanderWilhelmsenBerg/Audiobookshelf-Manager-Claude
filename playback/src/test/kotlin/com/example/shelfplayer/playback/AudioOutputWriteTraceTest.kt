package com.example.shelfplayer.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@androidx.annotation.OptIn(UnstableApi::class)
class AudioOutputWriteTraceTest {
    @Test
    fun `partial writes preserve the result and count only accepted frames`() {
        val clock = TestAppClock()
        val trace = AudioOutputWriteTrace(clock)
        val output = output(trace, pcm) { buffer ->
            buffer.position(buffer.position() + 4)
            !buffer.hasRemaining()
        }
        val buffer = ByteBuffer.allocateDirect(8)
        trace.start()

        assertFalse(output.write(buffer, 1, 100))
        clock.advanceBy(1.seconds)
        assertTrue(output.write(buffer, 1, 100))
        output.flush()
        trace.mark("RouteSafetyPause", 7)
        output.release()

        val rows = trace.entries()
        val writes = rows.filter { " audio-write " in it }
        assertEquals(2, writes.size)
        assertTrue(writes[0].contains("ptsUs=100 offsetBytes=0 acceptedBytes=4 acceptedFrames=1 complete=false"))
        assertTrue(writes[1].contains("ptsUs=100 offsetBytes=4 acceptedBytes=4 acceptedFrames=1 complete=true"))
        assertTrue(rows.any { " audio-output-flush " in it && "trackEpoch=7" in it })
        assertTrue(rows.any { " audio-output-release " in it && "trackEpoch=7" in it })
    }

    @Test
    fun `retains only a bounded interval around a boundary and distinguishes encoded bytes`() {
        val clock = TestAppClock()
        val trace = AudioOutputWriteTrace(clock)
        val output = output(trace, AudioOutputWriteTrace.Format(C.ENCODING_AC3, 48_000, null), ::consume)
        trace.start()
        output.write(ByteBuffer.allocateDirect(4), 3, 10)
        clock.advanceBy(5.seconds)
        output.write(ByteBuffer.allocateDirect(4), 3, 20)
        clock.advanceBy(2.seconds)
        trace.mark("RouteSafetyPause", 7)
        clock.advanceBy(1.seconds)
        output.write(ByteBuffer.allocateDirect(4), 3, 30)
        clock.advanceBy(3.seconds)
        output.write(ByteBuffer.allocateDirect(4), 3, 40)
        trace.stop()

        val writes = trace.entries().filter { " audio-write " in it }
        assertEquals(2, writes.size)
        assertTrue(writes[0].contains("ptsUs=20 offsetBytes=0 acceptedBytes=4 acceptedFrames=null"))
        assertTrue(writes[1].contains("ptsUs=30"))
    }

    @Test
    fun `disabled capture and writes in flight during clear leave no stale evidence`() {
        val trace = AudioOutputWriteTrace(TestAppClock())
        output(trace, pcm, ::consume).write(ByteBuffer.allocateDirect(4), 1, 10)
        trace.start()
        val old = output(trace, pcm) { buffer ->
            trace.clear(keepRecording = true)
            consume(buffer)
        }
        assertTrue(old.write(ByteBuffer.allocateDirect(4), 1, 20))
        val current = output(trace, pcm, ::consume)
        current.write(ByteBuffer.allocateDirect(4), 1, 30)
        trace.mark("Skipping", null)
        val writes = trace.entries().filter { " audio-write " in it }
        assertEquals(1, writes.size)
        assertTrue(writes.single().contains("ptsUs=30"))
        trace.clear(keepRecording = false)
        assertTrue(trace.entries().isEmpty())
    }

    @Test
    fun `position events are opt-in and clocks are sampled on the same timeline`() {
        val clock = TestAppClock()
        val trace = AudioOutputWriteTrace(clock)
        val output = trace.ClockSampler(AudioOutputWriteTrace.ClockStage.AudioOutput, 7)
        val sink = trace.ClockSampler(AudioOutputWriteTrace.ClockStage.Media3Sink)
        trace.positionWrite(PlaybackPositionWrite.SessionSeek, 10)
        trace.start()
        val capture = requireNotNull(trace.active)
        output.sample(capture, 100_000)
        sink.sample(capture, 3_500_000)
        clock.advanceBy(50.milliseconds)
        output.sample(capture, 150_000)
        trace.positionWrite(PlaybackPositionWrite.RouteCheckpoint, 3_400)
        clock.advanceBy(50.milliseconds)
        sink.sample(capture, Long.MIN_VALUE)
        trace.mark("ProjectionDeparture", 7)
        output.sample(capture, 200_000)
        trace.stop()

        val events = trace.entries().filter { " audio-clock " in it || " position-action " in it }
        assertEquals(
            listOf(
                "trackEpoch=7 stage=AudioOutput positionUs=100000",
                "trackEpoch=null stage=Media3Sink positionUs=3500000",
                "trackEpoch=null stage=RouteCheckpoint targetMs=3400",
                "trackEpoch=null stage=Media3Sink positionUs=null",
                "trackEpoch=7 stage=AudioOutput positionUs=200000",
            ),
            events.map { it.substring(it.indexOf("trackEpoch=")) },
        )
        trace.clear(keepRecording = true)
        output.sample(capture, 900_000)
        output.sample(requireNotNull(trace.active), 200_000)
        trace.mark("Skipping", 7)
        assertTrue(trace.entries().any { "positionUs=200000" in it })
        assertTrue(trace.entries().none { "positionUs=900000" in it })
    }

    private fun output(
        trace: AudioOutputWriteTrace,
        format: AudioOutputWriteTrace.Format,
        write: (ByteBuffer) -> Boolean,
    ): DiagnosticAudioOutput {
        val type = AudioOutput::class.java
        val delegate = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            when (method.name) {
                "write" -> write(requireNotNull(args)[0] as ByteBuffer)
                "flush", "release" -> null
                else -> error("Unexpected AudioOutput call: ${method.name}")
            }
        } as AudioOutput
        return DiagnosticAudioOutput(delegate, trace, 7, format)
    }

    private fun consume(buffer: ByteBuffer): Boolean {
        buffer.position(buffer.limit())
        return true
    }

    private val pcm = AudioOutputWriteTrace.Format(C.ENCODING_PCM_16BIT, 48_000, 4)
}
