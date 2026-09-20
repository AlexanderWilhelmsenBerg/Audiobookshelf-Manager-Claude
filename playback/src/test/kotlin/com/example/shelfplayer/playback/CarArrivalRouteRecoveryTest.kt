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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** #36 — the route operation must survive Android Auto clearing the exact preferred device mid-settle. */
class CarArrivalRouteRecoveryTest {
    private val buds = AudioOutput(
        id = "bluetooth:buds",
        displayName = "Buds",
        kind = DeviceKind.Bluetooth,
        role = AudioOutputRole.Ambiguous,
    )
    private val target = CarArrivalResumeGate.Target(
        outputId = buds.id,
        generation = 7,
        explicitSelectionSequence = 3,
        phase = CarArrivalResumeGate.Phase.Arrival,
    )

    @Test
    fun `transient Automatic fallback is followed by a second reassert of captured headset`() = runBlocking {
        val outputs = MutableStateFlow(listOf(buds))
        val selected = MutableStateFlow<String?>(null)
        val events = mutableListOf<CarArrivalRouteRecovery.Event>()
        var attempts = 0

        val held = CarArrivalRouteRecovery(200.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { true },
            onEvent = events::add,
        ) { id ->
            attempts += 1
            selected.value = id
            if (attempts == 1) {
                outputs.value = emptyList()
                selected.value = null
                outputs.value = listOf(buds)
            }
            true
        }

        assertEquals(buds.id, held)
        assertEquals(2, attempts)
        assertEquals(buds.id, selected.value)
        assertTrue(CarArrivalRouteRecovery.Event.PreferenceLost in events)
        assertTrue(CarArrivalRouteRecovery.Event.Secured in events)
    }

    @Test
    fun `headset that never returns cannot become a resume route`() = runBlocking {
        val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
        val selected = MutableStateFlow<String?>(null)
        val events = mutableListOf<CarArrivalRouteRecovery.Event>()
        var attempts = 0

        val held = CarArrivalRouteRecovery(50.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { true },
            onEvent = events::add,
        ) {
            attempts += 1
            true
        }

        assertNull(held)
        assertEquals(0, attempts)
        assertTrue(CarArrivalRouteRecovery.Event.TargetAbsent in events)
        assertTrue(CarArrivalRouteRecovery.Event.TimedOut in events)
    }

    @Test
    fun `target reappearance is diagnosed before exact headset is secured`() = runBlocking {
        val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
        val selected = MutableStateFlow<String?>(null)
        val events = mutableListOf<CarArrivalRouteRecovery.Event>()

        val reappearance = launch {
            // This models Android publishing the same A2DP endpoint again during the bounded platform settle.
            delay(10)
            outputs.value = listOf(buds)
        }
        val held = CarArrivalRouteRecovery(200.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { true },
            onEvent = events::add,
        ) { id ->
            selected.value = id
            true
        }
        reappearance.join()

        assertEquals(buds.id, held)
        assertTrue(CarArrivalRouteRecovery.Event.TargetAbsent in events)
        assertTrue(CarArrivalRouteRecovery.Event.TargetReturned in events)
        assertTrue(CarArrivalRouteRecovery.Event.Secured in events)
    }

    @Test
    fun `newer listener intent during settle prevents retry and resume`() = runBlocking {
        val outputs = MutableStateFlow(listOf(buds))
        val selected = MutableStateFlow<String?>(null)
        val events = mutableListOf<CarArrivalRouteRecovery.Event>()
        var eligible = true
        var attempts = 0

        val held = CarArrivalRouteRecovery(100.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { eligible },
            onEvent = events::add,
        ) { id ->
            attempts += 1
            selected.value = id
            eligible = false
            true
        }

        assertNull(held)
        assertEquals(1, attempts)
        assertTrue(CarArrivalRouteRecovery.Event.EligibilityLost in events)
    }
}
