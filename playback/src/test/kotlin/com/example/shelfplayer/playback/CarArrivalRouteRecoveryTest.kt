package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/** #36 — the route operation itself must survive Android Auto clearing the preferred device mid-settle. */
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
    )

    @Test
    fun `transient Automatic fallback is followed by a second reassert of the captured headset`() = runBlocking {
        val outputs = MutableStateFlow(listOf(buds))
        val selected = MutableStateFlow<String?>(null)
        var attempts = 0

        val held = CarArrivalRouteRecovery(200.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { true },
        ) { id ->
            attempts += 1
            selected.value = id
            if (attempts == 1) {
                // The 18:15 drive: A2DP vanished during settle, which made the router fall back to Automatic,
                // then the same headset appeared again before the first route operation completed.
                outputs.value = emptyList()
                selected.value = null
                outputs.value = listOf(buds)
            }
            true
        }

        assertEquals(buds.id, held)
        assertEquals(2, attempts)
        assertEquals(buds.id, selected.value)
    }

    @Test
    fun `headset that never returns cannot become a resume route`() = runBlocking {
        val outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
        val selected = MutableStateFlow<String?>(null)
        var attempts = 0

        val held = CarArrivalRouteRecovery(50.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { true },
        ) {
            attempts += 1
            true
        }

        assertNull(held)
        assertEquals(0, attempts)
    }

    @Test
    fun `newer listener intent during settle prevents a retry and resume`() = runBlocking {
        val outputs = MutableStateFlow(listOf(buds))
        val selected = MutableStateFlow<String?>(null)
        var eligible = true
        var attempts = 0

        val held = CarArrivalRouteRecovery(100.milliseconds).secure(
            target = target,
            outputs = outputs,
            selectedId = selected,
            isStillEligible = { eligible },
        ) { id ->
            attempts += 1
            selected.value = id
            eligible = false
            delay(1)
            true
        }

        assertNull(held)
        assertEquals(1, attempts)
    }
}
