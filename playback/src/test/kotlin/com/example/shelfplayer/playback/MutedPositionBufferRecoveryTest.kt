package com.example.shelfplayer.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

/** PLAY-002/004 — APK2244's real target arrives after buffering with 116ms of unheard advancement. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MutedPositionBufferRecoveryTest {
    @Test
    fun `buffering after seek returns is retained when Ready precedes the barrier acknowledgement`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.start()
        fixture.rawPosition = 25_000L
        fixture.prove()
        runCurrent()
        val baselineListeners = fixture.playbackListeners.size - 1
        fixture.reportPlaybackState(Player.STATE_BUFFERING)
        fixture.rawPosition = 12_116L
        fixture.reportPlaybackState(Player.STATE_READY)
        fixture.deliver(0)
        runCurrent()
        assertEquals(2, fixture.messages.size)
        assertEquals(12_000L, fixture.rawPosition)
        fixture.rawPosition = 12_010L
        fixture.deliver(1)
        runCurrent()

        assertNull(fixture.activeProbe())
        assertTrue(fixture.playWhenReady)
        assertEquals(1f, fixture.volume)
        assertEquals(baselineListeners, fixture.playbackListeners.size, "temporary observer must be removed")
    }

    @Test
    fun `buffered Car to Headset transfer compensates delayed ready proof once in either callback order`() = runTest {
        for (readyBeforeBarrier in listOf(false, true)) {
            val fixture = bufferedTransfer()
            firstReady(fixture, readyBeforeBarrier)
            assertEquals(2, fixture.messages.size, "late ready proof needs the one remaining correction")
            assertEquals(12_000L, fixture.rawPosition)
            assertEquals(0f, fixture.volume)
            assertTrue(fixture.playWhenReady)

            fixture.deliver(1)
            runCurrent()
            fixture.rawPosition = 12_020L
            fixture.playbackState = Player.STATE_READY
            fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
            runCurrent()

            assertNull(fixture.activeProbe())
            assertEquals(
                OutputIntentOwner.TransferPhase.Verified,
                fixture.router.outputIntentOwner.state.value.transfer?.phase,
            )
            assertEquals(12_020L, fixture.rawPosition)
            assertEquals(1f, fixture.volume)
            assertTrue(fixture.playWhenReady)
            assertEquals(0, fixture.plays)
            assertEquals(2, fixture.messages.size)
        }
    }

    @Test
    fun `a second buffered overshoot or backward movement still restores and pauses without another barrier`() =
        runTest {
            for (advance in listOf(51L, -1L)) {
                val fixture = bufferedTransfer()
                firstReady(fixture, readyBeforeBarrier = false)
                assertEquals(2, fixture.messages.size)
                fixture.deliver(1)
                runCurrent()
                fixture.rawPosition = 12_000L + advance
                fixture.playbackState = Player.STATE_READY
                fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
                runCurrent()

                assertNull(fixture.activeProbe())
                assertFalse(fixture.playWhenReady)
                assertEquals(12_000L, fixture.rawPosition)
                assertEquals(
                    OutputIntentOwner.TransferPhase.Failed,
                    fixture.router.outputIntentOwner.state.value.transfer?.phase,
                )
                assertEquals(2, fixture.messages.size, "buffering cannot restart the two-stage protocol")
                assertEquals(0, fixture.plays)
            }
        }

    @Test
    fun `Pause during buffered second correction cancels its late acknowledgement`() = runTest {
        val fixture = bufferedTransfer()
        firstReady(fixture, readyBeforeBarrier = false)
        assertEquals(2, fixture.messages.size)
        val finished = assertNotNull(fixture.effects.before(PlaybackMovement.Pause))
        fixture.command++
        fixture.media.pause()
        finished()
        runCurrent()
        assertTrue(fixture.messages[1].isCanceled)

        fixture.rawPosition = 12_010L
        fixture.playbackState = Player.STATE_READY
        fixture.deliver(1)
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()

        assertNull(fixture.activeProbe())
        assertFalse(fixture.playWhenReady)
        assertEquals(0, fixture.plays)
        assertEquals(2, fixture.messages.size)
    }

    private fun TestScope.bufferedTransfer(): MutedPositionEffectsTest.Fixture {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.router.selectCar()
        runCurrent()
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertEquals(
            OutputIntentOwner.TransferPhase.Delegated,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
        fixture.bufferOnSeek = true
        fixture.router.select(fixture.outputId)
        fixture.rawPosition = 12_266L
        runCurrent()
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        assertEquals(Player.STATE_BUFFERING, fixture.playbackState)
        assertEquals(1, fixture.messages.size)
        assertEquals(12_000L, fixture.rawPosition)
        return fixture
    }

    private fun TestScope.firstReady(fixture: MutedPositionEffectsTest.Fixture, readyBeforeBarrier: Boolean) {
        if (!readyBeforeBarrier) {
            fixture.deliver(0)
            runCurrent()
        }
        fixture.rawPosition = 12_116L
        fixture.playbackState = Player.STATE_READY
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        if (readyBeforeBarrier) fixture.deliver(0)
        runCurrent()
    }
}
