package com.example.shelfplayer.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertNull

@androidx.annotation.OptIn(UnstableApi::class)
class PcmWriteLevelTest {
    @Test
    fun measuresOnlyTrailingTwentyMillisecondsIncludingBothChannels() {
        val format = AudioOutputWriteTrace.Format(C.ENCODING_PCM_FLOAT, 1_000, 8)
        val pcm = ByteBuffer.allocateDirect(21 * 8).order(ByteOrder.LITTLE_ENDIAN)
        pcm.putFloat(1f).putFloat(1f)
        repeat(20) { pcm.putFloat(0f).putFloat(-0.5f) }
        pcm.flip()
        val result = PcmWriteLevel.measure(format, pcm, 0, pcm.remaining())
        assertEquals(40, result.sampleCount)
        assertEquals(-6.02, requireNotNull(result.peakDbfs), 0.01)
        assertEquals(-9.03, requireNotNull(result.rmsDbfs), 0.01)
        assertEquals(0, pcm.position())
    }

    @Test
    fun handlesSignedPackedAndUnsignedPcmIndependentOfInputBufferByteOrder() {
        val encodings = listOf(
            C.ENCODING_PCM_8BIT to byteArrayOf(64),
            C.ENCODING_PCM_16BIT_BIG_ENDIAN to byteArrayOf(-64, 0),
            C.ENCODING_PCM_24BIT to byteArrayOf(0, 0, -64),
            C.ENCODING_PCM_24BIT_BIG_ENDIAN to byteArrayOf(-64, 0, 0),
            C.ENCODING_PCM_32BIT to byteArrayOf(0, 0, 0, -64),
        )
        for ((encoding, bytes) in encodings) {
            val format = AudioOutputWriteTrace.Format(encoding, 48_000, bytes.size)
            val buffer = ByteBuffer.wrap(bytes)
            val result = PcmWriteLevel.measure(format, buffer, 0, bytes.size)
            assertEquals(-6.02, requireNotNull(result.rmsDbfs), 0.01)
            assertEquals(result.peakDbfs, result.rmsDbfs)
            assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
        }
    }

    @Test
    fun encodedIncompleteAndInvalidPcmAreNeverReportedAsSilence() {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN)
        fun level(encoding: Int, frame: Int?, count: Int) =
            PcmWriteLevel.measure(AudioOutputWriteTrace.Format(encoding, 48_000, frame), bytes, 0, count)
        val results = listOf(
            level(C.ENCODING_AC3, null, 4) to "unmeasurable-encoding",
            level(C.ENCODING_PCM_16BIT, 4, 2) to "incomplete-pcm",
            level(C.ENCODING_PCM_FLOAT, 4, 4) to "invalid-pcm",
        )
        for ((result, status) in results) {
            assertEquals(status, result.status)
            assertEquals(0, result.sampleCount)
            assertNull(result.peakDbfs)
        }
    }
}
