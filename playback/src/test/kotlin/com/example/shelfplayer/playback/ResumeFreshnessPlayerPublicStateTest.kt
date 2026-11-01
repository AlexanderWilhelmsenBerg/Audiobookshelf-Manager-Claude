package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Public Media3 state must reflect the delegate while a guarded Play is still being decided. */
@RunWith(RobolectricTestRunner::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ResumeFreshnessPlayerPublicStateTest {

    @Test
    fun `pending denied Play stays paused in forwarding state`() {
        val delegate = player(withMediaItem = true)
        val preparation = SettableFuture.create<Boolean>()
        val forwarding = forwarding(delegate, preparation)

        forwarding.play()

        assertFalse(delegate.playWhenReady)
        assertFalse(forwarding.playWhenReady)

        preparation.set(false)
        shadowOf(delegate.applicationLooper).idle()

        assertFalse(delegate.playWhenReady)
        assertFalse(forwarding.playWhenReady)
        delegate.release()
    }

    @Test
    fun `pending accepted Play follows delegate only after raw Play is issued`() {
        val delegate = player(withMediaItem = true)
        val preparation = SettableFuture.create<Boolean>()
        val forwarding = forwarding(delegate, preparation)

        forwarding.play()

        assertFalse(delegate.playWhenReady)
        assertFalse(forwarding.playWhenReady)

        delegate.play()
        preparation.set(true)
        shadowOf(delegate.applicationLooper).idle()

        assertTrue(delegate.playWhenReady)
        assertTrue(forwarding.playWhenReady)
        delegate.release()
    }

    @Test
    fun `newer Pause stays authoritative when pending Play completes`() {
        val delegate = player(withMediaItem = true)
        val preparation = SettableFuture.create<Boolean>()
        val forwarding = forwarding(delegate, preparation)

        forwarding.play()
        forwarding.pause()
        preparation.set(true)
        shadowOf(delegate.applicationLooper).idle()

        assertFalse(delegate.playWhenReady)
        assertFalse(forwarding.playWhenReady)
        delegate.release()
    }

    @Test
    fun `empty session Play still uses Media3 resumption path`() {
        val delegate = player(withMediaItem = false)
        var guardedPreparations = 0
        val forwarding = forwarding(delegate, SettableFuture.create<Boolean>()) {
            guardedPreparations += 1
        }

        forwarding.play()
        shadowOf(delegate.applicationLooper).idle()

        assertEquals(0, guardedPreparations)
        assertTrue(delegate.playWhenReady)
        assertTrue(forwarding.playWhenReady)
        delegate.release()
    }

    @Test
    fun `public default seek remains available while Play state is delegate-derived`() {
        val delegate = player(withMediaItem = true)
        val preparation = SettableFuture.create<Boolean>()
        var guardedPreparations = 0
        val forwarding = forwarding(delegate, preparation) { guardedPreparations += 1 }

        forwarding.seekToDefaultPosition()
        shadowOf(delegate.applicationLooper).idle()

        assertEquals(0, guardedPreparations, "seek must not enter guarded Play preparation")
        assertTrue(forwarding.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION))
        assertFalse(forwarding.playWhenReady)
        delegate.release()
    }

    private fun player(withMediaItem: Boolean): ExoPlayer =
        ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build().apply {
            if (withMediaItem) setMediaItem(MediaItem.fromUri("file:///resume-freshness-test"))
        }

    private fun forwarding(delegate: ExoPlayer, preparation: SettableFuture<Boolean>, onPrepare: () -> Unit = {}) =
        ResumeFreshnessPlayer(
            delegate = delegate,
            preparePlay = {
                onPrepare()
                preparation
            },
            consumeFreshStart = { false },
            invalidate = { true },
            onPlayWhenReadyRequest = { true },
            skipIntervals = { com.example.shelfplayer.core.model.playback.SkipIntervals(0.seconds, 0.seconds) },
            prepareCurrentOutput = { Futures.immediateVoidFuture() },
            movementEffects = unprotectedMovementEffects(delegate),
        )
}
