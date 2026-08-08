package com.example.shelfplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Builds the player, so [PlaybackService] does not have to know how.
 *
 * A seam rather than construction inline: the audio attributes and the data source are the two
 * decisions in this module with a requirement attached to each, and this lets both be asserted without
 * starting a `Service`.
 */
@OptIn(UnstableApi::class)
interface PlayerFactory {
    fun create(): ExoPlayer

    /** How the notification and lock screen load cover art. See [DefaultPlayerFactory]. */
    fun bitmapLoader(): BitmapLoader
}

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-002 — the player, with the settings the requirements name.
 *
 * @param dataSourceFactory the app's **authenticated** HTTP stack. The server sends credential-free
 *   track URLs (PRODUCT_SPEC 14.5), so the `Authorization` header is what fetches them, and it comes
 *   from the same OkHttp client every other request uses — same connection pool, same host, same
 *   redacting log interceptor.
 */
@OptIn(UnstableApi::class)
internal class DefaultPlayerFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:MediaDataSource private val dataSourceFactory: DataSource.Factory,
    private val artwork: BitmapLoader,
) : PlayerFactory {

    override fun create(): ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        // The `true` is `handleAudioFocus`: Media3 requests and releases focus itself.
        .setAudioAttributes(SPEECH_OVER_MEDIA, true)
        // PRODUCT_SPEC PLAY-002 — headphones out pauses, and audio never moves to the phone speaker.
        .setHandleAudioBecomingNoisy(true)
        // The stream keeps arriving with the screen off. `WAKE_MODE_NETWORK` also holds a WifiLock,
        // which is what stops a doze-happy device dropping the connection mid-chapter.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    override fun bitmapLoader(): BitmapLoader = artwork

    private companion object {
        /**
         * PLAY-002's "default to pause on transient loss", expressed as what the audio *is*.
         *
         * Media3's focus manager ducks a transient-can-duck loss for music and pauses it for speech.
         * Declaring speech is therefore the requirement rather than a hint: an audiobook ducked under a
         * navigation prompt is an audiobook the listener has to rewind.
         */
        val SPEECH_OVER_MEDIA: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
    }
}
