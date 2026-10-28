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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    @Test
    fun `transfer replacement keeps silence and its checkpoint through synchronous publication`() = runTest {
        val effects = PlaybackVolumeEffects(TestAppClock())
        val media = MutableMediaPlayer()
        val guard = guard(backgroundScope, UnconfinedTestDispatcher(testScheduler), effects, media.player)
        effects.attach(media.player)
        guard.attachPlayer(media.player)
        guard.protect(OUTPUT)
        guard.captureCheckpoint = { checkpoint() }
        val muteEdges = mutableListOf<Boolean>()
        effects.addMuteListener { muteEdges += it.muted }
        assertTrue(guard.beginProbe(OUTPUT, media.player, EPOCH))
        val previous = assertNotNull(guard.currentProbe)
        previous.requiresPlaybackBarrier = true
        val owner = OutputIntentOwner().apply { onBookChanged(7) }
        val prepared = owner.prepareTransfer(null, OutputIntentOwner.Destination.Car, EPOCH + 1, null)

        assertTrue(
            guard.beginTransfer(prepared.request, media.player) { active ->
                assertTrue(effects.isRouteMuted(media.player))
                assertNotNull(guard.positionCheckpoint)
                assertNull(active.muteClaim, "publication cannot run completion against a half-bound selection")
                owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying)
            },
        )
        val successor = assertNotNull(guard.currentProbe)
        assertTrue(successor.requiresPlaybackBarrier)
        guard.cancelProbe(previous)
        assertSame(successor, guard.currentProbe)
        assertEquals(listOf(true), muteEdges, "handover must not release the old claim before acquiring its successor")
        guard.cancelProbe(successor)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(true, false), muteEdges)
        assertEquals(1f, media.volume, "both exact mute claims must be retired")
        assertEquals(0, media.pauseCalls)
    }

    @Test
    fun `nested transfer during mute acquisition prevents older intent publication and claim leaks`() = runTest {
        val effects = PlaybackVolumeEffects(TestAppClock())
        val media = MutableMediaPlayer()
        val guard = guard(backgroundScope, UnconfinedTestDispatcher(testScheduler), effects, media.player)
        effects.attach(media.player)
        guard.attachPlayer(media.player)
        guard.captureCheckpoint = { checkpoint() }
        val owner = OutputIntentOwner().apply { onBookChanged(7) }
        val older = owner.prepareTransfer(null, OutputIntentOwner.Destination.Car, EPOCH, null)
        var newer: OutputIntentOwner.PreparedTransfer? = null
        var olderPublications = 0
        effects.addMuteListener { change ->
            if (change.muted && newer == null) {
                val choice = owner.prepareTransfer(OUTPUT, OutputIntentOwner.Destination.Output, EPOCH + 1, null)
                newer = choice
                assertTrue(
                    guard.beginTransfer(choice.request, media.player) {
                        owner.publishTransfer(choice, OutputIntentOwner.TransferPhase.Verifying)
                    },
                )
            }
        }

        assertFalse(
            guard.beginTransfer(older.request, media.player) {
                olderPublications++
                owner.publishTransfer(older, OutputIntentOwner.TransferPhase.Verifying)
            },
        )

        assertEquals(0, olderPublications)
        assertEquals(newer?.request?.selection, owner.selection.value)
        assertTrue(effects.isRouteMuted(media.player))
        guard.cancelProbe()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1f, media.volume)
        assertFalse(effects.isRouteMuted(media.player))
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
        isCurrentScope = { probe ->
            probe.player === player && (probe.transferRequest != null || probe.endpointId == OUTPUT)
        },
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
