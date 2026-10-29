package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * #36 integration regression: exercise the ownership → lifecycle pairing → route recovery → final consume chain.
 *
 * PlaybackService is framework/Hilt-owned, so its Media3 callback itself remains a physical/controller
 * boundary. These tests keep the service's exact ordering while using the same concrete policy objects it
 * calls, rather than proving each object in isolation only.
 */
class CarLifecycleContinuityIntegrationTest {
    private val buds = AudioOutput(
        id = "bluetooth:buds",
        displayName = "Buds",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
    )

    @Test
    fun `car first bind cannot consume focus candidate when only preference is known`() = runBlocking {
        val owner = playingOwner()
        val gate = CarArrivalResumeGate()
        gate.observePlayingHeadset(owner.heardRoute, buds.id, owner.currentGeneration, 0)
        val focus = gate.onAudioFocusLoss(
            at = 10.seconds,
            heardRoute = owner.heardRoute,
            headsetId = buds.id,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)
        val target = requireNotNull(gate.onCarArrival(12.seconds, owner.currentGeneration, 0).target)
        val outputs = MutableStateFlow(listOf(buds))
        val selected = MutableStateFlow<String?>(null)
        val held = CarArrivalRouteRecovery(90.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { gate.isCurrent(target, owner.currentGeneration, 0) },
        ) { id ->
            selected.value = id
            true
        }
        assertEquals(null, held)
        assertTrue(!gate.consumeRecovery(target, owner.currentGeneration, held, 0).accepted)
    }

    @Test
    fun `noisy-only before first car bind recovers the original headset once`() = runBlocking {
        val owner = playingOwner()
        val gate = CarArrivalResumeGate()
        gate.observePlayingHeadset(owner.heardRoute, buds.id, owner.currentGeneration, 0)
        val noisy = gate.onAudioBecomingNoisy(
            at = 10.seconds,
            heardRoute = owner.heardRoute,
            headsetId = buds.id,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Status.Armed, noisy.status)
        owner.onOutputsChanged(emptyList(), isPlaying = false)
        val arrival = gate.onCarArrival(12.seconds, owner.currentGeneration, 0)
        assertEquals(CarArrivalResumeGate.Status.Ready, arrival.status)
        val target = requireNotNull(arrival.target)
        val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
        val selected = MutableStateFlow<String?>(null)
        val reappears = launch {
            delay(10)
            outputs.value = listOf(buds.copy(isActive = true))
        }
        val held = CarArrivalRouteRecovery(200.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { gate.isCurrent(target, owner.currentGeneration, 0) },
        ) { id ->
            selected.value = id
            true
        }
        reappears.join()
        assertEquals(buds.id, held)
        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, held, 0).accepted)
        assertTrue(!gate.consumeRecovery(target, owner.currentGeneration, held, 0).accepted)
    }

    @Test
    fun `arrival route flap still reaches one exact-headset Play authorization`() = runBlocking {
        val owner = playingOwner()
        val gate = CarArrivalResumeGate()
        val focus = gate.onAudioFocusLoss(
            at = 10.seconds,
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)

        // Android Auto begins settling and temporarily omits the A2DP endpoint before the first controller bind.
        owner.onOutputsChanged(emptyList(), isPlaying = false)
        val target = requireNotNull(
            gate.onCarArrival(
                arrivedAt = 14.seconds,
                currentGeneration = owner.currentGeneration,
                explicitSelectionSequence = 0,
            ).target,
        )

        val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
        val selected = MutableStateFlow<String?>(null)
        val returns = launch {
            delay(10)
            outputs.value = listOf(buds.copy(isActive = true))
        }
        val held = CarArrivalRouteRecovery(200.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { gate.isCurrent(target, owner.currentGeneration, 0) },
        ) { id ->
            selected.value = id
            true
        }
        returns.join()

        assertEquals(buds.id, held)
        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, held, 0).accepted)
    }

    @Test
    fun `departure focus loss plus final disconnect survives route omission and authorizes same headset`() {
        runBlocking {
            val owner = playingOwner()
            val gate = CarArrivalResumeGate()
            gate.observePlayingHeadset(
                heardRoute = owner.heardRoute,
                headsetId = owner.headsetForCar(listOf(buds)),
                currentGeneration = owner.currentGeneration,
                explicitSelectionSequence = 0,
            )

            val focus = gate.onAudioFocusLoss(
                at = 20.seconds,
                heardRoute = owner.heardRoute,
                headsetId = buds.id,
                currentGeneration = owner.currentGeneration,
                explicitSelectionSequence = 0,
                carConnected = true,
            )
            assertEquals(CarArrivalResumeGate.Phase.Departure, focus.phase)

            owner.onOutputsChanged(emptyList(), isPlaying = false)
            val target = requireNotNull(
                gate.onCarDeparture(
                    departedAt = 22.seconds,
                    currentGeneration = owner.currentGeneration,
                    explicitSelectionSequence = 0,
                ).target,
            )

            val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
            val selected = MutableStateFlow<String?>(null)
            val returns = launch {
                delay(10)
                outputs.value = listOf(buds.copy(isActive = true))
            }
            val held = CarArrivalRouteRecovery(200.milliseconds).secure(
                target = target,
                outputs = outputs,
                selectedId = selected,
                isStillEligible = { gate.isCurrent(target, owner.currentGeneration, 0) },
            ) { id ->
                selected.value = id
                true
            }
            returns.join()

            assertEquals(buds.id, held)
            assertTrue(gate.consumeRecovery(target, owner.currentGeneration, held, 0).accepted)
        }
    }

    @Test
    fun `projection departure authorizes recovery without a MediaSession disconnect`() = runBlocking {
        val owner = playingOwner()
        val gate = CarArrivalResumeGate()
        gate.observePlayingHeadset(
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )

        val focus = gate.onAudioFocusLoss(
            at = 20.seconds,
            heardRoute = owner.heardRoute,
            headsetId = buds.id,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = true,
        )
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)

        // The physical projection edge is the boundary. No MediaSession onDisconnected is involved.
        val target = requireNotNull(
            gate.onProjectionDeparture(
                departedAt = 22.seconds,
                currentGeneration = owner.currentGeneration,
                explicitSelectionSequence = 0,
            ).target,
        )
        val outputs = MutableStateFlow(listOf(buds.copy(isActive = true)))
        val selected = MutableStateFlow<String?>(null)
        val held = CarArrivalRouteRecovery(200.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { gate.isCurrent(target, owner.currentGeneration, 0) },
        ) { id ->
            selected.value = id
            true
        }

        assertEquals(buds.id, held)
        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, held, 0).accepted)

        // A much later legacy-controller disconnect cannot consume the already-resolved transition again.
        val staleDisconnect = gate.onCarDeparture(
            departedAt = 50.seconds,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )
        assertEquals(CarArrivalResumeGate.Status.Rejected, staleDisconnect.status)
        assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, staleDisconnect.reason)
    }

    /**
     * PLAY-002 / ROUTE-002 / #128: onConnect can be delivered before Media3 reports the focus loss.
     * Test the service's real ownership -> gate -> route -> final Play-authorization components together.
     * This is intentionally RED on the old focus-before-bind-only state machine.
     */
    @Test
    fun `first car bind before focus loss can recover the exact previously heard headset once`() = runBlocking {
        val owner = playingOwner()
        val gate = CarArrivalResumeGate()
        gate.observePlayingHeadset(
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )

        val firstArrival = gate.onCarArrival(
            arrivedAt = 10.seconds,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )
        assertEquals(
            CarArrivalResumeGate.Status.Armed,
            firstArrival.status,
            "a first bind must retain a bounded arrival boundary when there was no earlier focus callback",
        )

        assertEquals(
            CarArrivalResumeGate.Status.Rejected,
            gate.onCarArrival(10.seconds + 200.milliseconds, owner.currentGeneration, 0).status,
            "a second controller or projection signal must not renew the original boundary",
        )
        val focus = gate.onAudioFocusLoss(
            at = 11.seconds,
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = true,
        )
        assertEquals(CarArrivalResumeGate.Phase.Arrival, focus.phase)
        assertEquals(CarArrivalResumeGate.Status.Ready, focus.status)
        val target = requireNotNull(focus.target)

        val selected = MutableStateFlow<String?>(null)
        val reassertions = mutableListOf<String>()
        val held = CarArrivalRouteRecovery(200.milliseconds).secure(
            target = target,
            outputs = MutableStateFlow(listOf(buds.copy(isActive = true))),
            selectedId = selected,
            isStillEligible = { gate.isCurrent(target, owner.currentGeneration, 0) },
        ) { id ->
            reassertions += id
            selected.value = id
            true
        }
        assertTrue(reassertions.isEmpty(), "a positively active headset needs no route reassertion")
        assertEquals(buds.id, held)
        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, held, 0).accepted)
        assertEquals(
            CarArrivalResumeGate.Status.Rejected,
            gate.onCarArrival(12.seconds, owner.currentGeneration, 0).status,
            "a duplicate car boundary must not authorize another Play",
        )
    }

    @Test
    fun `paused book with retained route owner cannot be started by focus then controller`() {
        val owner = playingOwner()
        val gate = CarArrivalResumeGate()
        gate.observePlayingHeadset(owner.heardRoute, buds.id, owner.currentGeneration, 0)

        gate.cancelAll() // Explicit Pause, but RouteHeardOwnership still remembers the old headset.
        val focus = gate.onAudioFocusLoss(
            at = 10.seconds,
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Status.Rejected, focus.status)
        val arrival = gate.onCarArrival(12.seconds, owner.currentGeneration, 0)
        assertEquals(CarArrivalResumeGate.Status.Rejected, arrival.status)
    }

    @Test
    fun `headset Pause then car reconnect cannot recover stale route from physical trace 2026-10-13`() {
        val owner = playingOwner()
        val gate = CarArrivalResumeGate()

        // The owner had explicitly selected the headset while the book was genuinely playing.
        val selection = owner.intentOwner.select(buds.id, OutputIntentOwner.Destination.Output)
        owner.onExplicitSelection(
            selection,
            listOf(buds),
            isAudible = true,
        )
        gate.observePlayingHeadset(owner.heardRoute, buds.id, owner.currentGeneration, 1)
        assertEquals(buds.id, owner.heardRoute?.outputId)

        // MediaSession keyCode=85 Pause: this cancels the gate but does not erase route-heard ownership.
        gate.cancelAll()
        owner.onOutputsChanged(listOf(buds), isPlaying = false)
        assertEquals(buds.id, owner.heardRoute?.outputId)

        // Twenty seconds later Media3 reports focus loss without any intervening observed Play.
        val focus = gate.onAudioFocusLoss(
            at = 40.seconds,
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 1,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Phase.Arrival, focus.phase)
        assertEquals(CarArrivalResumeGate.Status.Rejected, focus.status)
        assertEquals(CarArrivalResumeGate.Reason.NoFreshPlaybackAfterIntentReset, focus.reason)

        // Physical first Gearhead bind arrived 2.7s later, after a transient route omission.
        owner.onOutputsChanged(emptyList(), isPlaying = false)
        val bind = gate.onCarArrival(42_723.milliseconds, owner.currentGeneration, 1)
        assertEquals(CarArrivalResumeGate.Status.Rejected, bind.status)
        assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, bind.reason)
        assertEquals(null, bind.target)
    }

    @Test
    fun `late focus loss during an established car session cannot borrow old arrival authority`() {
        val owner = playingOwner()
        val gate = CarArrivalResumeGate()
        gate.observePlayingHeadset(
            heardRoute = owner.heardRoute,
            headsetId = buds.id,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )
        gate.onCarArrival(10.seconds, owner.currentGeneration, 0)
        gate.onCarArrival(15.seconds, owner.currentGeneration, 0) // A duplicate must not extend the six-second limit.

        val unrelatedFocus = gate.onAudioFocusLoss(
            at = 17.seconds,
            heardRoute = owner.heardRoute,
            headsetId = buds.id,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = true,
        )
        assertEquals(
            CarArrivalResumeGate.Phase.Departure,
            unrelatedFocus.phase,
            "a focus event after the bounded first-arrival window must never become an arrival resume",
        )
        assertEquals(CarArrivalResumeGate.Status.Armed, unrelatedFocus.status)
    }

    @Test
    fun `first car boundary cannot revive a deliberately paused or newly selected route`() {
        val owner = playingOwner()
        val pausedGate = CarArrivalResumeGate()
        pausedGate.observePlayingHeadset(owner.heardRoute, buds.id, owner.currentGeneration, 0)
        pausedGate.onCarArrival(10.seconds, owner.currentGeneration, 0)
        pausedGate.cancelAll()

        val pausedFocus = pausedGate.onAudioFocusLoss(
            at = 11.seconds,
            heardRoute = null,
            headsetId = null,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = true,
        )
        assertEquals(CarArrivalResumeGate.Status.Rejected, pausedFocus.status)
        assertEquals(CarArrivalResumeGate.Reason.NoFreshPlaybackAfterIntentReset, pausedFocus.reason)

        val reselectedGate = CarArrivalResumeGate()
        reselectedGate.observePlayingHeadset(owner.heardRoute, buds.id, owner.currentGeneration, 0)
        reselectedGate.onCarArrival(10.seconds, owner.currentGeneration, 0)
        val newSelectionFocus = reselectedGate.onAudioFocusLoss(
            at = 11.seconds,
            heardRoute = null,
            headsetId = null,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 1,
            carConnected = true,
        )
        assertEquals(
            CarArrivalResumeGate.Status.Rejected,
            newSelectionFocus.status,
            "a newer explicit output selection must defeat an older headset boundary",
        )
    }

    private fun playingOwner(): RouteHeardOwnership = RouteHeardOwnership(OutputIntentOwner()).apply {
        onBookChanged(hasBook = true)
        onPlaybackObserved(listOf(buds.copy(isActive = true)))
    }
}
