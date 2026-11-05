package com.example.shelfplayer.playback

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputTargetProofTest {
    private val targets = OutputProofTargets(setOf("headset", "speaker", "car"), setOf("car"), projection = true)

    @Test
    fun `an exact live endpoint includes explicit Speaker but refuses an ineligible target`() {
        for (id in listOf("headset", "speaker")) {
            val request = request(OutputIntentOwner.Destination.Output, id)
            val observed = AudioTrackObservation(4, AudioTrackPath.Endpoint(id))
            assertEquals(
                MutedProbeCompletion.Verified,
                OutputTargetProof.evaluate(request, observed, observed, targets),
            )
            assertEquals(
                MutedProbeCompletion.Refused,
                OutputTargetProof.evaluate(request, observed, observed, targets.copy(eligible = emptySet())),
            )
        }
    }

    @Test
    fun `RemoteSubmix proves only explicit Car delegation with positive current projection`() {
        val car = request(OutputIntentOwner.Destination.Car)
        val observed = AudioTrackObservation(4, AudioTrackPath.RemoteSubmix)
        assertEquals(MutedProbeCompletion.Delegated, OutputTargetProof.evaluate(car, observed, observed, targets))
        assertNull(OutputTargetProof.evaluate(car, observed, observed, targets.copy(projection = false)))
        assertEquals(
            MutedProbeCompletion.Refused,
            OutputTargetProof.evaluate(
                request(OutputIntentOwner.Destination.Output, "headset"),
                observed,
                observed,
                targets,
            ),
        )
    }

    @Test
    fun `Car cannot be inferred from an ambiguous mapped endpoint or a media policy preference`() {
        val request = request(OutputIntentOwner.Destination.Car)
        val ambiguous = AudioTrackObservation(4, AudioTrackPath.Endpoint("headset"))
        assertEquals(MutedProbeCompletion.Refused, OutputTargetProof.evaluate(request, ambiguous, ambiguous, targets))
        val car = AudioTrackObservation(4, AudioTrackPath.Endpoint("car"))
        assertEquals(MutedProbeCompletion.Verified, OutputTargetProof.evaluate(request, car, car, targets))
        assertNull(OutputTargetProof.evaluate(request, car, car, targets.copy(projection = false)))
    }

    @Test
    fun `the original route may settle while muted but never counts as destination success`() {
        val previous = AudioTrackPath.Endpoint("headset")
        val request = request(OutputIntentOwner.Destination.Car).copy(originalPath = previous)
        val old = AudioTrackObservation(4, previous)
        assertNull(OutputTargetProof.evaluate(request, old, old, targets))
        val wrong = AudioTrackObservation(4, AudioTrackPath.Endpoint("speaker"))
        assertEquals(MutedProbeCompletion.Refused, OutputTargetProof.evaluate(request, wrong, wrong, targets))
    }

    @Test
    fun `unknown multiple changed paths and changed observer epochs cannot authorize release`() {
        val request = request(OutputIntentOwner.Destination.Output, "headset")
        val current = AudioTrackObservation(4, AudioTrackPath.Endpoint("headset"))
        assertNull(OutputTargetProof.evaluate(request, null, current, targets))
        assertNull(OutputTargetProof.evaluate(request, current, current.copy(observerEpoch = 5), targets))
        val unresolved = current.copy(path = AudioTrackPath.Unresolved)
        assertNull(OutputTargetProof.evaluate(request, unresolved, unresolved, targets))
        val multiple = current.copy(path = AudioTrackPath.Multiple)
        assertEquals(MutedProbeCompletion.Refused, OutputTargetProof.evaluate(request, current, multiple, targets))
    }

    private fun request(destination: OutputIntentOwner.Destination, id: String? = null) =
        OutputIntentOwner.TransferRequest(OutputIntentOwner.Selection(1, id, destination), 2, 3, null)
}
