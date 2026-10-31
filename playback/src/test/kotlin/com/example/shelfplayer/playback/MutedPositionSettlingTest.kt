package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLAY-002/004 — a playback-thread message orders a seek; it cannot freeze the running media clock. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MutedPositionSettlingTest {
    @Test
    fun `exact settling boundary succeeds while a backward adjustment is refused`() = runTest {
        for (advance in listOf(50L, -1L)) {
            val fixture = MutedPositionEffectsTest.Fixture(this)
            fixture.start()
            fixture.rawPosition = 25_000L
            fixture.prove()
            runCurrent()
            fixture.rawPosition = 12_000L + advance
            fixture.deliver(0)
            runCurrent()

            assertNull(fixture.activeProbe())
            assertEquals(advance == 50L, fixture.playWhenReady)
            assertEquals(if (advance == 50L) 12_050L else 12_000L, fixture.rawPosition)
            assertEquals(1, fixture.messages.size)
            assertEquals(1f, fixture.volume)
            assertEquals(0, fixture.plays)
        }
    }

    @Test
    fun `clock movement during success publication is checked before releasing the prior lock`() = runTest {
        for (advance in listOf(50L, 51L)) {
            val fixture = MutedPositionEffectsTest.Fixture(this)
            assertTrue(fixture.router.keepAudioHere())
            val owner = fixture.router.outputIntentOwner
            var delivered = false
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                owner.state.collect {
                    if (it.transfer?.phase == OutputIntentOwner.TransferPhase.Delegated) {
                        delivered = true
                        fixture.rawPosition = 12_000L + advance
                    }
                }
            }
            fixture.router.selectCar()
            runCurrent()
            fixture.report(AudioTrackPath.RemoteSubmix)
            runCurrent()

            assertTrue(delivered)
            assertEquals(advance == 50L, fixture.playWhenReady)
            assertEquals(if (advance == 50L) 12_050L else 12_000L, fixture.rawPosition)
            val phase = if (advance == 50L) {
                OutputIntentOwner.TransferPhase.Delegated
            } else {
                OutputIntentOwner.TransferPhase.Failed
            }
            assertEquals(phase, owner.state.value.transfer?.phase)
            assertEquals(if (advance == 50L) null else fixture.outputId, owner.lock.value?.outputId)
            assertEquals(if (advance == 50L) null else OutputIntentOwner.LockPhase.Waiting, owner.lock.value?.phase)
            assertNull(fixture.activeProbe())
            assertEquals(1f, fixture.volume)
            assertEquals(0, fixture.plays)
        }
    }

    @Test
    fun `small post-barrier clock movement finishes once instead of seeking again`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()

        fixture.rawPosition = 12_040L
        fixture.deliver(0)
        runCurrent()

        assertNull(fixture.activeProbe())
        assertEquals(1f, fixture.volume)
        assertEquals(12_040L, fixture.rawPosition)
        assertEquals(1, fixture.messages.size, "a running clock cannot cause another seek/barrier")
        assertEquals(listOf(true), fixture.compensationOrigins)
        assertTrue(fixture.playWhenReady)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `buffering after compensation waits for ready without another seek`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.start()
        fixture.bufferOnSeek = true
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()
        assertEquals(Player.STATE_BUFFERING, fixture.playbackState)
        fixture.deliver(0)
        runCurrent()
        assertEquals(0f, fixture.volume, "a message delivery is not playing-route proof")

        fixture.rawPosition = 12_007L
        fixture.playbackState = Player.STATE_READY
        fixture.prove()
        runCurrent()

        assertNull(fixture.activeProbe())
        assertEquals(1f, fixture.volume)
        assertEquals(1, fixture.messages.size)
        assertEquals(listOf(true), fixture.compensationOrigins)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `excessive post-barrier advancement restores and pauses without retrying`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()

        fixture.rawPosition = 12_051L
        fixture.deliver(0)
        runCurrent()

        assertNull(fixture.activeProbe())
        assertFalse(fixture.playWhenReady)
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(1, fixture.pauses)
        assertEquals(1f, fixture.volume)
        assertEquals(1, fixture.messages.size, "refusal must not enqueue another automatic seek barrier")
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `late initial destination proof restores its whole wait once`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.start()
        fixture.routes = null
        fixture.rawPosition = 25_000L
        repeat(5) { fixture.prove() }
        runCurrent()
        assertTrue(fixture.messages.isEmpty(), "the old output is not sought during discovery")
        fixture.routes = setOf(fixture.outputId)
        fixture.prove()
        runCurrent()
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(1, fixture.messages.size)
        fixture.rawPosition = 12_040L
        fixture.deliver(0)
        runCurrent()
        assertNull(fixture.activeProbe())
        assertTrue(fixture.playWhenReady)
        assertEquals(1f, fixture.volume)
        assertEquals(1, fixture.messages.size)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `long unknown interval after restoration refuses without a second seek barrier`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()
        fixture.routes = null
        fixture.deliver(0)
        runCurrent()
        fixture.rawPosition = 20_000L
        repeat(5) { fixture.prove() }
        runCurrent()
        fixture.routes = setOf(fixture.outputId)
        fixture.prove()
        runCurrent()
        assertNull(fixture.activeProbe())
        assertFalse(fixture.playWhenReady)
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(1, fixture.messages.size)
        repeat(5) { fixture.prove() }
        runCurrent()
        assertEquals(1, fixture.messages.size)
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `listener Pause cancels the only barrier and its late delivery cannot restart audio`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()
        val finished = assertNotNull(fixture.effects.before(PlaybackMovement.Pause))
        fixture.command++
        fixture.media.pause()
        finished()
        runCurrent()
        assertTrue(fixture.messages[0].isCanceled)
        fixture.deliver(0)
        runCurrent()
        assertNull(fixture.activeProbe())
        assertFalse(fixture.playWhenReady)
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(1, fixture.messages.size)
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)
    }
}
