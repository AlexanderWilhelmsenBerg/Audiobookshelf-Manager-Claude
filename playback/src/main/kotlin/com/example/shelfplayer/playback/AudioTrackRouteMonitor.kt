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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generation tokens reject delayed callbacks from a released or replaced AudioTrack. They do not
 * identify a physical Bluetooth device or tell Android where to send audio.
 */
internal class AudioTrackObserverEpoch {
    private val generation = AtomicLong(0)

    fun next(): Long = generation.incrementAndGet()

    fun nextSnapshot(): Long = generation.get()

    fun isCurrent(value: Long): Boolean = generation.get() == value
}

/**
 * Passive, opt-in route observations. The ordinary non-recording ExoPlayer builder remains unchanged.
 * Media3's standard provider creates every output; this class never creates an extra AudioTrack,
 * chooses a route, requests focus or invokes playback transport.
 */
@OptIn(UnstableApi::class)
@Singleton
internal class AudioTrackRouteMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val lab: AudioRouteLab,
) {
    @Volatile
    private var active: AudioTrack? = null
    private var listener: AudioRouting.OnRoutingChangedListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val epoch = AudioTrackObserverEpoch()
    private var lastEvidence: String? = null
    private var lastPlaying = false

    fun providerIfRecording(): AudioOutputProvider? {
        // Called exactly when DefaultPlayerFactory constructs its player, not when the UI opens.
        if (!lab.state.value.recording) {
            lab.trackObserverStatus("Not installed (recording was off when player was created)")
            return null
        }
        lab.trackLifecycle("player-create recording=true")
        val delegate = runCatching { AudioTrackAudioOutputProvider.Builder(context).build() }
            .getOrElse {
                lab.trackLifecycle("provider-fallback")
                lab.trackObserverStatus("Provider initialization failed; default Media3 output retained")
                return null
            }
        lab.trackLifecycle("provider-installed")
        lab.trackObserverStatus("Provider installed; awaiting Media3 AudioTrack creation")
        return object : ForwardingAudioOutputProvider(delegate) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                // Deliberately do not catch Media3's own output-creation errors or alter its return value.
                val output = super.getAudioOutput(config)
                if (output is AudioTrackAudioOutput) {
                    runCatching { observe(output.audioTrack) }
                        .onFailure {
                            forget()
                            lab.trackLifecycle("audio-track-attach-failed")
                            lab.trackObserverStatus("Routing listener could not attach; audio remains Media3-owned")
                        }
                } else {
                    // A new non-track output invalidates any previous track measurement.
                    forget()
                    lab.trackLifecycle("provider-output-unobserved")
                    lab.trackObserverStatus("Media3 output is not an observable AudioTrack")
                }
                return output
            }

            override fun release() {
                forget()
                lab.trackLifecycle("provider-released")
                super.release()
            }
        }
    }

    /** Called by PlaybackService on genuine Media3 playing-state transitions. */
    fun sample() {
        val track = active ?: return
        val token = epoch.nextSnapshot()
        if (!lab.state.value.recording) return
        val result = runCatching {
            when {
                track.state == AudioTrack.STATE_UNINITIALIZED ->
                    "released-or-uninitialized-no-route-measurement"

                track.playState == AudioTrack.PLAYSTATE_PAUSED -> "paused-no-route-measurement"
                track.playState == AudioTrack.PLAYSTATE_STOPPED -> "stopped-no-route-measurement"
                track.playState != AudioTrack.PLAYSTATE_PLAYING -> "playstate-unknown-no-route-measurement"
                else -> {
                    val routes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                        track.routedDevices
                    } else {
                        listOfNotNull(track.routedDevice)
                    }
                    if (routes.isEmpty()) "playing-route-unknown" else describe(routes)
                }
            }
        }.getOrElse { "route-query-unavailable" }
        // A query can race with another track's release. Never publish it against the successor.
        if (!epoch.isCurrent(token) || active !== track) return
        val playing = result == "playing-route-unknown" || result.startsWith("playing-to=")
        if (playing && !lastPlaying) lab.trackLifecycle("audio-track-playing")
        lastPlaying = playing
        if (lastEvidence != result) {
            lastEvidence = result
            lab.actualTrackEvidence(result)
        }
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
        val token = epoch.next()
        active = track
        val callback = AudioRouting.OnRoutingChangedListener {
            if (epoch.isCurrent(token) && active === track && lab.state.value.recording) {
                lab.trackLifecycle("audio-track-route-changed")
                sample()
            }
        }
        listener = callback
        track.addOnRoutingChangedListener(callback, mainHandler)
        lab.trackLifecycle("audio-track-attached")
        lab.trackObserverStatus("AudioTrack attached; awaiting a playing route sample")
        sample()
    }

    private fun forget() {
        val previous = active
        val callback = listener
        active = null
        listener = null
        epoch.next()
        lastEvidence = null
        lastPlaying = false
        if (previous != null && callback != null) {
            runCatching { previous.removeOnRoutingChangedListener(callback) }
        }
        if (previous != null) {
            lab.trackLifecycle("audio-track-released-or-replaced")
            lab.trackObserverStatus("AudioTrack released or replaced; no current route measurement")
        }
    }
}
