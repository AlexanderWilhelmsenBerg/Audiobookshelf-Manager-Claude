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
import kotlin.math.log10
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
    fun `a moderate bass boost lifts quiet fundamentals and leaves high frequencies level`() {
        settings = PlaybackEqualizer(bass = 3)
        for ((frequency, expectedGain) in listOf(125 to 2.5..2.7, 6_000 to -0.1..0.15)) {
            configure()
            val input = tone(frequency, 1)
            val gain = 20 * log10(peak(process(input)).toDouble() / peak(input))
            assertTrue(gain in expectedGain, "Measured gain at $frequency Hz was $gain dB")
        }
    }

    @Test
    fun `all three bands can boost quiet speech together`() {
        settings = PlaybackEqualizer(3, 3, 3)
        configure()
        val input = tone(900, 1)
        val gain = 20 * log10(peak(process(input)).toDouble() / peak(input))
        assertTrue(gain in 2.98..3.02, "Measured broadband gain was $gain dB")
    }

    @Test
    fun `loud peaks share protection across stereo and quiet audio recovers`() {
        settings = PlaybackEqualizer(6, 6, 6)
        configure(channels = 2)
        val loud = ShortArray(3_000) { if (it % 2 == 0) 30_000 else -10_000 }
        val limited = process(loud)
        assertTrue(limited[0] in 30_300..32_500)
        for (i in limited.indices step 2) {
            assertTrue(limited[i] in 30_300..32_500)
            assertTrue(abs(limited[i] + 3 * limited[i + 1]) <= 2)
        }
        val quiet = process(ShortArray(48_000) { 6_000 })
        assertTrue(quiet.first() < 9_000)
        assertTrue(quiet.last() in 11_960..11_980)
    }

    @Test
    fun `returning to original after a limited peak releases smoothly and restores full scale`() {
        settings = PlaybackEqualizer(6, 6, 6)
        configure()
        val hot = ShortArray(2_000) { Short.MIN_VALUE }
        val before = process(hot).last()
        settings = PlaybackEqualizer.Original
        val returning = process(ShortArray(48_000) { Short.MIN_VALUE })
        assertEquals(before, returning.first())
        assertEquals(Short.MIN_VALUE, returning.last())
        assertTrue((1 until returning.size).all { abs(returning[it] - returning[it - 1]) < 80 })
        assertContentEquals(hot, process(hot))
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
