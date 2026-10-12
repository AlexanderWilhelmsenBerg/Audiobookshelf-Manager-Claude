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
            outputs.value = listOf(buds)
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
                outputs.value = listOf(buds)
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
        val outputs = MutableStateFlow(listOf(buds))
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
            outputs = MutableStateFlow(listOf(buds)),
            selectedId = selected,
            isStillEligible = { gate.isCurrent(target, owner.currentGeneration, 0) },
        ) { id ->
            reassertions += id
            selected.value = id
            true
        }
        assertEquals(listOf(buds.id), reassertions)
        assertEquals(buds.id, held)
        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, held, 0).accepted)
        assertEquals(
            CarArrivalResumeGate.Status.Rejected,
            gate.onCarArrival(12.seconds, owner.currentGeneration, 0).status,
            "a duplicate car boundary must not authorize another Play",
        )
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
        assertEquals(CarArrivalResumeGate.Reason.NoQualifyingHeadset, pausedFocus.reason)

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

    private fun playingOwner(): RouteHeardOwnership = RouteHeardOwnership().apply {
        onBookChanged(hasBook = true)
        onPlaybackObserved(listOf(buds.copy(isActive = true)))
    }
}
