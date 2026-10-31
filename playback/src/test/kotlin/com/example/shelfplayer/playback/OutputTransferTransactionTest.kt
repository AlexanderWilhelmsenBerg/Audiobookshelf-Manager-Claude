package com.example.shelfplayer.playback

import androidx.media3.common.Player
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputTransferTransactionTest {
    @Test
    fun `transfer waits for destination before its only position restoration`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        fixture.report(AudioTrackPath.RemoteSubmix)
        fixture.router.select(fixture.outputId)
        fixture.rawPosition = 12_100L
        runCurrent()
        assertTrue(fixture.compensationOrigins.isEmpty(), "old output must not be sought while awaiting destination")
        assertEquals(0f, fixture.volume)

        fixture.rawPosition = 12_168L
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
        runCurrent()
        assertEquals(listOf(true), fixture.compensationOrigins)
        fixture.report(AudioTrackPath.Endpoint(fixture.outputId), epoch = 2)
        fixture.deliver(0)
        runCurrent()
        assertEquals(0f, fixture.volume)
        fixture.rawPosition = 12_000L
        fixture.deliver(1)
        runCurrent()
        assertNull(fixture.activeProbe())
        assertEquals(2, fixture.messages.size)
        assertEquals(1f, fixture.volume)
        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `buffered replacement exceeding position budget pauses without seeking through another proof cycle`() =
        runTest {
            val fixture = MutedPositionEffectsTest.Fixture(this)
            fixture.bufferOnSeek = true
            fixture.report(AudioTrackPath.RemoteSubmix)
            fixture.router.select(fixture.outputId)
            runCurrent()
            fixture.rawPosition = 12_168L
            fixture.report(AudioTrackPath.Endpoint(fixture.outputId))
            runCurrent()
            fixture.deliver(0)
            runCurrent()
            fixture.rawPosition = 12_051L
            fixture.reportPlaybackState(Player.STATE_READY)
            fixture.report(AudioTrackPath.Endpoint(fixture.outputId), epoch = 2)
            fixture.deliver(1)
            runCurrent()
            assertNull(fixture.activeProbe())
            assertFalse(fixture.playWhenReady)
            assertEquals(12_000L, fixture.rawPosition)
            assertEquals(2, fixture.messages.size, "barrier and held-point message; no repeated live proof")
            assertEquals(0, fixture.plays)
        }

    @Test
    fun `same output preservation refuses route loss during success publication`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        var delivered = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { state ->
                if (!delivered && state.transfer?.phase == OutputIntentOwner.TransferPhase.Verified) {
                    delivered = true
                    fixture.observation = AudioTrackObservation(2, AudioTrackPath.Endpoint(fixture.speakerId))
                }
            }
        }
        fixture.router.select(fixture.outputId)
        runCurrent()
        assertTrue(delivered)
        assertFalse(fixture.playWhenReady)
        assertEquals(OutputIntentOwner.TransferPhase.Failed, owner.state.value.transfer?.phase)
        assertTrue(fixture.preferenceRequests.isEmpty())
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `newer Car choice during same output publication owns mute and cannot be paused by old selection`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val owner = fixture.router.outputIntentOwner
        var delivered = false
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.state.collect { state ->
                if (!delivered && state.transfer?.phase == OutputIntentOwner.TransferPhase.Verified) {
                    delivered = true
                    fixture.router.selectCar()
                }
            }
        }
        fixture.router.select(fixture.outputId)
        runCurrent()
        assertTrue(delivered)
        assertEquals(OutputIntentOwner.Destination.Car, owner.selection.value?.destination)
        assertTrue(fixture.playWhenReady)
        assertEquals(0f, fixture.volume)
        fixture.report(AudioTrackPath.RemoteSubmix)
        runCurrent()
        assertEquals(OutputIntentOwner.TransferPhase.Delegated, owner.state.value.transfer?.phase)
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.pauses)
    }

    @Test
    fun `explicit already measured destination changes intent without disturbing output`() = runTest {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        val muteChanges = mutableListOf<Boolean>()
        fixture.gains.addMuteListener { muteChanges += it.muted }
        fixture.router.select(fixture.outputId)
        runCurrent()
        assertTrue(muteChanges.isEmpty())
        assertTrue(fixture.preferenceRequests.isEmpty())
        assertTrue(fixture.compensationOrigins.isEmpty())
        assertNull(fixture.activeProbe())
        assertEquals(fixture.outputId, fixture.router.explicitSelection.value?.outputId)
        assertEquals(
            OutputIntentOwner.TransferPhase.Verified,
            fixture.router.outputIntentOwner.state.value.transfer?.phase,
        )
        assertEquals(0, fixture.plays)
        assertEquals(0, fixture.pauses)
    }
}
