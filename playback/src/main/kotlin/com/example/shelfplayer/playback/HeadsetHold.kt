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
internal class RouteHeardOwnership {

    enum class Evidence {
        /** The listener selected this output while BookWave playback was already observed as running. */
        ListenerSelectionWhilePlaying,

        /** Android reported this route while BookWave playback was observed as running. */
        FrameworkPolicyWhilePlaying,
    }

    data class HeardRoute(
        val generation: Long,
        val outputId: String,
        val role: AudioOutputRole,
        val evidence: Evidence,
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

    var heardRoute: HeardRoute? = null
        private set

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
    ) {
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
            heardRoute = chosen.asHeard(generation, Evidence.ListenerSelectionWhilePlaying)
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

        // Explicit listener intent wins over getAudioDevicesForAttributes / enumeration order. The preferred
        // device API is still a preference, so this is the strongest evidence BookWave owns, not a claim that
        // Android exposes an exact AudioTrack sink.
        val explicit = listenerIntent as? ListenerIntent.Device
        if (explicit != null) {
            val chosen = outputs.firstOrNull { it.id == explicit.outputId } ?: return
            heardRoute = chosen.asHeard(generation, Evidence.ListenerSelectionWhilePlaying)
            return
        }

        val candidate = frameworkCandidate(outputs) ?: return
        val automatic = listenerIntent as? ListenerIntent.Automatic
        if (automatic?.releasedRouteId == candidate.id) return
        if (automatic?.releasedRouteId != null && automatic.releasedRouteId != candidate.id) {
            listenerIntent = automatic.copy(releasedRouteId = null)
        }

        val previous = heardRoute?.takeIf { it.generation == generation }
        if (previous != null && previous.outputId != candidate.id) {
            val previousOutput = outputs.firstOrNull { it.id == previous.outputId }

            // A listener choice is never replaced by weaker framework policy while that target still exists.
            if (previous.evidence == Evidence.ListenerSelectionWhilePlaying && previousOutput != null) return

            if (previousOutput?.isHeadsetCandidate == true) {
                // ADR-0029 car-arrival race: neither an ambiguous A2DP dashboard nor a definite car bus may
                // erase the headset heard a moment earlier merely because Android policy moved first.
                if (candidate.role == AudioOutputRole.Car) return
                if (candidate.role == AudioOutputRole.Ambiguous) return
            }
        }

        heardRoute = candidate.asHeard(generation, Evidence.FrameworkPolicyWhilePlaying)
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

    private fun AudioOutput.asHeard(generation: Long, evidence: Evidence) = HeardRoute(
        generation = generation,
        outputId = id,
        role = role,
        evidence = evidence,
    )
}
