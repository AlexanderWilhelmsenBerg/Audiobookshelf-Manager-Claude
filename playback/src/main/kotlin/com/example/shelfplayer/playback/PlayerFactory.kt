package com.example.shelfplayer.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.common.log.Logger
import com.example.shelfplayer.core.model.playback.BufferPreset
import com.example.shelfplayer.core.model.playback.FocusBehaviour
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
    /**
     * @param buffer PRODUCT_SPEC PLAY-006 — the preset in force when this player is built. A change takes
     *   effect on the next player, which is what "applied on the next player preparation" means: recreating a
     *   live player mid-book is the one thing product priority 1 forbids doing for a setting.
     */
    fun create(buffer: BufferPreset = BufferPreset.Default, focus: FocusBehaviour = FocusBehaviour.Default): ExoPlayer

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
    private val logger: Logger,
) : PlayerFactory {

    override fun create(buffer: BufferPreset, focus: FocusBehaviour): ExoPlayer = ExoPlayer.Builder(context)
        // ADR-0016 — a book is one timeline window, so its item becomes a concatenated source rather
        // than a playlist. Everything that is not one of our book items falls through to the default.
        .setMediaSourceFactory(BookMediaSourceFactory(dataSourceFactory, logger))
        // PRODUCT_SPEC PLAY-006 — Automatic leaves Media3's own load control alone, which is a different
        // thing from any particular pair of numbers and is why the enum carries no override for it.
        .apply { loadControlFor(buffer)?.let(::setLoadControl) }
        // The `true` is `handleAudioFocus`: Media3 requests and releases focus itself.
        .setAudioAttributes(attributesFor(focus), true)
        // PRODUCT_SPEC PLAY-002 — headphones out pauses, and audio never moves to the phone speaker.
        .setHandleAudioBecomingNoisy(true)
        // The stream keeps arriving with the screen off. `WAKE_MODE_NETWORK` also holds a WifiLock,
        // which is what stops a doze-happy device dropping the connection mid-chapter.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    override fun bitmapLoader(): BitmapLoader = artwork

    /**
     * PRODUCT_SPEC PLAY-006 — the preset as a `DefaultLoadControl`, or `null` for Automatic.
     *
     * An invalid preset also returns `null` rather than throwing. `DefaultLoadControl.Builder` asserts these
     * relationships itself and would crash the service on the next play; falling back to Media3's defaults
     * costs the user their preference and keeps their book playing, which is the right way round
     * (product priority 1). The presets are checked in a unit test so this path is unreachable in practice.
     */
    private fun loadControlFor(buffer: BufferPreset): DefaultLoadControl? {
        if (buffer == BufferPreset.Automatic || !buffer.isValid) return null
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffer.minimumBuffer.inWholeMilliseconds.toInt(),
                buffer.maximumBuffer.inWholeMilliseconds.toInt(),
                buffer.bufferForPlayback.inWholeMilliseconds.toInt(),
                buffer.bufferForRebuffer.inWholeMilliseconds.toInt(),
            )
            .build()
    }

    /**
     * PRODUCT_SPEC PLAY-002 — the setting, expressed as what the audio *is*.
     *
     * Media3's focus manager ducks a `TRANSIENT_CAN_DUCK` loss for music and pauses it for speech. So the
     * choice is made by declaring a content type rather than by intercepting the callback: keeping
     * `handleAudioFocus` means the platform still handles every other case — the phone call, the permanent
     * loss, the alarm — and this app does not reimplement audio focus to change one branch of it.
     *
     * The default is speech, and the default is pause, because an audiobook ducked under a navigation
     * prompt is an audiobook the listener has to rewind.
     */
    private fun attributesFor(focus: FocusBehaviour): AudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(
            when (focus) {
                FocusBehaviour.Pause -> C.AUDIO_CONTENT_TYPE_SPEECH
                FocusBehaviour.Duck -> C.AUDIO_CONTENT_TYPE_MUSIC
            },
        )
        .build()
}
