package com.example.shelfplayer.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.example.shelfplayer.core.model.playback.FocusBehaviour
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeadsetOutputTrialsTest {
    @Test
    fun `early take-back comparisons vary only the PCM buffer`() {
        val trials = HeadsetOutputTrials()
        for ((name, size) in mapOf("EarlyTakeBack500" to 48_000, "EarlyTakeBack1000" to 96_000)) {
            val case = HeadsetOutputCase.valueOf(name)
            trials.select(case, "verified")
            assertEquals(true, case.takeBackOverride)
            assertEquals(false, case.blockCapture)
            assertEquals(size, pcmBytes(trials, minimum = 1_024))
        }
    }

    @Test
    fun `disconnect comparison changes only the PCM output buffer`() {
        val trials = HeadsetOutputTrials()
        for ((case, bytes) in mapOf(
            HeadsetOutputCase.Disconnect500 to 48_000,
            HeadsetOutputCase.Disconnect1000 to 96_000,
        )) {
            trials.select(case, "fixture")
            assertEquals(
                playbackAudioAttributes(FocusBehaviour.Duck),
                playbackAudioAttributes(FocusBehaviour.Duck, case),
            )
            assertEquals(bytes, pcmBytes(trials, minimum = 1_024))
        }
    }

    @Test
    fun `independent capture and output queue choices preserve focus and device minimum`() {
        val trials = HeadsetOutputTrials()
        for (case in HeadsetOutputCase.entries) {
            trials.select(case, "fixture")
            val defaultAttributes = playbackAudioAttributes(FocusBehaviour.Duck)
            val attributes = playbackAudioAttributes(FocusBehaviour.Duck, case)
            assertEquals(defaultAttributes.usage, attributes.usage)
            assertEquals(defaultAttributes.contentType, attributes.contentType)
            val capture = if (case.blockCapture) C.ALLOW_CAPTURE_BY_NONE else defaultAttributes.allowedCapturePolicy
            assertEquals(capture, attributes.allowedCapturePolicy)
            val durationBytes = case.targetPcmMs * 96
            assertEquals(durationBytes, pcmBytes(trials, minimum = 1_024))
            assertEquals(128_000, pcmBytes(trials, minimum = 128_000))
        }
    }

    @Test
    fun `connection cases have identical capture policy and half-second output queues`() {
        val trials = HeadsetOutputTrials()
        for (case in listOf(HeadsetOutputCase.ConnectTakeBackOff, HeadsetOutputCase.ConnectTakeBackOn)) {
            trials.select(case, "fixture")
            assertEquals(
                playbackAudioAttributes(FocusBehaviour.Duck),
                playbackAudioAttributes(FocusBehaviour.Duck, case),
            )
            assertEquals(48_000, pcmBytes(trials, minimum = 1_024))
        }
    }

    private fun pcmBytes(trials: HeadsetOutputTrials, minimum: Int) = trials.bufferProvider.getBufferSizeInBytes(
        minimum,
        C.ENCODING_PCM_16BIT,
        DefaultAudioSink.OUTPUT_MODE_PCM,
        2,
        48_000,
        C.RATE_UNSET_INT,
        1.0,
    )
}
