package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #36 — the car may resume only the focus-loss pause tied to #11's current headset ownership.
 */
class CarArrivalResumeGateTest {
    private val buds = output("bluetooth:buds", "Buds")
    private val speaker = output(
        id = "speaker:phone",
        name = "Phone speaker",
        kind = DeviceKind.Speaker,
        role = AudioOutputRole.Speaker,
    )
    private val car = output("car", "Car", DeviceKind.Car, AudioOutputRole.Car)

    @Test
    fun `measured focus loss followed by car arrival resumes the owned headset`() {
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        var resumed: String? = null

        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, car)))

        assertTrue(
            gate.resumeForCarArrival(
                12.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, car)),
            ) { resumed = it },
        )
        assertEquals(buds.id, resumed)
    }

    @Test
    fun `a deliberate pause after focus loss cancels continuity`() {
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, car)))

        gate.cancel()

        assertFalse(
            gate.resumeForCarArrival(
                12.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, car)),
            ) { error("must not resume") },
        )
    }

    @Test
    fun `phone speaker becoming the explicit destination blocks resume`() {
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, speaker, car)))

        owner.onExplicitSelection(
            outputId = speaker.id,
            outputs = listOf(buds, speaker),
            isPlaying = false,
        )

        assertFalse(
            gate.resumeForCarArrival(
                12.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, speaker, car)),
            ) { error("must not resume") },
        )
    }

    @Test
    fun `car appearing never becomes the resume destination`() {
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        var resumed: String? = null
        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, car)))

        owner.onOutputsChanged(listOf(buds, car.copy(isActive = true)), isPlaying = false)

        assertTrue(
            gate.resumeForCarArrival(
                12.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, car)),
            ) { resumed = it },
        )
        assertEquals(buds.id, resumed)
    }

    @Test
    fun `stale route ownership from another book cannot resume`() {
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, car)))

        owner.onBookChanged(hasBook = true)

        assertFalse(
            gate.resumeForCarArrival(
                12.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, car)),
            ) { error("must not resume") },
        )
    }

    @Test
    fun `a newer explicit headset choice outranks the paused route`() {
        val other = output("wired:headset", "Wired", DeviceKind.Wired, AudioOutputRole.Headset)
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, other, car)))

        owner.onExplicitSelection(
            outputId = other.id,
            outputs = listOf(buds, other),
            isPlaying = false,
        )

        assertFalse(
            gate.resumeForCarArrival(
                12.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, other, car)),
            ) { error("must not resume") },
        )
    }

    @Test
    fun `an old focus loss is not paired with a later car arrival`() {
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, car)))

        assertFalse(
            gate.resumeForCarArrival(
                17.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, car)),
            ) { error("must not resume") },
        )
    }

    @Test
    fun `a focus loss without heard headset ownership cannot create continuity`() {
        val owner = RouteHeardOwnership().apply { onBookChanged(hasBook = true) }
        val gate = CarArrivalResumeGate()

        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, car)))

        assertFalse(
            gate.resumeForCarArrival(
                12.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, car)),
            ) { error("must not resume") },
        )
    }

    @Test
    fun `one focus loss can resume only once`() {
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        var resumes = 0
        gate.onAudioFocusLoss(10.seconds, owner.heardRoute, owner.headsetForCar(listOf(buds, car)))

        assertTrue(
            gate.resumeForCarArrival(
                12.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, car)),
            ) { resumes += 1 },
        )
        assertFalse(
            gate.resumeForCarArrival(
                13.seconds,
                owner.heardRoute,
                owner.headsetForCar(listOf(buds, car)),
            ) { resumes += 1 },
        )
        assertEquals(1, resumes)
    }

    private fun heardOnBuds(): RouteHeardOwnership = RouteHeardOwnership().apply {
        onBookChanged(hasBook = true)
        onPlaybackObserved(listOf(buds.copy(isActive = true)))
    }

    private fun output(
        id: String,
        name: String,
        kind: DeviceKind = DeviceKind.Bluetooth,
        role: AudioOutputRole = AudioOutputRole.Ambiguous,
    ) = AudioOutput(id = id, displayName = name, kind = kind, role = role)
}
