package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

/** Production router/guard/transaction, with a track becoming readable without a routing callback. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MutedRouteObservationTest {
    @Test
    fun `late same-headset measurement completes without waiting for the settle fallback`() = runTest {
        val fixture = waitingProbe()
        fixture.routes = setOf(fixture.outputId)
        fixture.observation = AudioTrackObservation(1, AudioTrackPath.Endpoint(fixture.outputId))

        advanceTimeBy(21)
        runCurrent()

        assertNull(fixture.activeProbe())
        assertEquals(1f, fixture.volume)
        assertEquals(0, fixture.plays)
        assertEquals(0, fixture.pauses)
        assertEquals(12_000L, fixture.rawPosition)
        assertTrue(fixture.messages.isEmpty())
    }

    @Test
    fun `fast checks preserve compensation and fresh post-seek proof`() = runTest {
        val fixture = waitingProbe()
        fixture.rawPosition += 100
        fixture.routes = setOf(fixture.outputId)
        fixture.observation = AudioTrackObservation(1, AudioTrackPath.Endpoint(fixture.outputId))
        advanceTimeBy(21)
        runCurrent()

        assertEquals(12_000L, fixture.rawPosition)
        assertEquals(0f, fixture.volume)
        assertEquals(1, fixture.messages.size)
        fixture.observation = AudioTrackObservation(2, AudioTrackPath.Endpoint(fixture.speakerId))
        fixture.routes = setOf(fixture.speakerId)
        fixture.deliver(0)
        runCurrent()

        assertFalse(fixture.playWhenReady)
        assertNull(fixture.activeProbe())
        assertEquals(0, fixture.plays)
        assertEquals(1, fixture.messages.size, "wrong post-seek output cannot start another verification seek")
    }

    @Test
    fun `a late wrong or multiple route is refused without another callback`() = runTest {
        listOf(false, true).forEach { multiple ->
            val fixture = waitingProbe()
            fixture.routes = if (multiple) setOf(fixture.outputId, fixture.speakerId) else setOf(fixture.speakerId)
            advanceTimeBy(21)
            runCurrent()
            assertNull(fixture.activeProbe())
            assertFalse(fixture.playWhenReady)
            assertEquals(0, fixture.plays)
        }
    }

    @Test
    fun `repeated callbacks cannot extend checks and unknown output still times out`() = runTest {
        val fixture = waitingProbe()
        repeat(7) {
            fixture.playbackListeners.toList().forEach { listener -> listener.onIsPlayingChanged(true) }
            advanceTimeBy(50)
            runCurrent()
        }
        assertNotNull(fixture.activeProbe())
        assertEquals(0f, fixture.volume)
        val measurements = fixture.measurements
        advanceTimeBy(100)
        runCurrent()
        assertEquals(measurements, fixture.measurements, "the observation burst is exhausted")
        advanceTimeBy(51)
        runCurrent()
        assertNull(fixture.activeProbe())
        assertFalse(fixture.playWhenReady)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `pause cancels pending checks before a late good route`() = runTest {
        val fixture = waitingProbe()
        fixture.externalPause()
        runCurrent()
        val measurements = fixture.measurements
        fixture.routes = setOf(fixture.outputId)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(measurements, fixture.measurements)
        assertNull(fixture.activeProbe())
        assertFalse(fixture.playWhenReady)
        assertEquals(0, fixture.plays)
    }

    @Test
    fun `newer command book profile or attachment prevents stale observation`() = runTest {
        val changes: List<(MutedPositionEffectsTest.Fixture) -> Unit> = listOf(
            { it.command++ },
            { it.itemGeneration++ },
            { it.profileGeneration++ },
            { it.current = null },
        )
        changes.forEach { change ->
            val fixture = waitingProbe()
            val measurements = fixture.measurements
            change(fixture)
            fixture.routes = setOf(fixture.outputId)
            advanceTimeBy(301)
            runCurrent()
            assertEquals(measurements, fixture.measurements, "stale work must stop before querying a route")
            assertEquals(0f, fixture.volume)
            assertEquals(0, fixture.plays)
            fixture.cancel()
        }
    }

    @Test
    fun `a route read cannot finish a replacement probe or newer command`() = runTest {
        listOf(false, true).forEach { replace ->
            val fixture = waitingProbe()
            fixture.routes = setOf(fixture.outputId)
            fixture.observation = AudioTrackObservation(1, AudioTrackPath.Endpoint(fixture.outputId))
            fixture.onMeasurement = {
                fixture.onMeasurement = {}
                if (replace) {
                    fixture.cancel()
                    fixture.start()
                } else {
                    fixture.command++
                }
            }
            advanceTimeBy(21)
            runCurrent()
            assertNotNull(fixture.activeProbe())
            assertEquals(0f, fixture.volume)
            assertEquals(0, fixture.plays)
            assertTrue(fixture.messages.isEmpty())
            fixture.cancel()
        }
    }

    @Test
    fun `explicit car choice retires the pending headset burst`() = runTest {
        val fixture = waitingProbe()
        val oldProbe = fixture.activeProbe()
        fixture.router.commands.select(null, com.example.shelfplayer.core.model.playback.OutputDestination.Car)
        runCurrent()
        val measurements = fixture.measurements
        fixture.routes = setOf(fixture.outputId)
        advanceTimeBy(301)
        runCurrent()
        assertEquals(measurements, fixture.measurements)
        assertTrue(fixture.activeProbe() !== oldProbe)
        assertEquals(0, fixture.plays)
        assertEquals(
            OutputIntentOwner.Destination.Car,
            fixture.router.explicitSelection.value?.destination,
        )
    }

    private fun TestScope.waitingProbe(): MutedPositionEffectsTest.Fixture {
        val fixture = MutedPositionEffectsTest.Fixture(this)
        runCurrent()
        fixture.routes = null
        fixture.observation = null
        fixture.start()
        fixture.playbackListeners.toList().forEach { it.onIsPlayingChanged(true) }
        runCurrent()
        assertNotNull(fixture.activeProbe())
        assertEquals(0f, fixture.volume)
        return fixture
    }
}
