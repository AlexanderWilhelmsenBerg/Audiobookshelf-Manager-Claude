package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.AudioOutput
import com.example.shelfplayer.core.model.playback.AudioOutputRole

/**
 * PRODUCT_SPEC PLAY-002 / ROUTE-002 — one owner for the strongest route evidence of the loaded book.
 *
 * Android does not expose the AudioTrack's exact sink here. API 33+
 * `getAudioDevicesForAttributes` is system-policy evidence for BookWave's media attributes, not proof of
 * the device carrying this exact track. This owner therefore records where each fact came from and keeps an
 * explicit listener choice stronger than that framework observation.
 *
 * The record is generation-bound. Loading another book or emptying the queue invalidates the old evidence,
 * while the router's current explicit preference may legitimately remain in force for the next book.
 */
internal enum class RouteHeardEvidence {
    /** The listener selected this output while BookWave playback was already observed as running. */
    ListenerSelectionWhilePlaying,

    /** Android reported this route while BookWave playback was observed as running. */
    FrameworkPolicyWhilePlaying,
}

internal class RouteHeardOwnership {
    data class HeardRoute(
        val generation: Long,
        val outputId: String,
        val role: AudioOutputRole,
        val evidence: RouteHeardEvidence,
    )

    private sealed interface ListenerIntent {
        data object Unspecified : ListenerIntent

        data class Device(val outputId: String) : ListenerIntent

        /**
         * Automatic is explicit intent too.
         *
         * [releasedRouteId] prevents the still-active callback caused by `select(null)` from immediately
         * resurrecting the route the listener just released. It is part of this single ownership state, not
         * a second release flag; it is cleared as soon as Android reports another route or the device leaves.
         */
        data class Automatic(val releasedRouteId: String?) : ListenerIntent
    }

    private var nextGeneration = 0L
    private var activeGeneration: Long? = null
    private var listenerIntent: ListenerIntent = ListenerIntent.Unspecified
    private var lastExplicitSelectionSequence = 0L

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
        listenerIntent = when (val current = listenerIntent) {
            is ListenerIntent.Automatic -> current.copy(releasedRouteId = null)
            else -> current
        }
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
    ) {
        if (selectionSequence != null && selectionSequence <= lastExplicitSelectionSequence) return
        if (selectionSequence != null) lastExplicitSelectionSequence = selectionSequence

        val generation = activeGeneration
        val previous = heardRoute?.takeIf { it.generation == generation }

        if (outputId == null) {
            listenerIntent = ListenerIntent.Automatic(previous?.outputId)
            heardRoute = null
            if (isPlaying) observeFrameworkWhilePlaying(outputs)
            return
        }

        listenerIntent = ListenerIntent.Device(outputId)
        val chosen = outputs.firstOrNull { it.id == outputId }
        heardRoute = previous?.takeIf { it.outputId == outputId && chosen != null }

        if (isPlaying && generation != null && chosen != null) {
            heardRoute = chosen.asHeard(generation, RouteHeardEvidence.ListenerSelectionWhilePlaying)
        }
    }

    /** The service has just observed real playback; capture the strongest route evidence available now. */
    fun onPlaybackObserved(outputs: List<AudioOutput>) {
        observeFrameworkWhilePlaying(outputs)
    }

    /**
     * Route callbacks can continue while playback is paused. They may retire disconnected evidence, but only
     * callbacks observed while the book is playing are allowed to create or replace heard-route evidence.
     */
    fun onOutputsChanged(outputs: List<AudioOutput>, isPlaying: Boolean) {
        retireDisconnectedState(outputs)
        if (isPlaying) observeFrameworkWhilePlaying(outputs)
    }

    /** The generation-bound headset a car arrival may reassert, if that same candidate is still connected. */
    fun headsetForCar(outputs: List<AudioOutput>): String? {
        val generation = activeGeneration ?: return null
        val heard = heardRoute?.takeIf { it.generation == generation } ?: return null
        return heard.outputId.takeIf { id ->
            outputs.any { output -> output.id == id && output.isHeadsetCandidate }
        }
    }

    private fun observeFrameworkWhilePlaying(outputs: List<AudioOutput>) {
        val generation = activeGeneration ?: return
        retireDisconnectedState(outputs)

        explicitOutput(outputs)?.let { chosen ->
            heardRoute = chosen.asHeard(generation, RouteHeardEvidence.ListenerSelectionWhilePlaying)
        } ?: observePolicyRoute(generation, outputs)
    }

    private fun explicitOutput(outputs: List<AudioOutput>): AudioOutput? {
        val explicit = listenerIntent as? ListenerIntent.Device ?: return null
        return outputs.firstOrNull { it.id == explicit.outputId }
    }

    private fun observePolicyRoute(generation: Long, outputs: List<AudioOutput>) {
        val candidate = frameworkCandidate(outputs) ?: return
        if (isJustReleased(candidate)) return

        clearReleaseAfterMove(candidate)
        if (!shouldKeepPreviousAgainst(candidate, outputs)) {
            heardRoute = candidate.asHeard(generation, RouteHeardEvidence.FrameworkPolicyWhilePlaying)
        }
    }

    private fun isJustReleased(candidate: AudioOutput): Boolean =
        (listenerIntent as? ListenerIntent.Automatic)?.releasedRouteId == candidate.id

    private fun clearReleaseAfterMove(candidate: AudioOutput) {
        val automatic = listenerIntent as? ListenerIntent.Automatic ?: return
        val released = automatic.releasedRouteId
        if (released != null && released != candidate.id) {
            listenerIntent = automatic.copy(releasedRouteId = null)
        }
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
    private fun frameworkCandidate(outputs: List<AudioOutput>): AudioOutput? {
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

        heardRoute = heardRoute?.takeIf { heard ->
            heard.generation == activeGeneration && heard.outputId in connected
        }

        listenerIntent = when (val current = listenerIntent) {
            is ListenerIntent.Device ->
                if (current.outputId in connected) current else ListenerIntent.Unspecified

            is ListenerIntent.Automatic ->
                if (current.releasedRouteId == null || current.releasedRouteId in connected) {
                    current
                } else {
                    current.copy(releasedRouteId = null)
                }

            ListenerIntent.Unspecified -> ListenerIntent.Unspecified
        }
    }

    private fun AudioOutput.asHeard(generation: Long, evidence: RouteHeardEvidence) = HeardRoute(
        generation = generation,
        outputId = id,
        role = role,
        evidence = evidence,
    )
}
