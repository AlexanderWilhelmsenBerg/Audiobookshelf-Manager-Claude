package com.example.shelfplayer.playback

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** PLAY-002/ROUTE-001 — a departed, paused Car choice must not reject later deliberate Play. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceCarDepartureIntentTest {
    @Test
    fun `confirmed departure retires unlocked paused Car without Play and permits one later manual Play`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.projection(null, AndroidAutoProjectionMonitor.State.Projection)
            fixture.selectCar()
            runCurrent()
            val before = fixture.explicitSelectionSequence()
            assertEquals(OutputIntentOwner.Destination.Car, fixture.explicitDestination())
            assertFalse(fixture.player.playWhenReady)

            fixture.projection(
                AndroidAutoProjectionMonitor.State.Projection,
                AndroidAutoProjectionMonitor.State.NotConnected,
            )
            runCurrent()

            assertEquals(OutputIntentOwner.Destination.Automatic, fixture.explicitDestination())
            assertTrue(fixture.explicitSelectionSequence() > before)
            assertNull(fixture.service.audioOutputs.outputIntentOwner.state.value.transfer)
            assertNull(fixture.service.audioOutputs.outputIntentOwner.state.value.releasedRouteId)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(0, fixture.rawPlayCalls)
            assertTrue(fixture.commands.play(fixture.player, stillAuthorized = { true }))
            assertEquals(1, fixture.rawPlayCalls)
            assertNull(fixture.activeTransferDestination())
        }
    }

    @Test
    fun `unknown projection and its recovery retain the paused Car choice`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.projection(null, AndroidAutoProjectionMonitor.State.Projection)
            fixture.selectCar()
            runCurrent()
            val selection = fixture.service.audioOutputs.explicitSelection.value
            fixture.projection(
                AndroidAutoProjectionMonitor.State.Projection,
                AndroidAutoProjectionMonitor.State.Unknown,
            )
            fixture.projection(
                AndroidAutoProjectionMonitor.State.Unknown,
                AndroidAutoProjectionMonitor.State.Projection,
            )
            runCurrent()

            assertEquals(selection, fixture.service.audioOutputs.explicitSelection.value)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(0, fixture.rawPlayCalls)
        }
    }

    @Test
    fun `physical departure cannot clear a newer explicit headset selection and logical lock`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.projection(null, AndroidAutoProjectionMonitor.State.Projection)
            fixture.selectCar()
            runCurrent()
            fixture.returnHeadset()
            val owner = fixture.service.audioOutputs.outputIntentOwner
            fixture.service.audioOutputs.select(fixture.endpointId)
            runCurrent()
            // Seed retained logical ownership only; audible proof is tested through the transfer fixture.
            owner.keepHere(fixture.endpointId, requireNotNull(owner.state.value.activeBookGeneration), 0)
            val before = owner.state.value
            fixture.projection(
                AndroidAutoProjectionMonitor.State.Projection,
                AndroidAutoProjectionMonitor.State.NotConnected,
            )
            runCurrent()

            assertEquals(before.selection, owner.selection.value)
            assertEquals(before.lock?.outputId, owner.lock.value?.outputId)
            assertFalse(fixture.player.playWhenReady)
            assertEquals(0, fixture.rawPlayCalls)
        }
    }

    private fun PlaybackServiceCommandContinuityFixture.projection(
        previous: AndroidAutoProjectionMonitor.State?,
        current: AndroidAutoProjectionMonitor.State,
    ) {
        service.playbackCall(
            "onCarProjectionUpdate",
            AndroidAutoProjectionMonitor.Update::class.java,
            AndroidAutoProjectionMonitor.Update(previous, current),
        )
    }
}
