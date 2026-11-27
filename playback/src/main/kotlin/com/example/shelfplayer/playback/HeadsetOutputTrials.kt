package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds

/** Debug controls select a case explicitly while paused. Process recreation starts in Normal. */
enum class HeadsetOutputCase(val blockCapture: Boolean, val shortBuffer: Boolean) {
    Normal(false, false),
    CaptureBlocked(true, false),
    ShortBuffer(false, true),
    Combined(true, true),
    ;

    internal val targetPcmMs get() = if (shortBuffer) SHORT_BUFFER_MS else NORMAL_BUFFER_MS

    private companion object {
        const val SHORT_BUFFER_MS = 250
        const val NORMAL_BUFFER_MS = 500
    }
}

enum class HeadsetTestResult { Applied, PauseFirst, PlayHeadsetFirst, CaptureUnsupported }

@androidx.annotation.OptIn(UnstableApi::class)
internal class HeadsetOutputTrials {
    private val mutable = MutableStateFlow(HeadsetOutputCase.Normal)
    val active = mutable.asStateFlow()
    var endpoint: String? = null
        private set
    var applyToPausedPlayer: ((HeadsetOutputCase) -> HeadsetTestResult)? = null

    fun select(case: HeadsetOutputCase, headset: String?) {
        endpoint = headset.takeUnless { case == HeadsetOutputCase.Normal }
        mutable.value = case
    }

    private val small = DefaultAudioTrackBufferSizeProvider.Builder()
        .setTargetPcmBufferDurationUs(
            HeadsetOutputCase.ShortBuffer.targetPcmMs.milliseconds.inWholeMicroseconds.toInt(),
        )
        .build()
    val bufferProvider = DefaultAudioSink.AudioTrackBufferSizeProvider {
            min,
            encoding,
            mode,
            frame,
            rate,
            bitrate,
            speed,
        ->
        val provider = if (active.value.shortBuffer && mode == DefaultAudioSink.OUTPUT_MODE_PCM) {
            small
        } else {
            DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
        }
        provider.getBufferSizeInBytes(min, encoding, mode, frame, rate, bitrate, speed)
    }
}
