package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** The real output preference lane, not a parallel route-selection policy or fake player. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RoutePreferenceEffectsTest {
    @Test
    fun `queued commands with the same requested sink keep only the newest generation`() = runTest {
        val lane = RoutePreferenceEffects(StandardTestDispatcher(testScheduler))
        val effects = mutableListOf<String>()
        val first = lane.supersede()
        val old = launch { lane.run(first) { effects += "old-car" } }
        val second = lane.supersede()
        val latest = launch { lane.run(second) { effects += "new-car" } }

        runCurrent()
        old.join()
        latest.join()
        assertEquals(listOf("new-car"), effects)
        assertFalse(lane.isCurrent(first))
        assertTrue(lane.isCurrent(second))
    }

    @Test
    fun `player reattachment invalidates a suspended route-settle observation`() = runTest {
        val lane = RoutePreferenceEffects(StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<String>()
        val oldEpoch = lane.supersede()
        val requested = async {
            lane.run(oldEpoch) {
                observed += "old-request"
                lane.afterSettle(oldEpoch, 400.milliseconds) { observed += "old-settled" }
            }
        }
        runCurrent()
        assertEquals(listOf("old-request"), observed)
        val nextEpoch = lane.supersede()
        lane.run(nextEpoch) { observed += "new-request" }
        advanceTimeBy(450)
        runCurrent()
        requested.await()

        assertEquals(listOf("old-request", "new-request"), observed)
    }

    @Test
    fun `callback effect executes on the supplied player dispatcher with no duplicate owner`() = runTest {
        val lane = RoutePreferenceEffects(StandardTestDispatcher(testScheduler))
        val owningThread = Thread.currentThread()
        val epochs = mutableListOf<Long>()
        val ticket = lane.supersede()
        val task = launch {
            lane.run(ticket) {
                assertEquals(owningThread, Thread.currentThread())
                epochs += ticket
            }
        }
        runCurrent()
        task.join()
        assertEquals(listOf(ticket), epochs)
    }

    @Test
    fun `queued request cannot revive after a newer silent protection generation`() = runTest {
        val lane = RoutePreferenceEffects(StandardTestDispatcher(testScheduler))
        var preferredWrites = 0
        val stale = lane.supersede()
        val job = launch { lane.run(stale) { preferredWrites++ } }
        lane.supersede() // The real router's protectPlayingHeadset makes this transition.
        runCurrent()
        job.join()
        assertEquals(0, preferredWrites)
    }
}
