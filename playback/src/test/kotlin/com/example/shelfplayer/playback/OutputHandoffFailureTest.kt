package com.example.shelfplayer.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
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

/** Decoder failure must retire the same transaction used by explicit Car and Headset actions. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputHandoffFailureTest {
    @Test
    fun `decoder failure immediately retires transfer and preserves destination and held position`() = runTest {
        for (car in listOf(true, false)) {
            val fixture = MutedPositionEffectsTest.Fixture(this)
            if (car) {
                fixture.router.selectCar()
            } else {
                fixture.report(AudioTrackPath.RemoteSubmix)
                fixture.router.select(fixture.outputId)
            }
            runCurrent()
            val selection = fixture.router.explicitSelection.value
            val guard = fixture.router.positions.guard
            val old = assertNotNull(guard.currentProbe)
            fixture.rawPosition = 25_000L

            guard.currentPlayerListener!!.onPlayerError(failure())

            assertFalse(fixture.playWhenReady, "pause before releasing route mute")
            assertNull(fixture.activeProbe(), "failure cannot wait for a probe timeout")
            assertEquals(12_000L, fixture.rawPosition, "unheard advancement is not progress")
            assertEquals(selection, fixture.router.explicitSelection.value)
            assertEquals(
                OutputIntentOwner.TransferPhase.Failed,
                fixture.router.outputIntentOwner.state.value.transfer?.phase,
            )
            assertFalse(guard.finishProbe(old, verified = true), "late output proof cannot revive a failed transfer")
            advanceTimeBy(5_100L)
            runCurrent()
            assertEquals(0, fixture.plays, "decoder errors never manufacture Play")

            fixture.command++
            assertTrue(fixture.playWithProtection { assertEquals(0f, fixture.volume) })
            fixture.report(if (car) AudioTrackPath.RemoteSubmix else AudioTrackPath.Endpoint(fixture.outputId))
            runCurrent()
            assertEquals(selection, fixture.router.explicitSelection.value, "manual retry uses the chosen endpoint")
            assertEquals(1, fixture.plays)
            assertEquals(1f, fixture.volume)
            assertNull(fixture.activeProbe())
        }
    }

    @Test
    fun `decoder failure cancels suspended position restoration before late message delivery`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.router.selectCar()
        runCurrent()
        fixture.rawPosition = 25_000L
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertTrue(fixture.messages.isNotEmpty(), "restoration must have suspended on a real player message")
        fixture.router.positions.guard.currentPlayerListener!!.onPlayerError(failure())
        fixture.messages.indices.forEach(fixture::deliver)
        runCurrent()
        assertFalse(fixture.playWhenReady)
        assertEquals(12_000L, fixture.rawPosition)
        assertNull(fixture.activeProbe())
        assertEquals(
            OutputIntentOwner.TransferPhase.Failed,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `retired attachment error callback cannot pause or retire a newer probe`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val guard = fixture.router.positions.guard
        val retired = assertNotNull(guard.currentPlayerListener)
        guard.attachPlayer(fixture.media)
        fixture.router.selectCar()
        runCurrent()
        val active = assertNotNull(fixture.activeProbe())
        retired.onPlayerError(failure())
        assertEquals(active, fixture.activeProbe())
        assertTrue(fixture.playWhenReady)
        assertEquals(0, fixture.pauses)
    }

    private fun failure() = PlaybackException("private", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    @Test
    fun `transient network failure retains the existing bounded retry intent`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.router.selectCar()
        runCurrent()
        val active = assertNotNull(fixture.activeProbe())
        fixture.router.positions.guard.currentPlayerListener!!.onPlayerError(
            PlaybackException("private", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
        )
        assertEquals(active, fixture.activeProbe())
        assertTrue(fixture.playWhenReady)
        assertEquals(0f, fixture.volume, "I/O retry must retain the existing mute/proof boundary")
        assertEquals(0, fixture.pauses)
    }
}
