package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.milliseconds

/** Debug controls select a case explicitly while paused. Process recreation starts in Normal. */
enum class HeadsetOutputCase(
    val blockCapture: Boolean = false,
    internal val pcmOverrideMs: Int? = null,
    internal val takeBackOverride: Boolean? = null,
) {
    Normal,
    CaptureBlocked(blockCapture = true),
    ShortBuffer(pcmOverrideMs = 250),
    Combined(blockCapture = true, pcmOverrideMs = 250),
    Disconnect500(pcmOverrideMs = 500, takeBackOverride = false),
    Disconnect1000(pcmOverrideMs = 1_000, takeBackOverride = false),
    ConnectTakeBackOff(pcmOverrideMs = 500, takeBackOverride = false),
    ConnectTakeBackOn(pcmOverrideMs = 500, takeBackOverride = true),
    EarlyTakeBack500(pcmOverrideMs = 500, takeBackOverride = true),
    EarlyTakeBack1000(pcmOverrideMs = 1_000, takeBackOverride = true),
    ;

    internal val earlyTakeBack get() = this == EarlyTakeBack500 || this == EarlyTakeBack1000
    internal val targetPcmMs get() = pcmOverrideMs ?: 500 // Media3's default PCM target.
}

enum class HeadsetTestResult {
    Applied,
    PauseFirst,
    PlayHeadsetFirst,
    CaptureUnsupported,
    TakeBackUnavailable,
    TransferListUnavailable,
}

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

    private val pcmProviders = HeadsetOutputCase.entries.mapNotNull {
        it.pcmOverrideMs
    }.distinct().associateWith { ms ->
        DefaultAudioTrackBufferSizeProvider.Builder()
            .setTargetPcmBufferDurationUs(ms.milliseconds.inWholeMicroseconds.toInt())
            .build()
    }
    val bufferProvider = DefaultAudioSink.AudioTrackBufferSizeProvider {
            min,
            encoding,
            mode,
            frame,
            rate,
            bitrate,
            speed,
        ->
        val override = active.value.pcmOverrideMs
        val provider = if (mode == DefaultAudioSink.OUTPUT_MODE_PCM && override != null) {
            pcmProviders.getValue(override)
        } else {
            DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
        }
        provider.getBufferSizeInBytes(min, encoding, mode, frame, rate, bitrate, speed)
    }
}
