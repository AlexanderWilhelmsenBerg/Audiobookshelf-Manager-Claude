package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputPresentation
import com.example.shelfplayer.core.model.playback.AudioOutputRole
import com.example.shelfplayer.core.model.playback.OutputDestination
import com.example.shelfplayer.core.model.playback.OutputMeasurement
import com.example.shelfplayer.core.model.playback.OutputTransferStatus

/** Read-only projection of current intent and evidence; target semantics stay in OutputTargetProof. */
internal object OutputPresentationProjection {
    fun present(
        snapshot: OutputIntentOwner.Snapshot,
        observation: AudioTrackObservation?,
        outputs: List<AudioOutput>,
        projection: Boolean,
        eligibleEndpointIds: Set<String> = outputs.map(AudioOutput::id).toSet(),
    ): AudioOutputPresentation {
        val eligible = eligibleEndpointIds.filterTo(mutableSetOf()) { id -> outputs.count { it.id == id } == 1 }
        val path = observation?.path
        val measured = (path as? AudioTrackPath.Endpoint)?.id?.takeIf { it in eligible }
        val targets = OutputProofTargets(
            eligible = eligible,
            carEndpoints = outputs.filter { it.id in eligible && it.role == AudioOutputRole.Car }.map { it.id }.toSet(),
            projection = projection,
        )
        return AudioOutputPresentation(
            requested = snapshot.selection?.destination?.let {
                when (it) {
                    OutputIntentOwner.Destination.Automatic -> OutputDestination.Automatic
                    OutputIntentOwner.Destination.Car -> OutputDestination.Car
                    OutputIntentOwner.Destination.Output -> OutputDestination.Output
                }
            },
            requestedOutputId = snapshot.selection?.outputId,
            measurement = measurement(path, measured),
            measuredOutputId = measured,
            transfer = transferStatus(snapshot, observation, targets),
            hasRetainedPreviousLock =
            snapshot.lock?.let { it.selectionSequence != snapshot.selection?.sequence } == true,
        )
    }

    private fun measurement(path: AudioTrackPath?, measured: String?): OutputMeasurement = when (path) {
        is AudioTrackPath.Endpoint -> if (measured != null) OutputMeasurement.Endpoint else OutputMeasurement.Unresolved
        AudioTrackPath.RemoteSubmix -> OutputMeasurement.RemoteSubmix
        AudioTrackPath.Unresolved -> OutputMeasurement.Unresolved
        AudioTrackPath.Multiple -> OutputMeasurement.Multiple
        null -> OutputMeasurement.Unavailable
    }

    private fun transferStatus(
        snapshot: OutputIntentOwner.Snapshot,
        observation: AudioTrackObservation?,
        targets: OutputProofTargets,
    ): OutputTransferStatus? {
        val transfer = snapshot.transfer ?: return null
        return when (transfer.phase) {
            OutputIntentOwner.TransferPhase.Waiting -> OutputTransferStatus.Waiting

            OutputIntentOwner.TransferPhase.Verifying -> OutputTransferStatus.Verifying

            OutputIntentOwner.TransferPhase.Failed -> OutputTransferStatus.Failed

            OutputIntentOwner.TransferPhase.Verified, OutputIntentOwner.TransferPhase.Delegated -> {
                val current = snapshot.selection == transfer.request.selection &&
                    transfer.request.bookGeneration != null &&
                    snapshot.activeBookGeneration == transfer.request.bookGeneration
                val proof = if (current) {
                    OutputTargetProof.evaluate(
                        transfer.request,
                        observation,
                        observation,
                        targets,
                    )
                } else {
                    null
                }
                when {
                    transfer.phase == OutputIntentOwner.TransferPhase.Verified &&
                        proof == MutedProbeCompletion.Verified ->
                        OutputTransferStatus.Verified

                    transfer.phase == OutputIntentOwner.TransferPhase.Delegated &&
                        proof == MutedProbeCompletion.Delegated ->
                        OutputTransferStatus.Delegated

                    else -> OutputTransferStatus.Waiting
                }
            }
        }
    }
}
