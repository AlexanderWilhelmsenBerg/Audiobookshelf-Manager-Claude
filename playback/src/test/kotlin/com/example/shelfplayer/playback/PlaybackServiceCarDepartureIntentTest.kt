package com.example.shelfplayer.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
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
    fun `losing the playing car pauses before Android can fall back to a local output`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.projection(null, AndroidAutoProjectionMonitor.State.Projection)
            val router = fixture.service.audioOutputs
            fixture.attachCarOutputObserver()
            fixture.startDelegatedCar()
            // Android may already have changed the route by the time the departure callback arrives.
            router.currentTrackObservation = { AudioTrackObservation(1L, AudioTrackPath.Unresolved) }
            val before = fixture.player.currentPosition

            fixture.projection(
                AndroidAutoProjectionMonitor.State.Projection,
                AndroidAutoProjectionMonitor.State.NotConnected,
            )

            assertFalse(fixture.player.playWhenReady, "losing the active route pauses synchronously")
            runCurrent()
            assertEquals(OutputIntentOwner.Destination.Automatic, fixture.explicitDestination())
            assertEquals(before, fixture.player.currentPosition)
            assertEquals(0, fixture.rawPlayCalls)
        }
    }

    @Test
    fun `a local fallback pauses the playing car even before the projection callback`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            val manager = shadowOf(RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java))
            val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
            manager.setOutputDevices(listOf(speaker))
            fixture.projection(null, AndroidAutoProjectionMonitor.State.Projection)
            fixture.attachCarOutputObserver()
            fixture.startDelegatedCar()
            val router = fixture.service.audioOutputs
            val observe = requireNotNull(fixture.service.audioTrackRouteMonitor.onRouteChanged)
            // Route observation alone does not interrupt Car playback while the route is still projected.
            router.currentTrackObservation = { AudioTrackObservation(1L, AudioTrackPath.RemoteSubmix) }
            observe()
            assertTrue(fixture.player.playWhenReady)

            val speakerId = router.localOutputs.outputs().single().id
            router.currentTrackObservation = { AudioTrackObservation(1L, AudioTrackPath.Endpoint(speakerId)) }
            observe()

            assertFalse(fixture.player.playWhenReady)
            assertEquals(0, fixture.rawPlayCalls)
            assertEquals(OutputIntentOwner.Destination.Car, fixture.explicitDestination())
        }
    }

    @Test
    fun `losing the inactive car leaves the playing headset alone`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.projection(null, AndroidAutoProjectionMonitor.State.Projection)
            fixture.returnMeasuredHeadset()
            val generation = requireNotNull(fixture.currentIntentGeneration())
            val owner = fixture.service.audioOutputs.outputIntentOwner
            val lock = owner.keepHere(fixture.endpointId, generation, 0L)
            fixture.player.playWhenReady = true
            fixture.reportedIsPlaying = true

            fixture.projection(
                AndroidAutoProjectionMonitor.State.Projection,
                AndroidAutoProjectionMonitor.State.NotConnected,
            )
            runCurrent()

            assertTrue(fixture.player.playWhenReady)
            assertEquals(lock.selectionSequence, fixture.explicitSelectionSequence())
            assertEquals(fixture.endpointId, fixture.currentLock()?.outputId)
        }
    }

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
    fun `departure while playing leaves playback alone and clears Car on the next deliberate Play`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.projection(null, AndroidAutoProjectionMonitor.State.Projection)
            fixture.selectCar()
            runCurrent()
            val carSelection = fixture.explicitSelectionSequence()
            fixture.player.playWhenReady = true
            fixture.reportedIsPlaying = true
            fixture.projection(
                AndroidAutoProjectionMonitor.State.Projection,
                AndroidAutoProjectionMonitor.State.NotConnected,
            )
            fixture.returnMeasuredHeadset()
            runCurrent()

            assertTrue(fixture.player.playWhenReady)
            assertEquals(carSelection, fixture.explicitSelectionSequence())
            assertEquals(0, fixture.rawPlayCalls)

            assertNull(fixture.forwarding.handleSetPlayWhenReady(false).get())
            fixture.reportedIsPlaying = false
            runCurrent()
            assertFalse(fixture.player.playWhenReady)
            assertEquals(carSelection, fixture.explicitSelectionSequence())

            val play = fixture.forwarding.handleSetPlayWhenReady(true)
            assertEquals(OutputIntentOwner.Destination.Automatic, fixture.explicitDestination())
            runCurrent()
            assertEquals(true, play.get())
            assertEquals(1, fixture.rawPlayCalls)
            assertNull(fixture.activeTransferDestination())
        }
    }

    @Test
    fun `fresh Play after departure preserves a newer Car selection`() = runTest {
        PlaybackServiceCommandContinuityFixture(this).use { fixture ->
            fixture.projection(null, AndroidAutoProjectionMonitor.State.Projection)
            fixture.selectCar()
            runCurrent()
            fixture.player.playWhenReady = true
            fixture.projection(
                AndroidAutoProjectionMonitor.State.Projection,
                AndroidAutoProjectionMonitor.State.NotConnected,
            )
            runCurrent()
            assertNull(fixture.forwarding.handleSetPlayWhenReady(false).get())
            fixture.selectCar()
            runCurrent()
            val latest = fixture.explicitSelectionSequence()

            fixture.forwarding.handleSetPlayWhenReady(true)

            assertEquals(OutputIntentOwner.Destination.Car, fixture.explicitDestination())
            assertEquals(latest, fixture.explicitSelectionSequence())
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

    private fun PlaybackServiceCommandContinuityFixture.attachCarOutputObserver() {
        configureNoManualGuard()
        service.playbackCall("attachRouteSafetyMonitor", ExoPlayer::class.java, player)
    }

    private fun PlaybackServiceCommandContinuityFixture.startDelegatedCar() {
        val owner = service.audioOutputs.outputIntentOwner
        val car = owner.prepareTransfer(null, OutputIntentOwner.Destination.Car, 1L, null)
        assertTrue(owner.publishTransfer(car, OutputIntentOwner.TransferPhase.Verifying))
        assertTrue(owner.transitionTransfer(car.request, OutputIntentOwner.TransferPhase.Delegated))
        player.playWhenReady = true
        reportedIsPlaying = true
    }
}
