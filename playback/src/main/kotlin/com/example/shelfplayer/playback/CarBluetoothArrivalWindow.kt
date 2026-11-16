package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PD-009 refinement 2026-11-16 — a car-class hands-free link connected moments ago.
 *
 * Hardware captures put that link about ten seconds before Android Auto's focus request, and Auto's own
 * controller bind three or more seconds after it. The window is an inference that a car is arriving, never
 * Play authority: [CarArrivalResumeGate.onInferredCarArrival] still needs the pending trusted focus loss, and
 * the service still checks audio mode. One window supports one inferred arrival.
 *
 * Not thread-safe by design: the receiver and the service both run on the main thread.
 */
internal class CarBluetoothArrivalWindow(private val now: () -> Duration) {
    private var openedAt: Duration? = null
    private var peer: String? = null
    private var consumed = false
    private var takeBackClaimed = false

    /** [peerKey] is a process-private correlation token; it is compared, never logged or stored elsewhere. */
    fun onCarHandsFreeConnected(peerKey: String?) {
        openedAt = now()
        peer = peerKey
        consumed = false
        takeBackClaimed = false
    }

    /** The same car leaving ends its window; an unrelated or unreadable peer does not. */
    fun onHandsFreeDisconnected(peerKey: String?) {
        if (peerKey != null && peerKey == peer) close()
    }

    /** Age of an open, unconsumed window; null once it is closed, spent or older than [LIFETIME]. */
    fun age(): Duration? = openedAt
        ?.let { now() - it }
        ?.takeIf { !consumed && it >= Duration.ZERO && it < LIFETIME }

    fun consume(): Boolean {
        if (age() == null) return false
        consumed = true
        return true
    }

    /** One route take-back per car link, independent of whether the arrival inference was spent. */
    fun claimTakeBack(): Boolean {
        val open = openedAt?.let { now() - it }?.let { it >= Duration.ZERO && it < LIFETIME } == true
        if (!open || takeBackClaimed) return false
        takeBackClaimed = true
        return true
    }

    fun close() {
        openedAt = null
        peer = null
        consumed = false
        takeBackClaimed = false
    }

    internal companion object {
        /** Same bound as the trusted arrival episode; measured link-to-focus intervals were about ten seconds. */
        val LIFETIME: Duration = 30.seconds
    }
}
