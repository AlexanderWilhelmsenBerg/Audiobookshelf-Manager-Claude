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
 * headphones going in can start a book, restart it, and seek it back to where the second callback thought
 * it was.
 *
 * ### Per device, not global
 *
 * Keyed by device id, because plugging in headphones and switching on a speaker within ten seconds of each
 * other is a real thing somebody does, and the second should not be swallowed by the first.
 *
 * Not thread-safe by itself: it is called from the audio callback, which Android delivers on one handler.
 */
class DeviceConnections(private val window: Duration = DEFAULT_WINDOW) {

    private val lastActed = mutableMapOf<String, Instant>()

    /**
     * Whether this connection should be acted on, recording it if so.
     *
     * Asking is what marks it. A caller that checked and then decided not to act would leave the gate open
     * for a duplicate a moment later, which is the failure this exists to prevent.
     */
    fun shouldAct(deviceId: String, at: Instant): Boolean {
        val previous = lastActed[deviceId]
        if (previous != null && Duration.between(previous, at) < window) return false
        lastActed[deviceId] = at
        return true
    }

    /** Forgets a device's last action, so an explicit disconnect and reconnect is not swallowed. */
    fun onDisconnected(deviceId: String) {
        lastActed.remove(deviceId)
    }

    private companion object {
        /** ROUTE-002's number, written where the rule is rather than at the call site. */
        val DEFAULT_WINDOW: Duration = Duration.ofSeconds(10)
    }
}
