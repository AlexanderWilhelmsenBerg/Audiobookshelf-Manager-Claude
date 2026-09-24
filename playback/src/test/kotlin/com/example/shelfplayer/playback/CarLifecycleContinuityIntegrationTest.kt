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

    private fun playingOwner(): RouteHeardOwnership = RouteHeardOwnership().apply {
        onBookChanged(hasBook = true)
        onPlaybackObserved(listOf(buds.copy(isActive = true)))
    }
}
