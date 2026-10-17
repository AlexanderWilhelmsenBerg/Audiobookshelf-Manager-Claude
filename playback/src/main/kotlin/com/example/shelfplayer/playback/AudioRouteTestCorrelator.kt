package com.example.shelfplayer.playback

import java.time.Duration
import java.time.Instant

/**
 * Associates a user-marked *upcoming* physical action with the next media key and transport
 * command. This establishes temporal correlation only: platform InputDevice -1 is not a headset.
 * Markers pressed after the button deliberately cannot retroactively claim its origin.
 */
internal class AudioRouteTestCorrelator {
    private data class Armed(val step: String, val at: Instant, val matched: Boolean = false)
    private var armed: Armed? = null
    private var awaitingTransport: Armed? = null

    fun arm(step: String, at: Instant) {
        armed = Armed(step, at)
        awaitingTransport = null
    }

    fun observe(kind: String, at: Instant): String? {
        val current = armed
        if (kind.startsWith("hardware-button") && "keyAction=0" in kind && current != null && !current.matched) {
            val delay = Duration.between(current.at, at).toMillis()
            if (delay in 0..WINDOW_MS) {
                armed = current.copy(matched = true)
                awaitingTransport = current
                return "test-key-correlated step=${current.step} afterMs=$delay attribution=temporal-only"
            }
        }
        val awaiting = awaitingTransport
        if (kind.startsWith("transport-intent") && awaiting != null) {
            val delta = Duration.between(awaiting.at, at).toMillis()
            awaitingTransport = null
            armed = null
            if (delta in 0..WINDOW_MS) {
                val command = when {
                    "requested=Play" in kind -> "Play"
                    "requested=Pause" in kind -> "Pause"
                    else -> "Unknown"
                }
                return "test-command-correlated step=${awaiting.step} command=$command attribution=temporal-only"
            }
        }
        return null
    }

    private companion object {
        const val WINDOW_MS = 12_000L
    }
}
