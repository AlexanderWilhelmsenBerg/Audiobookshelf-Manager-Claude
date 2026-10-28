package com.example.shelfplayer.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OutputTransferIntentTest {
    @Test
    fun `lost proof after success can restore only that request's retained prior lock`() {
        val owner = lockedOwner()
        val prior = assertNotNull(owner.lock.value)
        val prepared = carRequest(owner)
        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying))
        assertTrue(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Delegated))
        assertNull(owner.lock.value)

        assertTrue(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Failed, prior))
        assertEquals(prior.copy(phase = OutputIntentOwner.LockPhase.Waiting), owner.lock.value)
        val newer = owner.select(null, OutputIntentOwner.Destination.Automatic)
        assertFalse(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Failed, prior))
        assertNull(owner.lock.value, "late rejection cannot resurrect a lock after newer release")
        assertEquals(newer, owner.selection.value)
    }

    @Test
    fun `preparation publishes nothing and verified delegation alone releases the prior lock`() {
        val owner = lockedOwner()
        val original = owner.state.value
        val prepared = carRequest(owner)

        assertSame(original, owner.state.value)
        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying))
        assertEquals(original.lock, owner.lock.value)
        assertEquals(prepared.request.selection, owner.selection.value)
        assertTrue(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Delegated))
        assertNull(owner.lock.value)
        assertEquals(prepared.request.selection.sequence, owner.selection.value?.sequence)
    }

    @Test
    fun `failure retains the prior logical target while rejecting a late success`() {
        val owner = lockedOwner()
        val prepared = carRequest(owner)
        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying))

        assertTrue(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Failed))

        assertEquals("A", owner.lock.value?.outputId)
        assertEquals(OutputIntentOwner.LockPhase.Waiting, owner.lock.value?.phase)
        assertEquals(OutputIntentOwner.Destination.Car, owner.selection.value?.destination)
        assertFalse(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Delegated))
        assertNotNull(owner.lock.value)
    }

    @Test
    fun `a newer reservation or listener choice cannot be overwritten by an older prepared transfer`() {
        val owner = lockedOwner()
        val old = carRequest(owner)
        val newer = owner.prepareTransfer("B", OutputIntentOwner.Destination.Output, 5, null)

        assertFalse(owner.publishTransfer(old, OutputIntentOwner.TransferPhase.Verifying))
        assertTrue(owner.publishTransfer(newer, OutputIntentOwner.TransferPhase.Waiting))
        assertFalse(owner.publishTransfer(old, OutputIntentOwner.TransferPhase.Verifying))
        assertFalse(owner.transitionTransfer(old.request, OutputIntentOwner.TransferPhase.Verified))
        assertEquals("B", owner.selection.value?.outputId)
        assertEquals("A", owner.lock.value?.outputId)
    }

    @Test
    fun `returning to the same destination cannot revive an older success`() {
        val owner = lockedOwner()
        val first = carRequest(owner)
        assertTrue(owner.publishTransfer(first, OutputIntentOwner.TransferPhase.Verifying))
        val middle = owner.prepareTransfer("B", OutputIntentOwner.Destination.Output, 5, null)
        assertTrue(owner.publishTransfer(middle, OutputIntentOwner.TransferPhase.Verifying))
        val latest = carRequest(owner, epoch = 6)
        assertTrue(owner.publishTransfer(latest, OutputIntentOwner.TransferPhase.Verifying))

        assertFalse(owner.transitionTransfer(first.request, OutputIntentOwner.TransferPhase.Delegated))
        assertTrue(owner.transitionTransfer(latest.request, OutputIntentOwner.TransferPhase.Delegated))
        assertEquals(latest.request.selection.sequence, owner.selection.value?.sequence)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transfer publication is atomic and proof updates emit no second selection`() = runTest {
        val owner = lockedOwner()
        val selections = mutableListOf<OutputIntentOwner.Selection?>()
        val snapshots = mutableListOf<OutputIntentOwner.Snapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.selection.collect { selections += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { owner.state.collect { snapshots += it } }
        val prepared = carRequest(owner)

        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying))
        assertTrue(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Delegated))

        assertEquals(2, selections.size)
        assertEquals(3, snapshots.size)
        assertNotNull(snapshots[1].lock, "requested Car must not publish an intermediate unlocked state")
        assertEquals(prepared.request, snapshots[1].transfer?.request)
        assertNull(snapshots[2].lock)
    }

    @Test
    fun `existing selection verification reuses its intent sequence and fresh recovery context`() {
        val owner = lockedOwner()
        val prepared = carRequest(owner)
        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying))
        assertTrue(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Delegated))
        val resumed = assertNotNull(owner.prepareExistingTransfer(5, AudioTrackPath.RemoteSubmix))

        assertEquals(prepared.request.selection, resumed.request.selection)
        assertEquals(AudioTrackPath.RemoteSubmix, resumed.request.originalPath)
        assertTrue(owner.publishTransfer(resumed, OutputIntentOwner.TransferPhase.Verifying))
        assertFalse(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Failed))
    }

    @Test
    fun `book Stop Automatic and runtime boundaries retire older proof without extra intent`() {
        val owner = lockedOwner()
        val prepared = carRequest(owner)
        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying))
        val sequence = owner.selection.value?.sequence

        owner.onBookChanged(8)
        assertEquals(OutputIntentOwner.TransferPhase.Waiting, owner.state.value.transfer?.phase)
        assertEquals(8, owner.state.value.transfer?.request?.bookGeneration)
        assertFalse(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Delegated))
        owner.onPlaybackStopped()
        assertNull(owner.lock.value)
        assertEquals(sequence, owner.selection.value?.sequence)
        owner.select(null, OutputIntentOwner.Destination.Automatic)
        assertNull(owner.state.value.transfer)
        owner.releaseRuntime()
        assertNull(owner.state.value.activeBookGeneration)
    }

    @Test
    fun `explicit transfer clears Automatic release bookkeeping only when published`() {
        val owner = OutputIntentOwner()
        owner.onBookChanged(7)
        owner.select(null, OutputIntentOwner.Destination.Automatic, releasedRouteId = "A")
        val prepared = owner.prepareTransfer("A", OutputIntentOwner.Destination.Output, 4, null)
        assertTrue(owner.isReleasedRoute("A"))

        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Waiting))

        assertFalse(owner.isReleasedRoute("A"))
    }

    @Test
    fun `publication cannot bypass verification by supplying a successful phase`() {
        val owner = lockedOwner()
        val prepared = carRequest(owner)
        val original = owner.state.value

        assertFailsWith<IllegalArgumentException> {
            owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Delegated)
        }

        assertSame(original, owner.state.value)
    }

    @Test
    fun `an empty queue cannot commit a verified transfer`() {
        val owner = OutputIntentOwner()
        val prepared = carRequest(owner)
        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying))

        assertFalse(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Delegated))
    }

    @Test
    fun `reverification of the kept selection retains and renews its existing lock`() {
        val owner = lockedOwner()
        val selection = owner.selection.value
        val prepared = assertNotNull(owner.prepareExistingTransfer(5, AudioTrackPath.Endpoint("A")))
        assertTrue(owner.publishTransfer(prepared, OutputIntentOwner.TransferPhase.Verifying))

        assertTrue(owner.transitionTransfer(prepared.request, OutputIntentOwner.TransferPhase.Verified))

        assertEquals(selection, owner.selection.value)
        assertEquals("A", owner.lock.value?.outputId)
        assertEquals(OutputIntentOwner.LockPhase.Active, owner.lock.value?.phase)
        assertEquals(5L, owner.lock.value?.effectEpoch)
    }

    private fun lockedOwner() = OutputIntentOwner().apply {
        onBookChanged(7)
        keepHere("A", 7, 3)
    }

    private fun carRequest(owner: OutputIntentOwner, epoch: Long = 4) = owner.prepareTransfer(
        null,
        OutputIntentOwner.Destination.Car,
        epoch,
        AudioTrackPath.Endpoint("A"),
    )
}
