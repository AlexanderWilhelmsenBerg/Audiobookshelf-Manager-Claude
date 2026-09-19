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

/** Issue #36 — one measured focus loss may resume only its pause-time headset on the first car arrival. */
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
    fun `measured focus loss resolves and resumes the pause-time headset`() {
        val owner = heardOnBuds()
        val gate = armed(owner)
        var resumed: String? = null

        val target = gate.targetForCarArrival(12.seconds, owner.currentGeneration, 0)
        assertEquals(buds.id, target?.outputId)

        assertTrue(
            gate.resumeForCarArrival(
                target = requireNotNull(target),
                currentGeneration = owner.currentGeneration,
                headsetId = buds.id,
                explicitSelectionSequence = 0,
            ) { resumed = it },
        )
        assertEquals(buds.id, resumed)
    }

    @Test
    fun `transient route evidence loss does not erase the captured target`() {
        val owner = heardOnBuds()
        val gate = armed(owner)

        owner.onOutputsChanged(listOf(speaker), isPlaying = false)
        assertNull(owner.heardRoute)

        val target = gate.targetForCarArrival(14.seconds, owner.currentGeneration, 0)
        assertEquals(buds.id, target?.outputId)
        assertTrue(gate.isCurrent(requireNotNull(target), owner.currentGeneration, 0))
    }

    @Test
    fun `a deliberate pause or play invalidation cancels continuity`() {
        val owner = heardOnBuds()
        val gate = armed(owner)
        gate.cancel()

        assertNull(gate.targetForCarArrival(12.seconds, owner.currentGeneration, 0))
    }

    @Test
    fun `newer explicit destination prevents even resolving the old target`() {
        val owner = heardOnBuds()
        val gate = armed(owner)

        assertNull(gate.targetForCarArrival(12.seconds, owner.currentGeneration, 1))
    }

    @Test
    fun `newer explicit choice after target resolution prevents resume`() {
        val owner = heardOnBuds()
        val gate = armed(owner)
        val target = requireNotNull(gate.targetForCarArrival(12.seconds, owner.currentGeneration, 0))

        assertFalse(
            gate.resumeForCarArrival(
                target = target,
                currentGeneration = owner.currentGeneration,
                headsetId = buds.id,
                explicitSelectionSequence = 1,
            ) { error("must not resume") },
        )
    }

    @Test
    fun `another book generation cannot resolve the old target`() {
        val owner = heardOnBuds()
        val gate = armed(owner)
        owner.onBookChanged(hasBook = true)

        assertNull(gate.targetForCarArrival(12.seconds, owner.currentGeneration, 0))
    }

    @Test
    fun `wrong secured headset cannot consume a valid target`() {
        val owner = heardOnBuds()
        val gate = armed(owner)
        val target = requireNotNull(gate.targetForCarArrival(12.seconds, owner.currentGeneration, 0))

        assertFalse(
            gate.resumeForCarArrival(
                target = target,
                currentGeneration = owner.currentGeneration,
                headsetId = speaker.id,
                explicitSelectionSequence = 0,
            ) { error("must not resume") },
        )
    }

    @Test
    fun `pairing window is measured at first bind and not later route completion`() {
        val owner = heardOnBuds()
        val gate = armed(owner)
        var resumed: String? = null

        val target = requireNotNull(gate.targetForCarArrival(15.seconds, owner.currentGeneration, 0))
        assertTrue(
            gate.resumeForCarArrival(
                target = target,
                currentGeneration = owner.currentGeneration,
                headsetId = buds.id,
                explicitSelectionSequence = 0,
            ) { resumed = it },
        )
        assertEquals(buds.id, resumed)
    }

    @Test
    fun `focus loss outside the first-bind window is discarded`() {
        val owner = heardOnBuds()
        val gate = armed(owner)

        assertNull(gate.targetForCarArrival(17.seconds, owner.currentGeneration, 0))
    }

    @Test
    fun `focus loss without heard headset ownership creates no target`() {
        val owner = RouteHeardOwnership().apply { onBookChanged(hasBook = true) }
        val gate = CarArrivalResumeGate()
        gate.onAudioFocusLoss(
            10.seconds,
            owner.heardRoute,
            owner.headsetForCar(listOf(buds, car)),
            explicitSelectionSequence = 0,
        )

        assertNull(gate.targetForCarArrival(12.seconds, owner.currentGeneration, 0))
    }

    @Test
    fun `one focus loss can resume only once`() {
        val owner = heardOnBuds()
        val gate = armed(owner)
        var resumes = 0
        val target = requireNotNull(gate.targetForCarArrival(12.seconds, owner.currentGeneration, 0))

        assertTrue(
            gate.resumeForCarArrival(
                target = target,
                currentGeneration = owner.currentGeneration,
                headsetId = buds.id,
                explicitSelectionSequence = 0,
            ) { resumes += 1 },
        )
        assertFalse(
            gate.resumeForCarArrival(
                target = target,
                currentGeneration = owner.currentGeneration,
                headsetId = buds.id,
                explicitSelectionSequence = 0,
            ) { resumes += 1 },
        )
        assertEquals(1, resumes)
    }

    private fun armed(owner: RouteHeardOwnership): CarArrivalResumeGate = CarArrivalResumeGate().also { gate ->
        gate.onAudioFocusLoss(
            10.seconds,
            owner.heardRoute,
            owner.headsetForCar(listOf(buds, car)),
            explicitSelectionSequence = 0,
        )
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
