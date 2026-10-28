package com.example.shelfplayer.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.shelfplayer.core.testing.TestAppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
class HeadsetRouteGuardReentrantTest {
    @Test
    fun `cancellation from checkpoint capture cannot acquire a later mute claim`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val effects = PlaybackVolumeEffects(TestAppClock())
        val media = MutableMediaPlayer()
        val guard = guard(backgroundScope, dispatcher, effects, media.player)
        effects.attach(media.player)
        guard.attachPlayer(media.player)
        guard.protect(OUTPUT)
        guard.captureCheckpoint = { probe ->
            guard.cancelProbe(probe)
            checkpoint()
        }

        assertFalse(guard.beginProbe(OUTPUT, media.player, EPOCH))
        advanceTimeBy(5_000)
        runCurrent()

        assertFalse(effects.isRouteMuted(media.player))
        assertEquals(1f, media.volume)
        assertEquals(0, media.pauseCalls)
    }

    @Test
    fun `successor probe reserved during capture is not erased or double muted by its predecessor`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val effects = PlaybackVolumeEffects(TestAppClock())
        val media = MutableMediaPlayer()
        val guard = guard(backgroundScope, dispatcher, effects, media.player)
        effects.attach(media.player)
        guard.attachPlayer(media.player)
        guard.protect(OUTPUT)
        var nested = false
        var successorStarted = false
        guard.captureCheckpoint = { probe ->
            if (!nested) {
                nested = true
                guard.cancelProbe(probe)
                successorStarted = guard.beginProbe(OUTPUT, media.player, EPOCH + 1)
            }
            checkpoint()
        }

        assertFalse(guard.beginProbe(OUTPUT, media.player, EPOCH))
        assertTrue(successorStarted)
        assertTrue(effects.isRouteMuted(media.player))
        guard.cancelProbe()

        assertFalse(effects.isRouteMuted(media.player))
        assertEquals(1f, media.volume)
        assertEquals(0, media.pauseCalls)
    }

    @Test
    fun `synchronous cancellation during mute acquisition retires the provisional probe`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val clock = TestAppClock()
        val effects = PlaybackVolumeEffects(clock)
        val media = MutableMediaPlayer()
        val guard = guard(backgroundScope, dispatcher, effects, media.player)
        effects.attach(media.player)
        guard.attachPlayer(media.player)
        guard.protect(OUTPUT)
        effects.addMuteListener { change ->
            if (change.muted) guard.cancelProbe()
        }

        val started = guard.beginProbe(OUTPUT, media.player, EPOCH)
        advanceTimeBy(5_000)
        runCurrent()

        assertFalse(started, "synchronous cancellation owns the start/cleanup boundary")
        assertFalse(effects.isRouteMuted(media.player))
        assertEquals(1f, media.volume)
        assertEquals(0, media.pauseCalls, "a cancelled provisional probe has no later timeout pause")
    }

    @Test
    fun `nested begin during mute notification cannot overwrite or leak the outer claim`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val effects = PlaybackVolumeEffects(TestAppClock())
        val media = MutableMediaPlayer()
        val guard = guard(backgroundScope, dispatcher, effects, media.player)
        effects.attach(media.player)
        guard.attachPlayer(media.player)
        guard.protect(OUTPUT)
        var nestedStarted: Boolean? = null
        effects.addMuteListener { change ->
            if (change.muted && nestedStarted == null) {
                nestedStarted = guard.beginProbe(OUTPUT, media.player, EPOCH + 1)
            }
        }

        val outerStarted = guard.beginProbe(OUTPUT, media.player, EPOCH)
        guard.cancelProbe()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(outerStarted)
        assertEquals(false, nestedStarted, "the guard reserves its existing probe slot before notifying listeners")
        assertFalse(effects.isRouteMuted(media.player), "cancelling the outer probe releases every owned mute claim")
        assertEquals(1f, media.volume)
        assertEquals(0, media.pauseCalls)
    }

    private fun guard(
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        effects: PlaybackVolumeEffects,
        player: ExoPlayer,
    ) = HeadsetRouteGuard(
        scope = scope,
        playerDispatcher = dispatcher,
        volumeEffects = effects,
        isCurrentProbe = { _, current, endpoint -> current === player && endpoint == OUTPUT },
        onProbeInvalidated = { false },
        onProbeFinished = {},
        onPlaying = {},
    )

    private fun checkpoint() = MutedPositionCheckpoint(
        item = MediaItem.Builder().setMediaId("fixture-book").build(),
        position = 1.seconds,
        controllerCommandGeneration = 0,
        mediaItemGeneration = 0,
        profileGeneration = 0,
        bookGeneration = null,
        selectionSequence = null,
    )

    private class MutableMediaPlayer {
        var volume = 1f
        var pauseCalls = 0
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getVolume" -> volume

                "setVolume" -> {
                    volume = args!![0] as Float
                    null
                }

                "pause" -> {
                    pauseCalls += 1
                    null
                }

                "addListener", "removeListener" -> null

                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as ExoPlayer
    }

    private companion object {
        const val OUTPUT = "headset:fixture"
        const val EPOCH = 1L
    }
}
