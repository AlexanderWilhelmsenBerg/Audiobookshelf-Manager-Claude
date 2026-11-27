package com.example.shelfplayer.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10

/** Amplitude across all channels in the trailing 20 ms (or less) of one accepted write. */
internal data class PcmWriteLevel(
    val status: String,
    val sampleCount: Int = 0,
    val rmsDbfs: Double? = null,
    val peakDbfs: Double? = null,
) {
    companion object {
        private const val WINDOWS_PER_SECOND = 50
        private const val POWER_DB_FACTOR = 10
        private const val AMPLITUDE_DB_FACTOR = 20

        fun measure(format: AudioOutputWriteTrace.Format, buffer: ByteBuffer, offset: Int, count: Int): PcmWriteLevel {
            val codec = PcmSampleFormat.from(format.encoding)
            val frame = format.pcmFrameBytes
            if (codec == null || frame == null) return PcmWriteLevel("unmeasurable-encoding")
            val validFormat = frame > 0 && frame % codec.width == 0 && format.sampleRate > 0
            val validSpan = count > 0 && offset >= 0 && offset <= buffer.limit() - count
            if (!validFormat || !validSpan || count % frame != 0) return PcmWriteLevel("incomplete-pcm")
            val frames = minOf(count / frame, (format.sampleRate / WINDOWS_PER_SECOND).coerceAtLeast(1))
            val end = offset + count
            return read(codec, buffer.duplicate().order(codec.order), end - frames * frame, end)
        }

        private fun read(codec: PcmSampleFormat, view: ByteBuffer, start: Int, end: Int): PcmWriteLevel {
            var peak = 0.0
            var sumSquares = 0.0
            for (i in start until end step codec.width) {
                val sample = codec.sample(view, i)
                if (!sample.isFinite()) return PcmWriteLevel("invalid-pcm")
                val amplitude = abs(sample).coerceAtMost(1.0)
                peak = maxOf(peak, amplitude)
                sumSquares += amplitude * amplitude
            }
            val samples = (end - start) / codec.width
            return if (peak == 0.0) {
                PcmWriteLevel("digital-silence", samples)
            } else {
                val power = sumSquares / samples
                val rmsDb = power.takeIf { it > 0.0 }?.let { POWER_DB_FACTOR * log10(it) }
                PcmWriteLevel("measured", samples, rmsDb, AMPLITUDE_DB_FACTOR * log10(peak))
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private enum class PcmSampleFormat(val encoding: Int, val width: Int, val order: ByteOrder = ByteOrder.LITTLE_ENDIAN) {
    U8(C.ENCODING_PCM_8BIT, 1),
    S16(C.ENCODING_PCM_16BIT, 2),
    S16BE(C.ENCODING_PCM_16BIT_BIG_ENDIAN, 2, ByteOrder.BIG_ENDIAN),
    S24(C.ENCODING_PCM_24BIT, 3),
    S24BE(C.ENCODING_PCM_24BIT_BIG_ENDIAN, 3, ByteOrder.BIG_ENDIAN),
    S32(C.ENCODING_PCM_32BIT, 4),
    S32BE(C.ENCODING_PCM_32BIT_BIG_ENDIAN, 4, ByteOrder.BIG_ENDIAN),
    F32(C.ENCODING_PCM_FLOAT, 4),
    F32BE(C.ENCODING_PCM_FLOAT_BIG_ENDIAN, 4, ByteOrder.BIG_ENDIAN),
    F64(C.ENCODING_PCM_DOUBLE, 8),
    F64BE(C.ENCODING_PCM_DOUBLE_BIG_ENDIAN, 8, ByteOrder.BIG_ENDIAN),
    ;

    fun sample(bytes: ByteBuffer, i: Int): Double = when (this) {
        U8 -> ((bytes[i].toInt() and BYTE_MASK) - U8_MIDPOINT) / U8_MIDPOINT

        S16, S16BE -> bytes.getShort(i) / S16_FULL_SCALE

        S24 -> ((bytes[i + 2].toInt() shl Short.SIZE_BITS) or (bytes.getShort(i).toInt() and SHORT_MASK)) /
            S24_FULL_SCALE

        S24BE -> ((bytes.getShort(i).toInt() shl Byte.SIZE_BITS) or (bytes[i + 2].toInt() and BYTE_MASK)) /
            S24_FULL_SCALE

        S32, S32BE -> bytes.getInt(i) / S32_FULL_SCALE

        F32, F32BE -> bytes.getFloat(i).toDouble()

        F64, F64BE -> bytes.getDouble(i)
    }

    companion object {
        private const val BYTE_MASK = 0xff
        private const val SHORT_MASK = 0xffff
        private const val U8_MIDPOINT = 128.0
        private const val S16_FULL_SCALE = 32768.0
        private const val S24_FULL_SCALE = 8388608.0
        private const val S32_FULL_SCALE = 2147483648.0

        fun from(encoding: Int): PcmSampleFormat? = entries.firstOrNull { it.encoding == encoding }
    }
}
