package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputActivitySource
import com.example.shelfplayer.core.model.playback.AudioOutputRole

/**
 * PRODUCT_SPEC PLAY-002 / ROUTE-002 — one owner for the strongest route evidence of the loaded book.
 *
 * The router labels measured AudioTrack sinks separately from API 33+ media-policy predictions and
 * older-platform preference fallbacks. This owner keeps that provenance while preserving an explicit
 * listener choice as the strongest product intent. A heard route authorizes a continuity candidate;
 * only the router's current playing-track check can authorize audible guarded playback.
 *
 * The record is generation-bound. Loading another book or emptying the queue invalidates the old evidence,
 * while the router's current explicit preference may legitimately remain in force for the next book.
 */
internal enum class RouteHeardEvidence {
    /** The listener selected this output while BookWave playback was already observed as running. */
    ListenerSelectionWhilePlaying,

    /** Android reported this route while BookWave playback was observed as running. */
    FrameworkPolicyWhilePlaying,

    /** The router measured this current playing AudioTrack's route. */
    ActualAudioTrackWhilePlaying,

    /** Only a selected preference was available; Android did not report this track's sink. */
    SelectedPreferenceWhilePlaying,

    /** A caller supplied activity without provenance; retain policy behavior without inventing proof. */
    UnmeasuredActivityWhilePlaying,
}

internal class RouteHeardOwnership(internal val intentOwner: OutputIntentOwner) {
    data class HeardRoute(
        val generation: Long,
        val outputId: String,
        val role: AudioOutputRole,
        val evidence: RouteHeardEvidence,
    )

    private var nextGeneration = 0L
    private var activeGeneration: Long? = null

    /** Consumer watermark only; the authoritative sequence remains in [intentOwner]. */
    private var lastConsumedSelectionSequence = 0L

    var heardRoute: HeardRoute? = null
        private set

    /**
     * Stable book-generation identity for policies that must survive transient Android route-list churn.
     *
     * Unlike [heardRoute], this does not disappear merely because a currently-owned output briefly drops out
     * of AudioManager's live device list. It changes only at the actual book/queue ownership boundary.
     */
    val currentGeneration: Long?
        get() = activeGeneration

    /** A Media3 item transition is the ownership boundary between loaded books. */
    fun onBookChanged(hasBook: Boolean) {
        heardRoute = null
        activeGeneration = if (hasBook) ++nextGeneration else null
        intentOwner.onBookChanged(activeGeneration)
    }

    /** Stop/queue-clear can happen without an audio-output emission, so invalidate it explicitly. */
    fun onQueueEmptied() = onBookChanged(hasBook = false)

    /**
     * Every BookWave output chooser reaches this path through [AudioOutputRouter.select].
     *
     * A non-null choice is stronger than framework policy. Automatic deliberately clears old heard evidence
     * and temporarily refuses the just-released route, so phone and Android Auto selection paths cannot drift.
     */
    fun onExplicitSelection(
        outputId: String?,
        outputs: List<AudioOutput>,
        isPlaying: Boolean,
        selectionSequence: Long? = null,
        destination: OutputIntentOwner.Destination? = null,
    ): Boolean {
        val selection = intentOwner.state.value.selection ?: return false
        val sameSelection = selection.sequence == selectionSequence && selection.outputId == outputId
        val sameDestination = destination == null || selection.destination == destination
        if (!sameSelection || !sameDestination || selection.sequence <= lastConsumedSelectionSequence) {
            return false
        }
        lastConsumedSelectionSequence = selection.sequence

        val generation = activeGeneration
        val previous = heardRoute?.takeIf { it.generation == generation }

        if (outputId == null) {
            heardRoute = null
            if (isPlaying) observeRouteWhilePlaying(outputs)
            return true
        }

        val chosen = outputs.firstOrNull { it.id == outputId }
        heardRoute = previous?.takeIf { it.outputId == outputId && chosen != null }

        if (isPlaying && generation != null && chosen != null) {
            heardRoute = chosen.asHeard(generation, RouteHeardEvidence.ListenerSelectionWhilePlaying)
        }
        return true
    }

    /** The service has just observed real playback; capture the strongest route evidence available now. */
    fun onPlaybackObserved(outputs: List<AudioOutput>) {
        observeRouteWhilePlaying(outputs)
    }

    /**
     * Route callbacks can continue while playback is paused. They may retire disconnected evidence, but only
     * callbacks observed while the book is playing are allowed to create or replace heard-route evidence.
     */
    fun onOutputsChanged(outputs: List<AudioOutput>, isPlaying: Boolean) {
        retireDisconnectedState(outputs)
        if (isPlaying) observeRouteWhilePlaying(outputs)
    }

    /** The generation-bound headset a car arrival may reassert, if that same candidate is still connected. */
    fun headsetForCar(outputs: List<AudioOutput>): String? {
        val generation = activeGeneration ?: return null
        val heard = heardRoute?.takeIf { it.generation == generation } ?: return null
        return heard.outputId.takeIf { id ->
            outputs.any { output -> output.id == id && output.isHeadsetCandidate }
        }
    }

    /** The only transient pre-arrival claim: one current-generation, measured, uniquely active headset candidate. */
    fun measuredPlayingHeadsetForContinuity(outputs: List<AudioOutput>): String? {
        val generation = activeGeneration ?: return null
        val heard = heardRoute?.takeIf {
            it.generation == generation && it.evidence == RouteHeardEvidence.ActualAudioTrackWhilePlaying
        } ?: return null
        val activeTracks = outputs.filter {
            it.isActive && it.activitySource == AudioOutputActivitySource.PlayingAudioTrack
        }
        if (activeTracks.size != 1 || activeTracks.single().id != heard.outputId) return null
        return headsetForCar(outputs)?.takeIf { it == heard.outputId }
    }

    private fun observeRouteWhilePlaying(outputs: List<AudioOutput>) {
        val generation = activeGeneration ?: return
        retireDisconnectedState(outputs)

        explicitOutput(outputs)?.let { chosen ->
            heardRoute = chosen.asHeard(generation, RouteHeardEvidence.ListenerSelectionWhilePlaying)
            intentOwner.reportHeardRoute(chosen.id)
        } ?: observeActivityRoute(generation, outputs)
    }

    private fun explicitOutput(outputs: List<AudioOutput>): AudioOutput? {
        val explicit =
            intentOwner.state.value.selection?.takeIf { it.destination == OutputIntentOwner.Destination.Output }
                ?: return null
        return outputs.firstOrNull { it.id == explicit.outputId }
    }

    private fun observeActivityRoute(generation: Long, outputs: List<AudioOutput>) {
        val candidate = activityCandidate(outputs) ?: return
        if (intentOwner.isReleasedRoute(candidate.id)) return

        if (!shouldKeepPreviousAgainst(candidate, outputs)) {
            heardRoute = candidate.asHeard(generation, candidate.heardEvidence())
            intentOwner.reportHeardRoute(candidate.id)
        }
    }

    private fun AudioOutput.heardEvidence(): RouteHeardEvidence = when (activitySource) {
        AudioOutputActivitySource.PlayingAudioTrack -> RouteHeardEvidence.ActualAudioTrackWhilePlaying
        AudioOutputActivitySource.FrameworkMediaPolicy -> RouteHeardEvidence.FrameworkPolicyWhilePlaying
        AudioOutputActivitySource.SelectedPreference -> RouteHeardEvidence.SelectedPreferenceWhilePlaying
        AudioOutputActivitySource.Unmeasured -> RouteHeardEvidence.UnmeasuredActivityWhilePlaying
    }

    private fun shouldKeepPreviousAgainst(candidate: AudioOutput, outputs: List<AudioOutput>): Boolean {
        val previous = heardRoute?.takeIf { it.generation == activeGeneration }
        val previousOutput = previous?.let { heard -> outputs.firstOrNull { it.id == heard.outputId } }
        if (previous == null || previousOutput == null) return false
        if (previous.outputId == candidate.id) return false

        val protectsHeadsetFromCarRace =
            previousOutput.isHeadsetCandidate &&
                (candidate.role == AudioOutputRole.Car || candidate.role == AudioOutputRole.Ambiguous)
        return previous.evidence == RouteHeardEvidence.ListenerSelectionWhilePlaying || protectsHeadsetFromCarRace
    }

    /**
     * Return one route only when platform evidence is not dependent on output enumeration order.
     *
     * A speaker is demoted when exactly one non-speaker is active, matching the existing presentation rule.
     * Multiple ambiguous A2DP endpoints remain ambiguous as a set: their ordering is never ownership.
     */
    private fun activityCandidate(outputs: List<AudioOutput>): AudioOutput? {
        val active = outputs.filter(AudioOutput::isActive)
        val nonSpeakers = active.filterNot(AudioOutput::isSpeaker)
        if (nonSpeakers.size == 1) return nonSpeakers.single()
        if (nonSpeakers.size > 1) {
            return nonSpeakers.filter { it.role != AudioOutputRole.Ambiguous }.singleOrNull()
        }
        return active.filter(AudioOutput::isSpeaker).singleOrNull()
    }

    private fun retireDisconnectedState(outputs: List<AudioOutput>) {
        val connected = outputs.asSequence().map(AudioOutput::id).toSet()
        intentOwner.onOutputDisconnected(connected)

        heardRoute = heardRoute?.takeIf { heard ->
            heard.generation == activeGeneration && heard.outputId in connected
        }
    }

    private fun AudioOutput.asHeard(generation: Long, evidence: RouteHeardEvidence) = HeardRoute(
        generation = generation,
        outputId = id,
        role = role,
        evidence = evidence,
    )
}
