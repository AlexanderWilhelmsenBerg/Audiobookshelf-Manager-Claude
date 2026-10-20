package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * PLAY-002 / ROUTE-001 / ROUTE-002: event-order matrix over the real policy and route recovery.
 * Android callback delivery and actual AudioTrack routing still require physical acceptance.
 */
class PlaybackContinuityInvariantMatrixTest {
    private enum class Order { InterruptionBeforeCar, CarBeforeInterruption }
    private enum class Interruption { FocusLoss, BecomingNoisy }
    private enum class Invalidation { ExplicitPause, BookGenerationChanged, OutputSelectionChanged }

    private data class Recovery(
        val gate: CarArrivalResumeGate,
        val target: CarArrivalResumeGate.Target,
        val generation: Long?,
    )

    private val headset = AudioOutput(
        id = "bluetooth:original-headset",
        displayName = "Original headset",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
    )
    private val speaker = AudioOutput(
        id = "speaker:phone",
        displayName = "Phone speaker",
        kind = DeviceKind.Speaker,
        role = AudioOutputRole.Speaker,
        isActive = true,
    )

    private fun readyRecovery(order: Order, interruption: Interruption): Recovery {
        val owner = RouteHeardOwnership().apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(headset.copy(isActive = true)))
        }
        val generation = owner.currentGeneration
        val gate = CarArrivalResumeGate()
        gate.observePlayingHeadset(owner.heardRoute, headset.id, generation, 0)

        fun interrupt(at: Duration, carConnected: Boolean): CarArrivalResumeGate.Decision {
            val route = if (carConnected) null else owner.heardRoute
            val id = if (carConnected) null else headset.id
            return when (interruption) {
                Interruption.FocusLoss -> gate.onAudioFocusLoss(at, route, id, generation, 0, carConnected)
                Interruption.BecomingNoisy -> gate.onAudioBecomingNoisy(at, route, id, generation, 0, carConnected)
            }
        }

        val decision = when (order) {
            Order.InterruptionBeforeCar -> {
                assertEquals(CarArrivalResumeGate.Status.Armed, interrupt(10.seconds, false).status)
                gate.onCarArrival(11.seconds, generation, 0)
            }

            Order.CarBeforeInterruption -> {
                assertEquals(CarArrivalResumeGate.Status.Armed, gate.onCarArrival(10.seconds, generation, 0).status)
                interrupt(11.seconds, true)
            }
        }
        assertEquals(CarArrivalResumeGate.Status.Ready, decision.status, "$order / $interruption")
        assertEquals(headset.id, decision.target?.outputId)
        return Recovery(gate, requireNotNull(decision.target), generation)
    }

    @Test
    fun callbackOrderOnlyAuthorizesOriginalHeadsetOnce() = runBlocking {
        for (order in Order.entries) {
            for (event in Interruption.entries) {
                val item = readyRecovery(order, event)
                var reassertions = 0
                val held = CarArrivalRouteRecovery(100.milliseconds).secure(
                    target = item.target,
                    outputs = MutableStateFlow(listOf(headset.copy(isActive = true))),
                    selectedId = MutableStateFlow<String?>(null),
                    isStillEligible = { item.gate.isCurrent(item.target, item.generation, 0) },
                ) {
                    reassertions++
                    true
                }
                val label = "$order / $event"
                assertEquals(headset.id, held, label)
                assertEquals(0, reassertions, "actively routed headset must not be reasserted: $label")
                assertTrue(item.gate.consumeRecovery(item.target, item.generation, held, 0).accepted, label)
                assertFalse(item.gate.consumeRecovery(item.target, item.generation, held, 0).accepted, label)
                assertEquals(
                    CarArrivalResumeGate.Status.Rejected,
                    item.gate.onCarArrival(12.seconds, item.generation, 0).status,
                    "duplicate car callback: $label",
                )
            }
        }
    }

    @Test
    fun stalePauseBookOrSelectionNeverReassertsOrResumes() = runBlocking {
        for (order in Order.entries) {
            for (event in Interruption.entries) {
                for (invalidation in Invalidation.entries) {
                    val item = readyRecovery(order, event)
                    val generation = if (invalidation == Invalidation.BookGenerationChanged) {
                        requireNotNull(item.generation) + 1
                    } else {
                        item.generation
                    }
                    val selection = if (invalidation == Invalidation.OutputSelectionChanged) 1L else 0L
                    if (invalidation == Invalidation.ExplicitPause) item.gate.cancelAll()
                    var reassertions = 0
                    val held = CarArrivalRouteRecovery(100.milliseconds).secure(
                        target = item.target,
                        outputs = MutableStateFlow(listOf(headset.copy(isActive = true))),
                        selectedId = MutableStateFlow<String?>(null),
                        isStillEligible = { item.gate.isCurrent(item.target, generation, selection) },
                    ) {
                        reassertions++
                        true
                    }
                    val label = "$order / $event / $invalidation"
                    assertNull(held, label)
                    assertEquals(0, reassertions, "invalidated attempt may not touch routing: $label")
                    assertFalse(item.gate.consumeRecovery(item.target, generation, held, selection).accepted, label)
                }
            }
        }
    }

    @Test
    fun competingSpeakerEvidenceNeverAuthorizesHeadsetRecovery() = runBlocking {
        for (order in Order.entries) {
            for (event in Interruption.entries) {
                val item = readyRecovery(order, event)
                val selected = MutableStateFlow<String?>(headset.id)
                val held = CarArrivalRouteRecovery(80.milliseconds).secure(
                    target = item.target,
                    outputs = MutableStateFlow(listOf(headset, speaker)),
                    selectedId = selected,
                    isStillEligible = { item.gate.isCurrent(item.target, item.generation, 0) },
                ) { id ->
                    selected.value = id
                    true
                }
                val label = "$order / $event"
                assertNull(held, "preferred-device request does not override reported speaker: $label")
                assertFalse(item.gate.consumeRecovery(item.target, item.generation, held, 0).accepted, label)
            }
        }
    }
}
