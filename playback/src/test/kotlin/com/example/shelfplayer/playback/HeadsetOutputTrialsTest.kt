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
            val durationBytes = if (case.shortBuffer) 24_000 else 48_000
            assertEquals(durationBytes, pcmBytes(trials, minimum = 1_024))
            assertEquals(64_000, pcmBytes(trials, minimum = 64_000))
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
