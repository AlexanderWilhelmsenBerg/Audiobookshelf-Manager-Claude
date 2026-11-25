package com.example.shelfplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import java.io.File
import org.videolan.libvlc.MediaPlayer as VlcPlayer

internal val labAudioAttributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

internal interface CarLabEngine {
    val playing: Boolean
    val positionMs: Long
    val routed: AudioDeviceInfo? get() = null
    fun play()
    fun pause()
    fun close()
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class LabExo(context: Context, file: File, failure: () -> Unit) : CarLabEngine {
    private val player = ExoPlayer.Builder(context).build()

    init {
        player.setAudioAttributes(
            playbackAudioAttributes(com.example.shelfplayer.core.model.playback.FocusBehaviour.Default),
            false,
        )
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = failure()
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
    }

    override val playing get() = player.isPlaying
    override val positionMs get() = player.currentPosition
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun close() = player.release()
}

internal class LabMediaPlayer(file: File, preferred: AudioDeviceInfo?, failure: () -> Unit) : CarLabEngine {
    private val player = MediaPlayer()

    init {
        var prepared = false
        try {
            player.setAudioAttributes(labAudioAttributes)
            player.setDataSource(file.path)
            player.prepare()
            player.isLooping = true
            if (preferred != null) check(player.setPreferredDevice(preferred))
            player.setOnErrorListener { _, _, _ ->
                failure()
                true
            }
            prepared = true
        } finally {
            if (!prepared) player.release()
        }
    }

    override val routed: AudioDeviceInfo? get() = player.routedDevice
    override val playing get() = player.isPlaying
    override val positionMs get() = player.currentPosition.toLong()
    override fun play() = player.start()
    override fun pause() = player.pause()
    override fun close() = player.release()
}

internal class LabVlc(context: Context, file: File, failure: () -> Unit) : CarLabEngine {
    private val library = LibVLC(context, listOf("--no-video"))
    private val player = VlcPlayer(library)
    private val media = Media(library, Uri.fromFile(file))

    init {
        media.addOption(":input-repeat=65535")
        player.media = media
        player.setEventListener { if (it.type == VlcPlayer.Event.EncounteredError) failure() }
    }

    override val playing get() = player.isPlaying
    override val positionMs get() = player.time
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun close() {
        player.setEventListener(null)
        player.stop()
        player.release()
        media.release()
        library.release()
    }
}
