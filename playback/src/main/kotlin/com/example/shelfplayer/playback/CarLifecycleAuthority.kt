package com.example.shelfplayer.playback

/**
 * The sole authority for Android Auto lifecycle latches.
 *
 * MediaSession controller counts stay in the existing CarConnections instance. This component
 * reconciles them with the stronger physical projection signal, and owns the previously
 * service-local projection/established booleans. It does not own playback, routing, audio focus,
 * ExoPlayer, or a recovery episode; PlaybackService reacts to its boundary outcomes.
 *
 * All calls occur on the service/player application dispatcher. In particular, a provider's
 * Unknown result must not retire a positive projection, while NotConnected must.
 */
internal class CarLifecycleAuthority(private val connections: CarConnections) {
    internal enum class ProjectionEdge {
        None,
        Arrival,
        PhysicalDeparture,
    }

    var projectionState = AndroidAutoProjectionMonitor.State.Unknown
        private set

    var projectionOwnsCarLifecycle = false
        private set

    var sessionEstablished = false
        private set

    val connectedForSleepTimer: Boolean
        get() = connections.isConnected() || projectionOwnsCarLifecycle

    /** A controller bind establishes a continuity session, even if projection is not observable. */
    fun onControllerConnected() {
        connections.onConnected()
        sessionEstablished = true
    }

    /** Only the final controller matters, and even that is weaker than positive projection. */
    fun onControllerDisconnected() {
        connections.onDisconnected()
    }

    /** Called only for a true final controller departure with no positive projection latch. */
    fun onFinalLegacyControllerDeparture() {
        check(!connections.isConnected() && !projectionOwnsCarLifecycle)
        sessionEstablished = false
    }

    /**
     * Snapshot the latest provider observation before service diagnostics. Boundary handling follows
     * synchronously in the same callback to preserve the prior logging and event-order contract.
     */
    fun onProjectionReported(state: AndroidAutoProjectionMonitor.State) {
        projectionState = state
    }

    /** Applies the real boundary; neither Unknown nor a repeated positive signal is a new edge. */
    fun onProjectionBoundary(update: AndroidAutoProjectionMonitor.Update): ProjectionEdge {
        check(projectionState == update.current) { "Projection observation must precede its boundary" }
        if (update.initial) {
            projectionOwnsCarLifecycle = update.current.carConnected
            if (projectionOwnsCarLifecycle && connections.isConnected()) sessionEstablished = true
            return ProjectionEdge.None
        }
        if (!projectionOwnsCarLifecycle && update.current.carConnected) {
            projectionOwnsCarLifecycle = true
            return ProjectionEdge.Arrival
        }
        if (projectionOwnsCarLifecycle && update.current == AndroidAutoProjectionMonitor.State.NotConnected) {
            sessionEstablished = false
            connections.onProjectionDisconnected()
            projectionOwnsCarLifecycle = false
            return ProjectionEdge.PhysicalDeparture
        }
        return ProjectionEdge.None
    }
}
