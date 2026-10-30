package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputContinuityTimingTest {
    @Test
    fun `repeated car arrival preserves measured healthy headset without playback effects`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(104)
        fixture.measuredRoutes { setOf(endpoint.id) }
        fixture.router.currentTrackObservation = { AudioTrackObservation(1, AudioTrackPath.Endpoint(endpoint.id)) }
        fixture.present(endpoint)
        val owner = RouteHeardOwnership(fixture.router.outputIntentOwner).apply {
            onBookChanged(true)
            onPlaybackObserved(fixture.router.outputs.value)
        }
        val effects = CarContinuityResumeEffects(
            recovery = CarContinuityRecoveryCoordinator(CarArrivalResumeGate()),
            outputs = fixture.router,
            owner = owner,
            trace = { _, _ -> },
            currentPlayer = { fixture.player },
            selectionSequence = { fixture.router.explicitSelection.value?.sequence ?: 0 },
            snapshot = { emptyList() },
            resumePlayer = { _, _, _ -> error("A healthy arrival must not create Play") },
            scope = backgroundScope,
        )

        repeat(3) {
            effects.onArrival(null)
            runCurrent()
        }
        advanceTimeBy(500)
        runCurrent()

        assertEquals(endpoint.id, fixture.guardId())
        assertTrue(fixture.preferredRequests.isEmpty())
        assertTrue(fixture.pauseThreads.isEmpty())
        assertTrue(fixture.volumeChanges.isEmpty())
        assertEquals(null, fixture.router.explicitSelection.value)
        effects.cancelPending()
    }

    @Test
    fun `measured healthy reassert completes immediately without disturbing the route`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(104)
        fixture.measuredRoutes { setOf(endpoint.id) }
        fixture.router.currentTrackObservation = { AudioTrackObservation(1, AudioTrackPath.Endpoint(endpoint.id)) }
        fixture.present(endpoint)
        fixture.router.outputIntentOwner.onBookChanged(1)
        var completed = false
        val work = launch { completed = fixture.router.reassert(endpoint.id) }
        runCurrent()
        assertTrue(completed)
        assertTrue(fixture.preferredRequests.isEmpty())
        assertEquals(0, testScheduler.currentTime)
        work.join()
    }
}
