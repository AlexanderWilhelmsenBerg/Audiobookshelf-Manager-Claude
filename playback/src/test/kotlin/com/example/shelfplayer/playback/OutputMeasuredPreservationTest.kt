package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputMeasuredPreservationTest {
    @Test
    fun `prediction and unavailable ambiguous or replaced track cannot claim healthy preservation`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(104)
        fixture.present(endpoint)
        fixture.measuredRoutes { setOf(endpoint.id) }
        fixture.router.outputIntentOwner.onBookChanged(1)
        val exact = AudioTrackObservation(1, AudioTrackPath.Endpoint(endpoint.id))
        for (observation in listOf(
            null,
            exact.copy(path = AudioTrackPath.Multiple),
            exact.copy(path = AudioTrackPath.RemoteSubmix),
        )) {
            fixture.router.currentTrackObservation = { observation }
            assertFalse(fixture.router.isMeasuredHeadset(endpoint.id))
        }
        var epoch = 0L
        fixture.router.currentTrackObservation = { exact.copy(observerEpoch = ++epoch) }
        assertFalse(fixture.router.isMeasuredHeadset(endpoint.id))
        fixture.router.currentTrackObservation = { exact }
        fixture.colliding(endpoint.id)
        assertFalse(fixture.router.isMeasuredHeadset(endpoint.id))
        fixture.colliding(null)
        assertTrue(fixture.router.isMeasuredHeadset(endpoint.id))
        assertTrue(fixture.preferredRequests.isEmpty())
    }

    @Test
    fun `selection delivered during route measurement prevents old preference effects`() = runTest {
        val fixture = fixture()
        val endpoint = fixture.observeEndpoint(104)
        fixture.present(endpoint)
        fixture.router.outputIntentOwner.onBookChanged(1)
        fixture.router.currentTrackObservation = {
            fixture.router.outputIntentOwner.select(null, OutputIntentOwner.Destination.Car)
            AudioTrackObservation(1, AudioTrackPath.Endpoint(endpoint.id))
        }

        assertFalse(fixture.router.reassert(endpoint.id))
        assertTrue(fixture.preferredRequests.isEmpty())
        assertEquals(OutputIntentOwner.Destination.Car, fixture.router.explicitSelection.value?.destination)
    }
}
