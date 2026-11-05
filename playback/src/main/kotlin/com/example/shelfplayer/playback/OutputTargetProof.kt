package com.example.shelfplayer.playback

/** A read-only inventory/projection snapshot, never another destination or connection-state owner. */
internal data class OutputProofTargets(
    val eligible: Set<String>,
    val carEndpoints: Set<String>,
    val projection: Boolean,
)

/** Target-specific measured proof. Null permits only bounded continued silence, never audible release. */
internal object OutputTargetProof {
    fun evaluate(
        request: OutputIntentOwner.TransferRequest,
        first: AudioTrackObservation?,
        second: AudioTrackObservation?,
        targets: OutputProofTargets,
    ): MutedProbeCompletion? {
        if (first == null || second == null) return null
        if (first.path == AudioTrackPath.Multiple || second.path == AudioTrackPath.Multiple) {
            return MutedProbeCompletion.Refused
        }
        if (first != second) return null
        return when (val path = first.path) {
            is AudioTrackPath.Endpoint -> endpointProof(request, path, targets)
            AudioTrackPath.RemoteSubmix -> projectionProof(request, targets.projection)
            AudioTrackPath.Unresolved -> null
            AudioTrackPath.Multiple -> MutedProbeCompletion.Refused
        }
    }

    private fun endpointProof(
        request: OutputIntentOwner.TransferRequest,
        path: AudioTrackPath.Endpoint,
        targets: OutputProofTargets,
    ): MutedProbeCompletion? {
        if (request.selection.destination == OutputIntentOwner.Destination.Output &&
            path.id == request.selection.outputId
        ) {
            return if (path.id in targets.eligible) MutedProbeCompletion.Verified else MutedProbeCompletion.Refused
        }
        if (request.selection.destination == OutputIntentOwner.Destination.Car && path.id in targets.carEndpoints) {
            return if (targets.projection) MutedProbeCompletion.Verified else null
        }
        return if (path == request.originalPath) null else MutedProbeCompletion.Refused
    }

    private fun projectionProof(
        request: OutputIntentOwner.TransferRequest,
        projection: Boolean,
    ): MutedProbeCompletion? = when {
        request.selection.destination == OutputIntentOwner.Destination.Car ->
            if (projection) MutedProbeCompletion.Delegated else null

        request.originalPath == AudioTrackPath.RemoteSubmix -> null

        else -> MutedProbeCompletion.Refused
    }
}
