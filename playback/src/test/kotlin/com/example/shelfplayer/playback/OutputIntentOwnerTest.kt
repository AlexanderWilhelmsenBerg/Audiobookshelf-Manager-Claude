package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputIntentOwnerTest {
    @Test
    fun `returning to the same target cannot revive an older claim and runtime release clears context`() {
        val owner = OutputIntentOwner()
        owner.onBookChanged(1)
        val old = owner.keepHere("A", 1, 1)
        owner.select("B", OutputIntentOwner.Destination.Output)
        val latest = owner.keepHere("A", 1, 3)

        owner.markUnverified(old)
        assertEquals(latest, owner.state.value.lock)
        owner.releaseRuntime()
        owner.activateProof(latest, 1, 3)
        assertNull(owner.state.value.lock)
        assertNull(owner.state.value.activeBookGeneration)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `lock publication is atomic and lock-only updates do not repeat selection emissions`() = runTest {
        val owner = OutputIntentOwner()
        val snapshots = mutableListOf<OutputIntentOwner.Snapshot>()
        val selections = mutableListOf<OutputIntentOwner.Selection?>()
        val locks = mutableListOf<OutputIntentOwner.Lock?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { snapshots += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.selection.collect { selections += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.lock.collect { locks += it } }

        val claim = owner.keepHere("A", 1, 3)
        owner.markWaiting(claim)
        owner.markWaiting(requireNotNull(owner.lock.value))

        assertEquals(3, snapshots.size, "there must be no intermediate unlocked selection")
        assertEquals(claim.selectionSequence, snapshots[1].selection?.sequence)
        assertEquals(claim, snapshots[1].lock)
        assertEquals(2, selections.size, "status-only updates must not re-emit listener intent")
        assertEquals(3, locks.size, "equal status is conflated")
        assertEquals(snapshots.last().lock, owner.lock.value, "value is fresh without an async projection")
    }

    @Test
    fun `explicit choices supersede lock and advance one sequence`() {
        val owner = OutputIntentOwner()
        val lock = owner.keepHere("live-output", generation = 4, effectEpoch = 2)
        assertEquals(1L, lock.selectionSequence)

        owner.select(null, OutputIntentOwner.Destination.Automatic)

        assertEquals(2L, owner.state.value.selection?.sequence)
        assertNull(owner.state.value.lock)
    }

    @Test
    fun `proof invalidation retains logical target but drops generation and player claim`() {
        val owner = OutputIntentOwner()
        val lock = owner.keepHere("ephemeral", generation = 8, effectEpoch = 4)

        owner.invalidateProof(lock)

        val waiting = owner.state.value.lock
        assertEquals(OutputIntentOwner.LockPhase.Waiting, waiting?.phase)
        assertEquals(lock.outputId, waiting?.outputId)
        assertNull(waiting?.bookGeneration)
    }

    @Test
    fun `Automatic release records newer intent and clears the runtime claim`() {
        val owner = OutputIntentOwner()
        val lock = owner.keepHere("ephemeral", generation = 2, effectEpoch = 1)

        owner.select(null, OutputIntentOwner.Destination.Automatic)

        assertNull(owner.state.value.lock)
        assertEquals(lock.selectionSequence + 1, owner.state.value.selection?.sequence)
    }

    @Test
    fun `late status from replaced or released lock cannot mutate current state`() {
        val owner = OutputIntentOwner()
        val old = owner.keepHere("A", generation = 3, effectEpoch = 1)
        owner.select("B", OutputIntentOwner.Destination.Output)
        val current = owner.keepHere("B", generation = 3, effectEpoch = 2)

        owner.markUnverified(old)
        assertEquals(current, owner.state.value.lock)

        owner.select(null, OutputIntentOwner.Destination.Automatic)
        owner.markWaiting(current)
        assertNull(owner.state.value.lock)
    }

    @Test
    fun `Automatic release clears on positive move disconnect or new book`() {
        val owner = OutputIntentOwner()
        owner.select("A", OutputIntentOwner.Destination.Output)
        owner.select(null, OutputIntentOwner.Destination.Automatic, releasedRouteId = "A")

        assertEquals(true, owner.isReleasedRoute("A"))
        owner.reportHeardRoute("B")
        assertEquals(false, owner.isReleasedRoute("A"))

        owner.select(null, OutputIntentOwner.Destination.Automatic, releasedRouteId = "A")
        owner.onOutputDisconnected(emptySet())
        assertEquals(false, owner.isReleasedRoute("A"))
        owner.select(null, OutputIntentOwner.Destination.Automatic, releasedRouteId = "A")
        owner.onBookChanged(2)
        assertEquals(false, owner.isReleasedRoute("A"))
    }

    @Test
    fun `selection consumer watermark rejects repeated and mismatched observations`() {
        val intent = OutputIntentOwner()
        val routeOwner = RouteHeardOwnership(intent).apply { onBookChanged(true) }
        val selection = intent.select("A", OutputIntentOwner.Destination.Output)
        val output = com.example.shelfplayer.core.model.playback.AudioOutput(
            id = "A",
            displayName = "A",
            kind = com.example.shelfplayer.core.model.playback.DeviceKind.Bluetooth,
        )

        assertEquals(
            true,
            routeOwner.onExplicitSelection("A", listOf(output), true, selection.sequence, selection.destination),
        )
        assertEquals(
            false,
            routeOwner.onExplicitSelection("A", listOf(output), true, selection.sequence, selection.destination),
        )
        assertEquals(
            false,
            routeOwner.onExplicitSelection("wrong", listOf(output), true, selection.sequence, selection.destination),
        )
    }
}
