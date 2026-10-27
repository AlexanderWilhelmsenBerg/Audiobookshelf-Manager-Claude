package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Phase 2 / S10-S13: controlled suspensions and virtual-clock advancement against
 * the production continuity coordinator. The tests never simulate a second player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaybackScenarioAsyncEffectsTest {
    private val headset = AudioOutput(
        id = "bluetooth:synthetic-original",
        displayName = "Synthetic headset",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
        isActive = false,
    )

    private data class Ready(
        val coordinator: CarContinuityRecoveryCoordinator,
        val target: CarArrivalResumeGate.Target,
    )

    private fun ready(): Ready {
        val owner = RouteHeardOwnership(OutputIntentOwner()).apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset.copy(isActive = true)))
        }
        val coordinator = CarContinuityRecoveryCoordinator(
            CarArrivalResumeGate(),
            CarArrivalRouteRecovery(250.milliseconds),
        )
        val inputs = CarContinuityRecoveryCoordinator.Inputs(
            heardRoute = owner.heardRoute,
            headsetId = headset.id,
            generation = owner.currentGeneration,
            selectionSequence = 0,
            carConnected = false,
        )
        coordinator.observePlayingHeadset(inputs)
        assertEquals(
            CarArrivalResumeGate.Status.Armed,
            coordinator.platformInterruption(10.seconds, inputs, focusLoss = true).status,
        )
        val decision = coordinator.carArrival(11.seconds, owner.currentGeneration, 0)
        assertEquals(CarArrivalResumeGate.Status.Ready, decision.status)
        return Ready(coordinator, requireNotNull(decision.target))
    }

    @Test
    fun newerSelectionDuringSuspendedRouteRequestCannotResume() = runTest {
        val (coordinator, target) = ready()
        val acknowledge = CompletableDeferred<Boolean>()
        val outputs = MutableStateFlow(listOf(headset))
        val selected = MutableStateFlow<String?>(null)
        var selectionSequence = 0L
        val requests = mutableListOf<String>()
        val pending = async {
            coordinator.secure(
                target = target,
                outputs = outputs,
                selectedId = selected,
                currentGeneration = { target.generation },
                selectionSequence = { selectionSequence },
            ) { id ->
                requests += id
                acknowledge.await()
            }
        }
        runCurrent()
        assertEquals(listOf(headset.id), requests)
        selectionSequence++
        outputs.value = listOf(headset.copy(isActive = true))
        selected.value = headset.id
        acknowledge.complete(true)
        runCurrent()
        assertNull(pending.await(), "late acknowledgment must not resurrect a superseded output intent")
        assertFalse(
            coordinator.assess(
                target,
                CarContinuityPlayPrecheck.Proceed,
                target.generation,
                1,
                headset.id,
            ).mayPlay,
        )
        assertEquals(1, requests.size)
    }

    @Test
    fun bookReplacementDuringSuspendedRouteRequestCannotResume() = runTest {
        val (coordinator, target) = ready()
        val acknowledge = CompletableDeferred<Boolean>()
        val outputs = MutableStateFlow(listOf(headset))
        var currentGeneration = target.generation
        var requests = 0
        val pending = async {
            coordinator.secure(
                target = target,
                outputs = outputs,
                selectedId = MutableStateFlow<String?>(null),
                currentGeneration = { currentGeneration },
                selectionSequence = { 0 },
            ) {
                requests++
                acknowledge.await()
            }
        }
        runCurrent()
        assertEquals(1, requests)
        currentGeneration++
        coordinator.cancelAll()
        outputs.value = listOf(headset.copy(isActive = true))
        acknowledge.complete(true)
        runCurrent()
        assertNull(pending.await())
        assertFalse(
            coordinator.assess(
                target,
                CarContinuityPlayPrecheck.Proceed,
                currentGeneration,
                0,
                headset.id,
            ).mayPlay,
        )
    }

    @Test
    fun sameNamedReplacementEndpointNeverBorrowsCapturedRoute() = runTest {
        val (coordinator, target) = ready()
        val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
        var requests = 0
        val pending = async {
            coordinator.secure(
                target = target,
                outputs = outputs,
                selectedId = MutableStateFlow<String?>(null),
                currentGeneration = { target.generation },
                selectionSequence = { 0 },
            ) {
                requests++
                true
            }
        }
        runCurrent()
        outputs.value = listOf(
            headset.copy(id = "bluetooth:synthetic-reconnected-different-endpoint", isActive = true),
        )
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertNull(pending.await())
        assertEquals(0, requests, "matching display names are never stable physical identity")
        assertFalse(coordinator.assess(target, CarContinuityPlayPrecheck.Proceed, target.generation, 0, null).mayPlay)
    }

    @Test
    fun preferredDeviceRequestCannotProvePlayingRouteBeforeTimeout() = runTest {
        val (coordinator, target) = ready()
        val selected = MutableStateFlow<String?>(null)
        val events = mutableListOf<CarArrivalRouteRecovery.Event>()
        var requests = 0
        val pending = async {
            coordinator.secure(
                target = target,
                outputs = MutableStateFlow(listOf(headset)),
                selectedId = selected,
                currentGeneration = { target.generation },
                selectionSequence = { 0 },
                onEvent = events::add,
            ) { id ->
                requests++
                selected.value = id
                true
            }
        }
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertNull(pending.await())
        assertTrue(requests > 0)
        assertTrue(CarArrivalRouteRecovery.Event.TimedOut in events)
        assertFalse(CarArrivalRouteRecovery.Event.Secured in events)
    }

    @Test
    fun concurrentSecureCallsStillAuthorizeAtMostOnePlayerEffect() = runTest {
        val (coordinator, target) = ready()
        val outputs = MutableStateFlow(listOf(headset.copy(isActive = true)))
        var requests = 0
        val first = async {
            coordinator.secure(
                target = target,
                outputs = outputs,
                selectedId = MutableStateFlow<String?>(null),
                currentGeneration = { target.generation },
                selectionSequence = { 0 },
            ) {
                requests++
                true
            }
        }
        val second = async {
            coordinator.secure(
                target = target,
                outputs = outputs,
                selectedId = MutableStateFlow<String?>(null),
                currentGeneration = { target.generation },
                selectionSequence = { 0 },
            ) {
                requests++
                true
            }
        }
        runCurrent()
        assertEquals(headset.id, first.await())
        assertEquals(headset.id, second.await())
        assertEquals(0, requests, "already active route does not need a repeated preference request")
        val attempts = listOf(first, second).map {
            coordinator.assess(
                target,
                CarContinuityPlayPrecheck.Proceed,
                target.generation,
                0,
                it.getCompleted(),
            ).mayPlay
        }
        assertEquals(listOf(true, false), attempts)
    }
}
