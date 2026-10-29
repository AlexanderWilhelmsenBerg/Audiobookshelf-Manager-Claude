package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.DeviceKind
import com.example.shelfplayer.core.model.playback.OutputDestination
import com.example.shelfplayer.core.model.playback.OutputMeasurement
import com.example.shelfplayer.core.model.playback.OutputTransferStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutputPresentationProjectionTest {
    @Test
    fun previousLockIsRecoveryContextForTheNewRequest() {
        val owner = OutputIntentOwner()
        owner.onBookChanged(1L)
        owner.keepHere("old", 1L, 1L)
        assertEquals(
            false,
            OutputPresentationProjection.present(owner.state.value, null, emptyList(), false).hasRetainedPreviousLock,
        )
        val request = owner.prepareTransfer("new", OutputIntentOwner.Destination.Output, 2L, null)
        owner.publishTransfer(request, OutputIntentOwner.TransferPhase.Waiting)

        val presentation = OutputPresentationProjection.present(owner.state.value, null, emptyList(), false)

        assertEquals(true, presentation.hasRetainedPreviousLock)
        assertEquals("new", presentation.requestedOutputId)
        assertEquals("old", owner.state.value.lock?.outputId)
    }

    @Test
    fun onlyCurrentAudioTrackObservationPresentsAMeasuredEndpoint() {
        val requested =
            snapshot(OutputIntentOwner.Destination.Output, "headset", OutputIntentOwner.TransferPhase.Verified)
        val predicted = output("headset").copy(
            isActive = true,
            activitySource = AudioOutputActivitySource.FrameworkMediaPolicy,
        )

        val projection = OutputPresentationProjection.present(
            requested,
            observation = null,
            outputs = listOf(predicted),
            projection = false,
        )

        assertEquals(OutputDestination.Output, projection.requested)
        assertEquals(OutputMeasurement.Unavailable, projection.measurement)
        assertNull(projection.measuredOutputId)
        assertEquals(OutputTransferStatus.Waiting, projection.transfer)
    }

    @Test
    fun matchingObservedEndpointVerifiesButUnknownAndMultiplePathsNeverChooseADevice() {
        val request =
            snapshot(OutputIntentOwner.Destination.Output, "headset", OutputIntentOwner.TransferPhase.Verified)
        val matched = OutputPresentationProjection.present(
            request,
            observation(AudioTrackPath.Endpoint("headset")),
            outputs = listOf(output("headset")),
            projection = false,
        )
        val unresolved = OutputPresentationProjection.present(
            request,
            observation(AudioTrackPath.Unresolved),
            outputs = listOf(output("headset")),
            projection = false,
        )
        val multiple = OutputPresentationProjection.present(
            request,
            observation(AudioTrackPath.Multiple),
            outputs = listOf(output("headset")),
            projection = false,
        )

        assertEquals(OutputMeasurement.Endpoint, matched.measurement)
        assertEquals("headset", matched.measuredOutputId)
        assertEquals(OutputTransferStatus.Verified, matched.transfer)
        assertEquals(OutputMeasurement.Unresolved, unresolved.measurement)
        assertNull(unresolved.measuredOutputId)
        assertEquals(OutputTransferStatus.Waiting, unresolved.transfer)
        assertEquals(OutputMeasurement.Multiple, multiple.measurement)
        assertNull(multiple.measuredOutputId)
        assertEquals(OutputTransferStatus.Waiting, multiple.transfer)
    }

    @Test
    fun measuredEndpointMissingFromInventoryOrDuplicatedIsUnresolvedAndNotVerified() {
        val request =
            snapshot(OutputIntentOwner.Destination.Output, "headset", OutputIntentOwner.TransferPhase.Verified)
        val observed = observation(AudioTrackPath.Endpoint("headset"))
        val missing = OutputPresentationProjection.present(
            request,
            observed,
            outputs = emptyList(),
            eligibleEndpointIds = emptySet(),
            projection = false,
        )
        val duplicate = output("headset")
        val ambiguous = OutputPresentationProjection.present(
            request,
            observed,
            outputs = listOf(duplicate, duplicate.copy(displayName = "Duplicate")),
            eligibleEndpointIds = setOf("headset"),
            projection = false,
        )

        assertEquals(OutputMeasurement.Unresolved, missing.measurement)
        assertNull(missing.measuredOutputId)
        assertEquals(OutputTransferStatus.Waiting, missing.transfer)
        assertEquals(OutputMeasurement.Unresolved, ambiguous.measurement)
        assertNull(ambiguous.measuredOutputId)
        assertEquals(OutputTransferStatus.Waiting, ambiguous.transfer)
    }

    @Test
    fun verifiedAndDelegatedSuccessBecomeWaitingWhenCurrentEvidenceDisappears() {
        val verified =
            snapshot(OutputIntentOwner.Destination.Output, "headset", OutputIntentOwner.TransferPhase.Verified)
        val delegated = snapshot(OutputIntentOwner.Destination.Car, null, OutputIntentOwner.TransferPhase.Delegated)
        val verifiedLost = OutputPresentationProjection.present(
            verified,
            observation = null,
            outputs = emptyList(),
            projection = false,
        )
        val delegatedLostTrack = OutputPresentationProjection.present(
            delegated,
            observation = null,
            outputs = emptyList(),
            projection = true,
        )
        val delegatedLostProjection = OutputPresentationProjection.present(
            delegated,
            observation(AudioTrackPath.RemoteSubmix),
            outputs = emptyList(),
            projection = false,
        )
        val delegatedCurrent = OutputPresentationProjection.present(
            delegated,
            observation(AudioTrackPath.RemoteSubmix),
            outputs = emptyList(),
            projection = true,
        )

        assertEquals(OutputTransferStatus.Waiting, verifiedLost.transfer)
        assertEquals(OutputTransferStatus.Waiting, delegatedLostTrack.transfer)
        assertEquals(OutputTransferStatus.Waiting, delegatedLostProjection.transfer)
        assertEquals(OutputTransferStatus.Delegated, delegatedCurrent.transfer)
        assertEquals(OutputMeasurement.RemoteSubmix, delegatedCurrent.measurement)
        assertNull(delegatedCurrent.measuredOutputId)
    }

    @Test
    fun waitingVerifyingAndFailedPhasesRemainVisibleWithoutEndpointProof() {
        listOf(
            OutputIntentOwner.TransferPhase.Waiting to OutputTransferStatus.Waiting,
            OutputIntentOwner.TransferPhase.Verifying to OutputTransferStatus.Verifying,
            OutputIntentOwner.TransferPhase.Failed to OutputTransferStatus.Failed,
        ).forEach { (phase, expected) ->
            val projected = OutputPresentationProjection.present(
                snapshot(OutputIntentOwner.Destination.Car, null, phase),
                observation(AudioTrackPath.Multiple),
                outputs = emptyList(),
                projection = false,
            )
            assertEquals(expected, projected.transfer)
            assertEquals(OutputMeasurement.Multiple, projected.measurement)
        }
    }

    @Test
    fun carAndAutomaticRemainDistinctWithNullEndpointIds() {
        val car = OutputPresentationProjection.present(
            snapshot(OutputIntentOwner.Destination.Car, null),
            null,
            emptyList(),
            false,
        )
        val automatic = OutputPresentationProjection.present(
            snapshot(OutputIntentOwner.Destination.Automatic, null),
            null,
            emptyList(),
            false,
        )

        assertEquals(OutputDestination.Car, car.requested)
        assertEquals(OutputDestination.Automatic, automatic.requested)
        assertNull(car.requestedOutputId)
        assertNull(automatic.requestedOutputId)
    }

    @Test
    fun aVerifiedCarEndpointNeedsCurrentCarProjectionAndRoleEvidence() {
        val carRequest = snapshot(OutputIntentOwner.Destination.Car, null, OutputIntentOwner.TransferPhase.Verified)
        val carOutput = output("car", AudioOutputRole.Car)
        val measured = observation(AudioTrackPath.Endpoint("car"))
        val connected = OutputPresentationProjection.present(carRequest, measured, listOf(carOutput), projection = true)
        val disconnected = OutputPresentationProjection.present(
            carRequest,
            measured,
            listOf(carOutput),
            projection = false,
        )
        val nonCarEndpoint = OutputPresentationProjection.present(
            carRequest,
            measured,
            listOf(output("car", AudioOutputRole.Other)),
            projection = true,
        )

        assertEquals(OutputTransferStatus.Verified, connected.transfer)
        assertEquals(OutputTransferStatus.Waiting, disconnected.transfer)
        assertEquals(OutputTransferStatus.Waiting, nonCarEndpoint.transfer)
    }

    @Test
    fun staleTransferSnapshotCannotProjectVerifiedOrDelegatedSuccess() {
        val verifiedRequest =
            snapshot(OutputIntentOwner.Destination.Output, "headset", OutputIntentOwner.TransferPhase.Verified)
        val verifiedTransfer = requireNotNull(verifiedRequest.transfer)
        val supersededSelection = verifiedRequest.copy(
            selection = verifiedRequest.selection?.copy(sequence = 2L),
        )
        val wrongGeneration = verifiedRequest.copy(activeBookGeneration = 2L)
        val carRequest = snapshot(OutputIntentOwner.Destination.Car, null, OutputIntentOwner.TransferPhase.Delegated)
        val delegationWithSupersededSelection = carRequest.copy(
            selection = carRequest.selection?.copy(sequence = 2L),
        )
        val measured = observation(AudioTrackPath.Endpoint("headset"))
        val remote = observation(AudioTrackPath.RemoteSubmix)

        val staleSelection = OutputPresentationProjection.present(
            supersededSelection,
            measured,
            outputs = listOf(output("headset")),
            eligibleEndpointIds = setOf("headset"),
            projection = false,
        )
        val staleGeneration = OutputPresentationProjection.present(
            wrongGeneration,
            measured,
            outputs = listOf(output("headset")),
            eligibleEndpointIds = setOf("headset"),
            projection = false,
        )
        val staleDelegation = OutputPresentationProjection.present(
            delegationWithSupersededSelection,
            remote,
            outputs = emptyList(),
            eligibleEndpointIds = emptySet(),
            projection = true,
        )

        assertEquals(verifiedTransfer.phase, OutputIntentOwner.TransferPhase.Verified)
        assertEquals(OutputTransferStatus.Waiting, staleSelection.transfer)
        assertEquals(OutputTransferStatus.Waiting, staleGeneration.transfer)
        assertEquals(OutputTransferStatus.Waiting, staleDelegation.transfer)
    }

    private fun snapshot(
        destination: OutputIntentOwner.Destination,
        outputId: String?,
        phase: OutputIntentOwner.TransferPhase? = null,
    ): OutputIntentOwner.Snapshot {
        val owner = OutputIntentOwner()
        owner.onBookChanged(1L)
        val selection = owner.select(outputId, destination)
        val transfer = phase?.let {
            val request = OutputIntentOwner.TransferRequest(selection, 1L, 1L, originalPath = null)
            OutputIntentOwner.Transfer(request, it)
        }
        return owner.state.value.copy(transfer = transfer)
    }

    private fun observation(path: AudioTrackPath) = AudioTrackObservation(observerEpoch = 1L, path = path)

    private fun output(id: String, role: AudioOutputRole = AudioOutputRole.Headset) = AudioOutput(
        id = id,
        displayName = "Output",
        kind = DeviceKind.Bluetooth,
        role = role,
    )
}
