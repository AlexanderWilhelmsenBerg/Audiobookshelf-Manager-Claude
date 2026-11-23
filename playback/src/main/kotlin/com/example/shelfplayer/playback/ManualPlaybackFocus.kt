package com.example.shelfplayer.playback

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.example.shelfplayer.core.common.log.LogCategory
import com.example.shelfplayer.core.common.log.LogField
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.common.log.info

/** The opt-in focus owner. Focus callbacks are diagnostic and never issue transport commands. */
internal class ManualPlaybackFocus(private val audio: AudioManager?, private val logger: Logger) {
    private var request: AudioFocusRequest? = null
    private var generation = 0L

    fun play(explicit: Boolean): Boolean {
        if (!explicit) return request != null
        val manager = audio ?: return false
        release()
        val expectedGeneration = generation
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (generation == expectedGeneration && request != null) {
                    logger.info(
                        LogCategory.Playback,
                        "Manual audio focus changed",
                        LogField.Public("focusEvidence", "manual-android-callback"),
                        LogField.Public("androidFocusChange", change),
                    )
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        request = next
        val result = manager.requestAudioFocus(next)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        release()
        logger.info(LogCategory.Playback, "Manual audio focus request declined", LogField.Public("result", result))
        return false
    }

    fun release() {
        generation += 1
        val retired = request ?: return
        request = null
        audio?.abandonAudioFocusRequest(retired)
    }
}
