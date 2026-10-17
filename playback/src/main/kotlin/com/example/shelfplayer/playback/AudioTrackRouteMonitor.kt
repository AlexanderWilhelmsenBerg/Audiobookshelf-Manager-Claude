package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Experimental, opt-in actual AudioTrack routing evidence. The normal ExoPlayer builder is never
 * changed unless recording was enabled BEFORE the next PlaybackService creates its player.
 *
 * The delegate is Media3's default AudioTrackAudioOutputProvider. No custom audio rendering,
 * routing request, extra AudioTrack, audio attributes, focus or offload preference is created.
 * Failure to observe must not fail audio-output creation.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class AudioTrackRouteMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val lab: AudioRouteLab,
) {
    private var active: AudioTrack? = null
    private var listener: AudioRouting.OnRoutingChangedListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0

    fun providerIfRecording(): AudioOutputProvider? {
        if (!lab.state.value.recording) {
            lab.trackObserverStatus("Not installed (start recording before next player creation)")
            return null
        }
        lab.trackObserverStatus("Installed for this player; awaiting AudioTrack")
        return object : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                val output = super.getAudioOutput(config)
                if (output is AudioTrackAudioOutput) {
                    runCatching { observe(output.audioTrack) }
                        .onFailure { lab.trackObserverStatus("Observer unavailable; playback unaffected") }
                }
                return output
            }

            override fun release() {
                forget()
                super.release()
            }
        }
    }

    fun sample() {
        val track = active ?: return
        val generationAtStart = generation
        if (!lab.state.value.recording) return
        val status = runCatching {
            when {
                track.playState != AudioTrack.PLAYSTATE_PLAYING -> "paused-no-route-measurement"
                else -> {
                    val routes = when {
                        Build.VERSION.SDK_INT >= 36 -> track.routedDevices
                        else -> listOfNotNull(track.routedDevice)
                    }
                    if (routes.isEmpty()) "playing-route-unknown" else describe(routes)
                }
            }
        }.getOrElse { "route-query-unavailable" }
        if (generation == generationAtStart) lab.actualTrackEvidence(status)
    }

    private fun describe(routes: List<AudioDeviceInfo>): String {
        val values = routes.map { device ->
            val role = OutputDevices.outputOf(device.type, null)?.role?.name ?: "Other"
            "${lab.deviceAlias(device.id)}:$role"
        }
        return "playing-to=${values.joinToString("+")} measured=AudioTrack"
    }

    private fun observe(track: AudioTrack) {
        forget()
        active = track
        generation += 1
        val callback = AudioRouting.OnRoutingChangedListener {
            // Android's callback reflects the actual track and may run after release.
            sample()
        }
        listener = callback
        track.addOnRoutingChangedListener(callback, mainHandler)
        lab.trackObserverStatus("AudioTrack attached; live routed device evidence")
        sample()
    }

    private fun forget() {
        val previous = active
        val callback = listener
        active = null
        listener = null
        generation += 1
        if (previous != null && callback != null) {
            runCatching { previous.removeOnRoutingChangedListener(callback) }
        }
        lab.trackObserverStatus("AudioTrack released or replaced")
    }
}
