package com.example.shelfplayer.playback

import java.time.Duration
import java.time.Instant

/**
 * PRODUCT_SPEC ROUTE-002 — *"Duplicate connection callbacks within ten seconds trigger at most one action."*
 *
 * ### Why this is not incidental
 *
 * `AudioDeviceCallback` fires more than once for one physical connection, and reliably so: a Bluetooth
 * headset announces A2DP and its SCO profile separately, some report a route change immediately after
 * connecting, and a flaky contact on a wired jack can produce a burst. Without a gate, one pair of
 * headphones going in can start a book, restart it, and seek it back to wherever the second callback thought
 * it was.
 *
 * ### Per device, not global
 *
 * Keyed by device id, because plugging in headphones and switching on a speaker within ten seconds of each
 * other is a real thing somebody does, and the second should not be swallowed by the first.
 *
 * ### Already present is not newly connected
 *
 * Android may replay the outputs that already exist when an `AudioDeviceCallback` is registered. The
 * playback service is often created because the user just selected a book, so treating that replay as a
 * physical connection starts an asynchronous "arm the last book" request beside the explicit selection.
 * Whichever `/play` call returns last then owns the player. [onPresentAtStart] records those devices in a
 * separate one-shot set before registration. Their first added callback is consumed regardless of how long
 * registration takes; an explicit removal clears the marker so a real reconnect is acted on immediately.
 *
 * Not thread-safe by itself: calls are serialized by [OutputDeviceWatcher]'s action gate.
 */
class DeviceConnections(private val window: Duration = DEFAULT_WINDOW) {

    private val lastActed = mutableMapOf<String, Instant>()
    private val presentAtStart = mutableSetOf<String>()

    /**
     * Whether this connection should be acted on, recording it if so.
     *
     * Asking is what marks it. A caller that checked and then decided not to act would leave the gate open
     * for a duplicate a moment later, which is the failure this exists to prevent.
     */
    fun shouldAct(deviceId: String, at: Instant): Boolean {
        // Registration replay is state discovery, not a user action. Remove the marker when consumed so a
        // later callback which is not paired with a removal still falls through to the ordinary debounce.
        if (presentAtStart.remove(deviceId)) return false

        val previous = lastActed[deviceId]
        if (previous != null && Duration.between(previous, at) < window) return false
        lastActed[deviceId] = at
        return true
    }

    /**
     * Records an output that existed before observation began.
     *
     * The first callback for it is therefore a replay of the startup snapshot, not a user event. This is a
     * one-shot marker rather than a timestamp so a delayed registration callback cannot become a connection
     * merely because ten seconds elapsed while the process was busy.
     */
    fun onPresentAtStart(deviceId: String) {
        presentAtStart += deviceId
    }

    /** Forgets a device's state, so an explicit disconnect and reconnect is not swallowed. */
    fun onDisconnected(deviceId: String) {
        presentAtStart.remove(deviceId)
        lastActed.remove(deviceId)
    }

    private companion object {
        /** ROUTE-002's number, written where the rule is rather than at the call site. */
        val DEFAULT_WINDOW: Duration = Duration.ofSeconds(10)
    }
}
