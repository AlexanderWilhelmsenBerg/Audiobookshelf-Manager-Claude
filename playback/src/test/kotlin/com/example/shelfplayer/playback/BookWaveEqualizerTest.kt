package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.model.playback.PlaybackEqualizer
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class BookWaveEqualizerTest {
    private var settings = PlaybackEqualizer.Original
    private val processor = BookWaveEqualizer { settings }

    @Test
    fun `original preserves every PCM frame byte for byte`() {
        configure(channels = 2)
        val samples = shortArrayOf(Short.MIN_VALUE, 90, -15, Short.MAX_VALUE, 0, 0)
        assertContentEquals(samples, process(samples))
    }

    @Test
    fun `Media3 can pump the shared empty buffer before the first PCM frame`() {
        configure()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        val pcm = shortArrayOf(-6, 12, 0, -3)
        assertContentEquals(pcm, process(pcm))
    }

    @Test
    fun `the warm preset favors bass and keeps channels independent`() {
        settings = PlaybackEqualizer.Warm
        configure(channels = 2)
        val low = process(tone(80, 2))
        processor.flush(StreamMetadata.DEFAULT)
        val high = process(tone(6_000, 2))
        assertTrue(peak(low) > peak(high) * 1.3)
        assertTrue(low.indices.filter { it % 2 != 0 }.all { low[it] == 0.toShort() })
        assertTrue(high.indices.filter { it % 2 != 0 }.all { high[it] == 0.toShort() })
    }

    @Test
    fun `changing a preset ramps away and back to exact original without losing frames`() {
        configure()
        val constant = ShortArray(2_000) { 14_000 }
        process(constant)
        settings = PlaybackEqualizer.Clear
        val changed = process(constant)
        assertEquals(constant.size, changed.size)
        assertEquals(constant.first(), changed.first())
        assertTrue(abs(changed.last() - constant.last()) > 1_000)

        settings = PlaybackEqualizer.Original
        val returning = process(constant)
        assertEquals(changed.last(), returning.first())
        assertEquals(constant.last(), returning.last())
        assertContentEquals(constant, process(constant))
    }

    @Test
    fun `seeking clears filter history and switching PCM format reconfigures channels`() {
        settings = PlaybackEqualizer.Warm
        configure(channels = 2)
        process(tone(80, 2))
        configure(channels = 1, sampleRate = 24_000)
        val silence = ShortArray(64)
        assertContentEquals(silence, process(silence))
    }

    @Test
    fun `float output keeps its native audio path`() {
        assertEquals(AudioFormat.NOT_SET, processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)))
        processor.flush(StreamMetadata.DEFAULT)
        assertFalse(processor.isActive)
    }

    private fun configure(channels: Int = 1, sampleRate: Int = 48_000) {
        val format = AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush(StreamMetadata.DEFAULT)
    }

    private fun process(samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.output
        return ShortArray(output.remaining() / Short.SIZE_BYTES) { output.short }
    }

    private fun tone(hz: Int, channels: Int): ShortArray = ShortArray(4_800 * channels) { i ->
        if (i % channels == 0) (15_000 * sin(2 * PI * hz * (i / channels) / 48_000)).toInt().toShort() else 0
    }

    private fun peak(samples: ShortArray): Int = samples.drop(samples.size / 2).maxOf { abs(it.toInt()) }
}
