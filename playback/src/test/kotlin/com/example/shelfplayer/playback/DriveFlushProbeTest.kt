package com.example.shelfplayer.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioSink
import com.example.shelfplayer.core.testing.TestAppClock
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DriveFlushProbeTest {
    private var nowNs = 10L
    private val probe = DriveFlushProbe { nowNs }
    private val player = probe.Player()
    private val manual = AudioOutputWriteTrace(TestAppClock())
    private val pcm = AudioOutputWriteTrace.Format(C.ENCODING_PCM_16BIT, 48_000, 2)

    @Test
    fun firstFlushCapturesCallerAndOnlyAcceptedAudioBeforeDelegation() {
        val drive = probe.open {}
        val sink = sink(player) {
            assertEquals(1, drive.snapshot().length(), "Capture the call before delegating the flush")
        }
        val bytes = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putShort(32767).putShort(0).putShort(16384).putShort(32767).flip()
        bytes.position(2).mark()
        val output = output(player, 4, pcm) {
            it.position(it.position() + 4)
            false
        }
        assertFalse(output.write(bytes, 1, 200))
        assertEquals(6, bytes.position())
        bytes.reset()
        assertEquals(2, bytes.position())
        nowNs = 75
        flushFromSeek(sink)
        flushFromSeek(sink)

        val event = drive.snapshot().getJSONObject(0)
        assertEquals("drive", event.getString("phase"))
        assertTrue(event.getJSONArray("callerStack").getString(0).endsWith("DriveFlushProbeTest.flushFromSeek"))
        assertEquals(4, event.getInt("trackEpoch"))
        val level = event.getJSONArray("precedingWrites").getJSONObject(0)
        assertEquals(65L, level.getLong("ageNs"))
        assertEquals(2, level.getInt("sampleCount"))
        assertEquals(-6.02, level.getDouble("peakDbfs"), 0.01)
        assertEquals(-9.03, level.getDouble("rmsDbfs"), 0.01)
        assertTrue(manual.entries().isEmpty())
    }

    @Test
    fun disconnectGetsItsOwnFirstFlushWithGenuineSilenceAndNoRetryData() {
        val drive = probe.open {}
        val sink = sink(player)
        flushFromSeek(sink)
        drive.departure()
        val fresh = ByteBuffer.allocateDirect(8)
        val output = output(player, 8, pcm, ::consume)
        assertTrue(output.write(fresh, 1, 0))
        nowNs = 100
        assertTrue(output.write(fresh, 1, 0))
        flushFromSeek(sink)
        flushFromSeek(sink)
        val events = drive.snapshot()
        assertEquals(2, events.length())
        assertEquals(0, events.getJSONObject(0).getJSONArray("precedingWrites").length())
        val event = events.getJSONObject(1)
        assertEquals("departure", event.getString("phase"))
        val levels = event.getJSONArray("precedingWrites")
        assertEquals(1, levels.length())
        assertEquals("digital-silence", levels.getJSONObject(0).getString("status"))
        assertTrue(levels.getJSONObject(0).isNull("rmsDbfs"))
        assertEquals(90L, levels.getJSONObject(0).getLong("ageNs"))
    }

    @Test
    fun replacedDrivePlayerAndTrackCannotSupplyStaleLevels() {
        val old = probe.open {}
        lateinit var current: DriveFlushRecording
        val bytes = ByteBuffer.allocateDirect(4)
        val output = output(player, 1, pcm) {
            probe.close(old)
            current = probe.open {}
            it.position(it.limit())
            true
        }
        output.write(bytes, 1, 0)
        val other = probe.Player()
        output(other, 3, pcm, ::consume).write(bytes.apply { rewind() }, 1, 0)
        val oldTrack = output(player, 4, pcm, ::consume)
        oldTrack.write(bytes.apply { rewind() }, 1, 0)
        output(player, 5, pcm) { false }
        oldTrack.write(bytes.apply { rewind() }, 1, 0)
        current.departure()
        flushFromSeek(sink(player))
        probe.close(current)
        flushFromSeek(sink(other))
        assertEquals(0, old.snapshot().length())
        assertEquals(1, current.snapshot().length())
        val event = current.snapshot().getJSONObject(0)
        assertEquals("departure", event.getString("phase"))
        assertEquals(5, event.getInt("trackEpoch"))
        assertEquals(0, event.getJSONArray("precedingWrites").length())
    }

    @Test
    fun onlyRecentAcceptedAudioCanCrossArrivalAndDisableClearsIt() {
        probe.setEnabled(true)
        val output = output(player, 1, pcm, ::consume)
        val bytes = ByteBuffer.allocateDirect(4)
        output.write(bytes, 1, 0)
        nowNs += 2_000_000_000
        output.write(bytes.apply { rewind() }, 1, 0)
        val drive = probe.open {}
        flushFromSeek(sink(player))
        val writes = drive.snapshot().getJSONObject(0).getJSONArray("precedingWrites")
        assertEquals(1, writes.length())
        assertEquals(nowNs, writes.getJSONObject(0).getLong("elapsedRealtimeNs"))
        probe.close(drive)
        output.write(bytes.apply { rewind() }, 1, 0)
        probe.setEnabled(false)
        assertEquals(null, player.recording)
        output.write(bytes.apply { rewind() }, 1, 0)
        probe.setEnabled(true)
        val next = probe.open {}
        flushFromSeek(sink(player))
        assertEquals(0, next.snapshot().getJSONObject(0).getJSONArray("precedingWrites").length())
    }

    private fun flushFromSeek(sink: AudioSink) = sink.flush()

    private fun consume(bytes: ByteBuffer): Boolean {
        bytes.position(bytes.limit())
        return true
    }

    private fun sink(owner: DriveFlushProbe.Player, onFlush: () -> Unit = {}): AudioSink {
        val type = AudioSink::class.java
        val delegate = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            check(method.name == "flush")
            onFlush()
            null
        } as AudioSink
        return DiagnosticAudioSink(delegate, manual, owner)
    }

    private fun output(
        owner: DriveFlushProbe.Player,
        epoch: Long,
        format: AudioOutputWriteTrace.Format,
        write: (ByteBuffer) -> Boolean,
    ): AudioOutput {
        val type = AudioOutput::class.java
        val delegate = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            check(method.name == "write")
            write(requireNotNull(args)[0] as ByteBuffer)
        } as AudioOutput
        return DiagnosticAudioOutput(delegate, manual, epoch, format, owner) {}
    }
}
