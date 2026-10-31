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
    fun `delayed buffering and overshoot before barrier acknowledgement refuse without retry`() = runTest {
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
        assertEquals(1, fixture.messages.size)
        assertEquals(12_000L, fixture.rawPosition)
        assertNull(fixture.activeProbe())
        assertFalse(fixture.playWhenReady)
        assertEquals(1f, fixture.volume)
        assertEquals(baselineListeners, fixture.playbackListeners.size, "temporary observer must be removed")
    }

    @Test
    fun `buffered Car to Headset accepts only bounded settling in either callback order`() = runTest {
        for (readyBeforeBarrier in listOf(false, true)) {
            for (advance in listOf(20L, 50L, 116L)) {
                val fixture = bufferedTransfer()
                firstReady(fixture, readyBeforeBarrier, advance)
                assertEquals(1, fixture.messages.size, "there is only one correction and barrier")
                assertNull(fixture.activeProbe())
                assertEquals(advance <= 50L, fixture.playWhenReady)
                assertEquals(if (advance <= 50L) 12_000L + advance else 12_000L, fixture.rawPosition)
                assertEquals(
                    if (advance <=
                        50L
                    ) {
                        OutputIntentOwner.TransferPhase.Verified
                    } else {
                        OutputIntentOwner.TransferPhase.Failed
                    },
                    fixture.router.outputIntentOwner.state.value.transfer?.phase,
                )
                assertEquals(1f, fixture.volume)
                assertEquals(0, fixture.plays)
            }
        }
    }

    @Test
    fun `buffered overshoot and backward movement restore while paused without another barrier`() = runTest {
        for (advance in listOf(51L, -1L)) {
            val fixture = bufferedTransfer()
            firstReady(fixture, readyBeforeBarrier = false, advance = advance)
            assertNull(fixture.activeProbe())
            assertFalse(fixture.playWhenReady)
            assertEquals(12_000L, fixture.rawPosition)
            assertEquals(
                OutputIntentOwner.TransferPhase.Failed,
                fixture.router.outputIntentOwner.state.value.transfer?.phase,
            )
            assertEquals(1, fixture.messages.size)
            assertEquals(0, fixture.plays)
        }
    }

    @Test
    fun `Pause during buffered correction cancels its late acknowledgement`() = runTest {
        val fixture = bufferedTransfer()
        assertEquals(1, fixture.messages.size)
        val finished = assertNotNull(fixture.effects.before(PlaybackMovement.Pause))
        fixture.command++
        fixture.media.pause()
        finished()
        runCurrent()
        assertTrue(fixture.messages[0].isCanceled)
        fixture.rawPosition = 12_010L
        fixture.playbackState = Player.STATE_READY
        fixture.deliver(0)
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        assertNull(fixture.activeProbe())
        assertFalse(fixture.playWhenReady)
        assertEquals(0, fixture.plays)
        assertEquals(1, fixture.messages.size)
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

    private fun TestScope.firstReady(
        fixture: MutedPositionEffectsTest.Fixture,
        readyBeforeBarrier: Boolean,
        advance: Long,
    ) {
        if (!readyBeforeBarrier) {
            fixture.deliver(0)
            runCurrent()
        }
        fixture.rawPosition = 12_000L + advance
        fixture.playbackState = Player.STATE_READY
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        if (readyBeforeBarrier) fixture.deliver(0)
        runCurrent()
    }
}
