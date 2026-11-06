package com.example.shelfplayer.playback

import android.Manifest
import android.os.Build
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PLAY-002 / ROUTE-001: current-track preservation cannot become new transfer authority. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutputInventoryGapTest {
    @Test
    fun `inventory callbacks alone renew a preserved lock and still reject route loss`() = runTest {
        val fixture = fixture()
        val id = fixture.prepareGap(locked = true)
        val selection = fixture.router.explicitSelection.value
        repeat(3) {
            fixture.router.playbackCall("refresh")
            runCurrent()
            assertEquals(OutputIntentOwner.LockPhase.Active, fixture.router.outputLock.value?.phase)
            assertEquals(id, fixture.router.outputLock.value?.outputId)
            assertEquals(selection, fixture.router.explicitSelection.value)
            assertTrue(fixture.guardOwner().endpointMissing, "retained proof is not endpoint reappearance")
        }
        val published = fixture.router.outputs.value
        val grace = fixture.guardOwner().disappearanceGrace
        fixture.router.playbackCall("refresh")
        runCurrent()
        assertEquals(published, fixture.router.outputs.value, "equal rows cannot wake the service collector")
        assertEquals(OutputIntentOwner.LockPhase.Active, fixture.router.outputLock.value?.phase)
        assertEquals(grace, fixture.guardOwner().disappearanceGrace, "repeated callbacks must not restart grace")
        assertTrue(fixture.pauseThreads.isEmpty())
        assertTrue(fixture.preferredRequests.isEmpty())
        assertTrue(fixture.volumeChanges.isEmpty())
        fixture.router.currentTrackObservation = { AudioTrackObservation(3, AudioTrackPath.RemoteSubmix) }
        fixture.router.playbackCall("refresh")
        runCurrent()
        assertTrue(fixture.pauseThreads.isNotEmpty())
        assertEquals(OutputIntentOwner.LockPhase.Waiting, fixture.router.outputLock.value?.phase)
        assertEquals(id, fixture.router.outputLock.value?.outputId)
    }

    @Test
    fun `existing output lock survives an exact gap but still pauses on a wrong track route`() = runTest {
        val fixture = fixture()
        val id = fixture.prepareGap(locked = true)
        fixture.router.playbackCall("refresh")
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertTrue(fixture.pauseThreads.isEmpty())
        assertEquals(id, fixture.router.outputLock.value?.outputId)
        assertEquals(OutputIntentOwner.LockPhase.Active, fixture.router.outputLock.value?.phase)
        fixture.router.currentTrackObservation = { AudioTrackObservation(3, AudioTrackPath.RemoteSubmix) }
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertTrue(fixture.pauseThreads.isNotEmpty())
        assertEquals(id, fixture.router.outputLock.value?.outputId)
    }

    @Test
    fun `repeated inventory gaps preserve the exact playing headset without effects`() = runTest {
        val fixture = fixture()
        val id = fixture.prepareGap()
        repeat(3) {
            fixture.router.playbackCall("refresh")
            fixture.router.onActualTrackRouteChanged()
            fixture.safetyListener().onIsPlayingChanged(true)
            runCurrent()
        }
        assertEquals(id, fixture.router.selectedId.value)
        assertEquals(null, fixture.router.explicitSelection.value)
        assertTrue(fixture.pauseThreads.isEmpty())
        assertTrue(fixture.preferredRequests.isEmpty())
        assertTrue(fixture.volumeChanges.isEmpty())
        assertFalse(fixture.router.canKeepAudioHere.value, "absent inventory cannot create a new output lock")
    }

    @Test
    fun `unknown wrong multiple replaced and revoked evidence still pauses during an inventory gap`() = runTest {
        for (path in listOf(
            AudioTrackPath.Unresolved,
            AudioTrackPath.RemoteSubmix,
            AudioTrackPath.Multiple,
            AudioTrackPath.RetainedEndpoint("other"),
        )) {
            val fixture = fixture()
            fixture.prepareGap()
            fixture.router.currentTrackObservation = { AudioTrackObservation(3, path) }
            fixture.router.playbackCall("refresh")
            runCurrent()
            assertTrue(fixture.pauseThreads.isNotEmpty(), "$path must fail closed")
        }
        val fixture = fixture()
        val id = fixture.prepareGap()
        var epoch = 0L
        fixture.router.currentTrackObservation = { AudioTrackObservation(++epoch, AudioTrackPath.RetainedEndpoint(id)) }
        assertFalse(fixture.router.isMeasuredHeadset(id))
        fixture.router.currentTrackObservation = { AudioTrackObservation(3, AudioTrackPath.RetainedEndpoint(id)) }
        fixture.registry.updateIdentityAccess(false)
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertTrue(fixture.pauseThreads.isNotEmpty())
    }

    @Test
    fun `publication is passive while the actual route event still enforces safety`() = runTest {
        val fixture = fixture()
        fixture.prepareGap()
        fixture.router.currentTrackObservation = { null }
        fixture.router.playbackCall("publish")
        runCurrent()
        assertTrue(fixture.pauseThreads.isEmpty(), "display publication must not issue transport commands")
        fixture.router.onActualTrackRouteChanged()
        runCurrent()
        assertTrue(fixture.pauseThreads.isNotEmpty(), "the production route callback must enforce safety")
    }

    @Test
    fun `new selection during retained proof cannot preserve the old headset`() = runTest {
        val fixture = fixture()
        val id = fixture.prepareGap()
        fixture.router.currentTrackObservation = {
            fixture.router.outputIntentOwner.select(null, OutputIntentOwner.Destination.Car)
            AudioTrackObservation(3, AudioTrackPath.RetainedEndpoint(id))
        }
        assertFalse(fixture.router.isMeasuredHeadset(id))
        assertTrue(fixture.preferredRequests.isEmpty())
    }

    @Test
    fun `paused playback and book replacement cannot borrow a retained playing observation`() = runTest {
        val fixture = fixture()
        val id = fixture.prepareGap()
        fixture.router.currentTrackObservation = {
            fixture.router.outputIntentOwner.onBookChanged(2)
            AudioTrackObservation(3, AudioTrackPath.RetainedEndpoint(id))
        }
        assertFalse(fixture.router.isMeasuredHeadset(id))
        fixture.router.currentTrackObservation = { AudioTrackObservation(3, AudioTrackPath.RetainedEndpoint(id)) }
        fixture.player.pause()
        assertFalse(fixture.router.isMeasuredHeadset(id))
    }

    private fun Fixture.prepareGap(locked: Boolean = false): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val endpoint = observeEndpoint(104, identity = "process-private-fixture")
        present(endpoint)
        router.outputIntentOwner.onBookChanged(1)
        measuredRoutes { setOf(endpoint.id) }
        router.currentTrackObservation = { AudioTrackObservation(3, AudioTrackPath.Endpoint(endpoint.id)) }
        if (locked) assertTrue(router.keepAudioHere())
        guard(endpoint.id, connected = false)
        registry.reconcile(emptyList())
        measuredRoutes { emptySet() }
        router.currentTrackObservation = { AudioTrackObservation(3, AudioTrackPath.RetainedEndpoint(endpoint.id)) }
        return endpoint.id
    }
}
