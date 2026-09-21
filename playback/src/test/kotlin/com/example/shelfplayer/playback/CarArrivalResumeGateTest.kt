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
 * Issue #36 — arrival and departure are separate correlations inside one exact-headset continuity owner.
 *
 * The historical filename is retained because Forgejo's constrained file API cannot rename in place.
 */
class CarArrivalResumeGateTest {
    private val buds = output("bluetooth:buds", "Buds")
    private val other = output("wired:headset", "Wired", DeviceKind.Wired, AudioOutputRole.Headset)
    private val speaker = output(
        id = "speaker:phone",
        name = "Phone speaker",
        kind = DeviceKind.Speaker,
        role = AudioOutputRole.Speaker,
    )
    private val car = output("car", "Car", DeviceKind.Car, AudioOutputRole.Car)

    @Test
    fun `measured focus loss followed by first car bind resumes only the pause-time headset`() {
        val owner = heardOnBuds()
        val gate = CarArrivalResumeGate()
        val focus = gate.onAudioFocusLoss(
            at = 10.seconds,
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds, car)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )

        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)
        assertEquals(CarArrivalResumeGate.Phase.Arrival, focus.phase)

        val bind = gate.onCarArrival(12.seconds, owner.currentGeneration, 0)
        val target = requireNotNull(bind.target)
        assertEquals(CarArrivalResumeGate.Status.Ready, bind.status)
        assertEquals(buds.id, target.outputId)

        val consumed = gate.consumeRecovery(
            target = target,
            currentGeneration = owner.currentGeneration,
            headsetId = buds.id,
            explicitSelectionSequence = 0,
        )
        assertTrue(consumed.accepted)
    }

    @Test
    fun `arrival target survives transient live route evidence loss`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)

        owner.onOutputsChanged(listOf(speaker), isPlaying = false)
        assertNull(owner.heardRoute)

        val bind = gate.onCarArrival(14.seconds, owner.currentGeneration, 0)

        assertEquals(CarArrivalResumeGate.Status.Ready, bind.status)
        assertEquals(buds.id, bind.target?.outputId)
    }

    @Test
    fun `deliberate pause after arrival focus loss cancels continuity`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)

        gate.cancelAll()

        val bind = gate.onCarArrival(12.seconds, owner.currentGeneration, 0)
        assertEquals(CarArrivalResumeGate.Status.Rejected, bind.status)
        assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, bind.reason)
    }

    @Test
    fun `newer explicit play after focus loss cancels pending recovery`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)

        gate.cancelPending()

        assertEquals(
            CarArrivalResumeGate.Reason.NoPendingFocusLoss,
            gate.onCarArrival(12.seconds, owner.currentGeneration, 0).reason,
        )
    }

    @Test
    fun `newer output choice before first bind rejects old arrival target`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)

        val bind = gate.onCarArrival(12.seconds, owner.currentGeneration, 1)

        assertEquals(CarArrivalResumeGate.Status.Rejected, bind.status)
        assertEquals(CarArrivalResumeGate.Reason.ExplicitSelectionChanged, bind.reason)
    }

    @Test
    fun `newer output choice during arrival recovery prevents final consume`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)
        val target = requireNotNull(gate.onCarArrival(12.seconds, owner.currentGeneration, 0).target)

        assertFalse(gate.isCurrent(target, owner.currentGeneration, 1))
        val consumed = gate.consumeRecovery(target, owner.currentGeneration, buds.id, 1)

        assertFalse(consumed.accepted)
        assertEquals(CarArrivalResumeGate.Reason.ExplicitSelectionChanged, consumed.reason)
    }

    @Test
    fun `book generation change before arrival bind rejects old target`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)

        owner.onBookChanged(hasBook = true)

        val bind = gate.onCarArrival(12.seconds, owner.currentGeneration, 0)
        assertEquals(CarArrivalResumeGate.Status.Rejected, bind.status)
        assertEquals(CarArrivalResumeGate.Reason.GenerationChanged, bind.reason)
    }

    @Test
    fun `book generation change during recovery invalidates resolved target`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)
        val target = requireNotNull(gate.onCarArrival(12.seconds, owner.currentGeneration, 0).target)

        owner.onBookChanged(hasBook = true)

        assertFalse(gate.isCurrent(target, owner.currentGeneration, 0))
        assertEquals(
            CarArrivalResumeGate.Reason.GenerationChanged,
            gate.consumeRecovery(target, owner.currentGeneration, buds.id, 0).reason,
        )
    }

    @Test
    fun `focus loss outside arrival pairing window cannot match a later car`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)

        val bind = gate.onCarArrival(17.seconds, owner.currentGeneration, 0)

        assertEquals(CarArrivalResumeGate.Status.Rejected, bind.status)
        assertEquals(CarArrivalResumeGate.Reason.OutsidePairingWindow, bind.reason)
    }

    @Test
    fun `a second controller bind cannot create another arrival recovery`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)
        val first = gate.onCarArrival(12.seconds, owner.currentGeneration, 0)
        val target = requireNotNull(first.target)

        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, buds.id, 0).accepted)

        val second = gate.onCarArrival(13.seconds, owner.currentGeneration, 0)
        assertEquals(CarArrivalResumeGate.Status.Rejected, second.status)
        assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, second.reason)
    }

    @Test
    fun `speaker or car evidence never creates arrival continuity`() {
        val gate = CarArrivalResumeGate()
        val speakerOwner = RouteHeardOwnership().apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(speaker.copy(isActive = true)))
        }

        val speakerFocus = gate.onAudioFocusLoss(
            at = 10.seconds,
            heardRoute = speakerOwner.heardRoute,
            headsetId = speakerOwner.headsetForCar(listOf(speaker, car)),
            currentGeneration = speakerOwner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Reason.NoQualifyingHeadset, speakerFocus.reason)

        val carOwner = RouteHeardOwnership().apply {
            onBookChanged(hasBook = true)
            onPlaybackObserved(listOf(car.copy(isActive = true)))
        }
        val carFocus = gate.onAudioFocusLoss(
            at = 11.seconds,
            heardRoute = carOwner.heardRoute,
            headsetId = carOwner.headsetForCar(listOf(car)),
            currentGeneration = carOwner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
        assertEquals(CarArrivalResumeGate.Reason.NoQualifyingHeadset, carFocus.reason)
    }

    @Test
    fun `departure focus loss before last disconnect resumes same playing headset`() {
        val owner = heardOnBuds()
        val gate = connectedOnBuds(owner)

        val focus = gate.onAudioFocusLoss(
            at = 20.seconds,
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds, car)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = true,
        )
        assertEquals(CarArrivalResumeGate.Phase.Departure, focus.phase)
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)

        val departure = gate.onCarDeparture(
            departedAt = 22.seconds,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )
        val target = requireNotNull(departure.target)
        assertEquals(CarArrivalResumeGate.Status.Ready, departure.status)
        assertEquals(CarArrivalResumeGate.Phase.Departure, target.phase)
        assertTrue(gate.consumeRecovery(target, owner.currentGeneration, buds.id, 0).accepted)
    }

    @Test
    fun `last disconnect before focus loss stays silent until physical ordering is known`() {
        val owner = heardOnBuds()
        val gate = connectedOnBuds(owner)

        val departure = gate.onCarDeparture(
            departedAt = 20.seconds,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )
        assertEquals(CarArrivalResumeGate.Status.Rejected, departure.status)
        assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, departure.reason)

        // A later focus loss is not retroactively labelled a departure event. With no car connected it can
        // only become a possible future-arrival candidate, and cannot authorize Play by itself.
        val focus = gate.onAudioFocusLoss(
            at = 22.seconds,
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )

        assertEquals(CarArrivalResumeGate.Phase.Arrival, focus.phase)
        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)
    }

    @Test
    fun `departure focus loss survives transient headset omission while car is still connected`() {
        val owner = heardOnBuds()
        val gate = connectedOnBuds(owner)

        owner.onOutputsChanged(emptyList(), isPlaying = true)
        assertNull(owner.heardRoute)

        val focus = gate.onAudioFocusLoss(
            at = 20.seconds,
            heardRoute = null,
            headsetId = null,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = true,
        )

        assertEquals(CarArrivalResumeGate.Status.Armed, focus.status)
        assertEquals(CarArrivalResumeGate.Phase.Departure, focus.phase)
        assertEquals(buds.id, focus.target?.outputId)
    }

    @Test
    fun `deliberately paused book cannot arm departure continuity`() {
        val owner = heardOnBuds()
        val gate = connectedOnBuds(owner)

        gate.cancelAll()

        val departure = gate.onCarDeparture(
            departedAt = 20.seconds,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )

        assertEquals(CarArrivalResumeGate.Status.Rejected, departure.status)
        assertEquals(CarArrivalResumeGate.Reason.NoPendingFocusLoss, departure.reason)
    }

    @Test
    fun `newer output selection defeats stable car-session evidence`() {
        val owner = heardOnBuds()
        val gate = connectedOnBuds(owner)

        val focus = gate.onAudioFocusLoss(
            at = 20.seconds,
            heardRoute = null,
            headsetId = null,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 1,
            carConnected = true,
        )

        assertEquals(CarArrivalResumeGate.Status.Rejected, focus.status)
        assertEquals(CarArrivalResumeGate.Reason.NoQualifyingHeadset, focus.reason)
    }

    @Test
    fun `departure pair outside bounded window does not resume`() {
        val owner = heardOnBuds()
        val gate = connectedOnBuds(owner)

        gate.onAudioFocusLoss(
            at = 20.seconds,
            heardRoute = owner.heardRoute,
            headsetId = buds.id,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = true,
        )

        val departure = gate.onCarDeparture(
            departedAt = 27.seconds,
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
        )

        assertEquals(CarArrivalResumeGate.Status.Rejected, departure.status)
        assertEquals(CarArrivalResumeGate.Reason.OutsidePairingWindow, departure.reason)
    }

    @Test
    fun `wrong secured headset cannot consume either lifecycle phase`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)
        val target = requireNotNull(gate.onCarArrival(12.seconds, owner.currentGeneration, 0).target)

        val consumed = gate.consumeRecovery(target, owner.currentGeneration, other.id, 0)

        assertFalse(consumed.accepted)
        assertEquals(CarArrivalResumeGate.Reason.WrongHeadset, consumed.reason)
    }

    @Test
    fun `non-focus pause path cannot silently inherit focus-loss policy`() {
        val owner = heardOnBuds()
        val gate = armedArrival(owner)

        gate.cancelAll()

        assertEquals(
            CarArrivalResumeGate.Reason.NoPendingFocusLoss,
            gate.onCarArrival(12.seconds, owner.currentGeneration, 0).reason,
        )
    }

    private fun armedArrival(owner: RouteHeardOwnership): CarArrivalResumeGate = CarArrivalResumeGate().also { gate ->
        gate.onAudioFocusLoss(
            at = 10.seconds,
            heardRoute = owner.heardRoute,
            headsetId = owner.headsetForCar(listOf(buds, car)),
            currentGeneration = owner.currentGeneration,
            explicitSelectionSequence = 0,
            carConnected = false,
        )
    }

    private fun connectedOnBuds(owner: RouteHeardOwnership): CarArrivalResumeGate =
        CarArrivalResumeGate().also { gate ->
            gate.observePlayingHeadset(
                heardRoute = owner.heardRoute,
                headsetId = owner.headsetForCar(listOf(buds, car)),
                currentGeneration = owner.currentGeneration,
                explicitSelectionSequence = 0,
                carConnected = true,
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
