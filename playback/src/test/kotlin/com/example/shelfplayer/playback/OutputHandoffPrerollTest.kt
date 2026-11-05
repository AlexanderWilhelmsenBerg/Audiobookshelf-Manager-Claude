package com.example.shelfplayer.playback

import androidx.media3.common.Player
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

@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputHandoffPrerollTest {
    @Test
    fun `delayed application continuation after held-point delivery refuses without live retry`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val unmutedWhilePlaying = mutableListOf<Boolean>()
        fixture.gains.addMuteListener { change ->
            if (!change.muted) unmutedWhilePlaying += fixture.playWhenReady
        }
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        fixture.rawPosition += 307L
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        fixture.deliver(0)
        runCurrent()
        assertEquals(11_750L, fixture.rawPosition)
        assertEquals(12_000L, fixture.messages.last().positionMs)

        // Deliver at the held point on Media3's playback thread, then model 177ms of progress before
        // the suspended MainImmediate continuation gets a turn.
        fixture.rawPosition = 12_000L
        fixture.deliver(1)
        fixture.rawPosition = 12_177L
        runCurrent()

        assertFalse(fixture.playWhenReady)
        assertEquals(12_000L, fixture.rawPosition, "failure restores the held position after pausing")
        assertEquals(1f, fixture.volume, "mute releases only after playback is paused")
        assertEquals(listOf(false), unmutedWhilePlaying)
        assertNull(fixture.activeProbe())
        assertEquals(2, fixture.messages.size, "failure must not launch a new timed proof or seek")
        assertEquals(listOf(true, true), fixture.compensationOrigins)
    }

    @Test
    fun `preparation handles faster playback and a checkpoint near the book start without a negative seek`() = runTest {
        for ((held, speed, start) in listOf(
            Triple(12_000L, 2f, 11_500L),
            Triple(12_000L, 1.25f, 11_687L),
            Triple(100L, 1f, 0L),
        )) {
            val fixture = MutedPositionEffectsTest.Fixture(this)
            fixture.rawPosition = held
            fixture.playbackSpeed = speed
            fixture.report(AudioTrackPath.RemoteSubmix)
            fixture.router.select(fixture.outputId)
            fixture.rawPosition += 307L
            fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
            runCurrent()
            assertEquals(start, fixture.rawPosition)
            fixture.deliver(0)
            runCurrent()
            assertEquals(0f, fixture.volume)
            fixture.rawPosition = held
            fixture.deliver(1)
            runCurrent()
            assertTrue(fixture.playWhenReady)
            assertEquals(1f, fixture.volume)
            assertEquals(speed, fixture.playbackSpeed)
            assertEquals(held, fixture.rawPosition)
            assertEquals(1, fixture.compensationOrigins.size)
        }
    }

    @Test
    fun `pause during preparation cancels the timed message and restores the held point`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        fixture.rawPosition += 307L
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        fixture.deliver(0)
        runCurrent()
        fixture.externalPause()
        runCurrent()
        assertTrue(fixture.messages[1].isCanceled)
        fixture.deliver(1)
        runCurrent()
        assertEquals(12_000L, fixture.rawPosition)
        assertFalse(fixture.playWhenReady)
        assertNull(fixture.activeProbe())
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `missing held-point delivery times out paused and cancels the message`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        fixture.rawPosition += 307L
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        fixture.deliver(0)
        runCurrent()
        advanceTimeBy(1_001L)
        runCurrent()
        assertTrue(fixture.messages[1].isCanceled)
        assertFalse(fixture.playWhenReady)
        assertEquals(12_000L, fixture.rawPosition)
        assertNull(fixture.activeProbe())
    }

    @Test
    fun `route changes during preparation cannot inherit earlier proof`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        fixture.rawPosition += 307L
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        fixture.deliver(0)
        runCurrent()
        fixture.rawPosition = 12_000L
        fixture.report(AudioTrackPath.Endpoint(fixture.speakerId), epoch = 2)
        fixture.deliver(1)
        runCurrent()
        assertFalse(fixture.playWhenReady)
        assertNull(fixture.activeProbe())
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `replacement becoming ready after 68ms prepares before held narration instead of refusing`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.bufferOnSeek = true
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        runCurrent()
        fixture.rawPosition += 307L
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        assertEquals(
            11_750L,
            fixture.rawPosition,
            "prepare behind the checkpoint, without discarding unheard narration",
        )
        fixture.deliver(0)
        runCurrent()
        fixture.rawPosition += 68L
        fixture.reportPlaybackState(Player.STATE_READY)
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId), epoch = 2)
        runCurrent()
        assertNotNull(fixture.activeProbe())
        assertEquals(0f, fixture.volume, "preparation must stay inaudible")
        assertEquals(12_000L, fixture.effects.positionMs(), "progress remains held")
        assertEquals(12_000L, fixture.messages.last().positionMs)
        fixture.rawPosition = 12_005L
        fixture.deliver(1)
        runCurrent()
        assertNull(fixture.activeProbe())
        assertTrue(fixture.playWhenReady)
        assertEquals(1f, fixture.volume)
        assertEquals(listOf(true), fixture.compensationOrigins, "only one live seek")
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `late delivery still refuses 68ms beyond held narration`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        fixture.rawPosition += 307L
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        fixture.deliver(0)
        runCurrent()
        assertEquals(0f, fixture.volume)
        fixture.rawPosition = 12_068L
        fixture.deliver(1)
        runCurrent()
        assertFalse(fixture.playWhenReady)
        assertEquals(12_000L, fixture.rawPosition)
        assertNull(fixture.activeProbe())
        assertEquals(0, fixture.plays)
    }
}
