package com.example.shelfplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.shelfplayer.core.model.playback.PlaybackEqualizer
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/** Frame-preserving, zero look-ahead tone shaping. One processor belongs to one audio sink. */
@OptIn(UnstableApi::class)
internal class BookWaveEqualizer(private val settings: () -> PlaybackEqualizer) : BaseAudioProcessor() {
    private var selected = PlaybackEqualizer.Original
    private var start = Gain.Original
    private var target = Gain.Original
    private var fadeFrames = 1
    private var remainingFade = 0
    private var lowAlpha = 0.0
    private var wideAlpha = 0.0
    private var low = DoubleArray(0)
    private var wide = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: StreamMetadata) {
        if (inputAudioFormat == AudioFormat.NOT_SET || !isActive) return
        low = DoubleArray(inputAudioFormat.channelCount)
        wide = DoubleArray(inputAudioFormat.channelCount)
        lowAlpha = alpha(BASS_HZ)
        wideAlpha = alpha(TREBLE_HZ)
        fadeFrames = (inputAudioFormat.sampleRate / FRAMES_PER_SECOND_OF_FADE).coerceAtLeast(1)
        selected = settings()
        target = Gain.forSettings(selected)
        start = target
        remainingFade = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Media3 pumps the shared EMPTY_BUFFER when only downstream output needs draining.
        if (!inputBuffer.hasRemaining()) return

        val next = settings()
        if (selected != next) {
            val fraction = fadeFraction()
            start = Gain(
                direct = interpolate(start.direct, target.direct, fraction),
                low = interpolate(start.low, target.low, fraction),
                wide = interpolate(start.wide, target.wide, fraction),
            )
            target = Gain.forSettings(next)
            selected = next
            remainingFade = fadeFrames
        }

        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (remainingFade == 0 && target == Gain.Original) {
            output.put(inputBuffer)
        } else {
            shape(inputBuffer, output)
        }
        output.flip()
    }

    private fun shape(input: ByteBuffer, output: ByteBuffer) {
        while (input.hasRemaining()) {
            val fraction = fadeFraction()
            val directGain = interpolate(start.direct, target.direct, fraction)
            val lowGain = interpolate(start.low, target.low, fraction)
            val wideGain = interpolate(start.wide, target.wide, fraction)
            for (channel in low.indices) {
                val sample = input.short.toDouble()
                low[channel] += lowAlpha * (sample - low[channel])
                wide[channel] += wideAlpha * (sample - wide[channel])
                val shaped = sample * directGain + low[channel] * lowGain + wide[channel] * wideGain
                output.putShort(
                    shaped.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                )
            }
            if (remainingFade > 0) remainingFade--
        }
        if (remainingFade == 0 && target == Gain.Original) {
            low.fill(0.0)
            wide.fill(0.0)
        }
    }

    private fun fadeFraction(): Double = 1.0 - remainingFade.toDouble() / fadeFrames

    private fun alpha(frequency: Double): Double = 1 - exp(-TAU * frequency / inputAudioFormat.sampleRate)

    private fun interpolate(from: Double, to: Double, fraction: Double): Double = from + (to - from) * fraction

    private data class Gain(val direct: Double, val low: Double, val wide: Double) {
        companion object {
            val Original = Gain(direct = 1.0, low = 0.0, wide = 0.0)

            fun forSettings(settings: PlaybackEqualizer): Gain {
                val bounded = settings.bounded()
                val bass = dbToLinear(bounded.bass)
                val presence = dbToLinear(bounded.presence)
                val treble = dbToLinear(bounded.treble)
                // The three complementary bands sum to the input at unity. Each low-pass state
                // is a weighted average, so this peak bound also protects rapid transients.
                val low = bass - presence
                val wide = presence - treble
                val headroom = (treble + abs(low) + abs(wide)).coerceAtLeast(1.0)
                return Gain(treble / headroom, low / headroom, wide / headroom)
            }

            private fun dbToLinear(decibels: Int): Double = DECIBEL_BASE.pow(decibels / AMPLITUDE_DECIBEL_FACTOR)
        }
    }

    private companion object {
        const val BASS_HZ = 250.0
        const val TREBLE_HZ = 3_200.0
        const val FRAMES_PER_SECOND_OF_FADE = 50 // Change gain over 20 ms.
        const val DECIBEL_BASE = 10.0
        const val AMPLITUDE_DECIBEL_FACTOR = 20.0
        const val TAU = 2 * PI
    }
}
