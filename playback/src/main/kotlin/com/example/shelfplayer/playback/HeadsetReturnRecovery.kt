package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Gate-owned allowance for one safety pause, with a deadline fixed when the headset is lost. */
internal class HeadsetReturnRecovery(private val now: () -> Duration, private val trusted: (String) -> Boolean) {
    private class Claim(val target: CarArrivalResumeGate.Target, val pausedAt: Duration) {
        var consumed = false
    }

    private var claim: Claim? = null

    fun issue(identity: CarContinuityIdentity, at: Duration, carArrival: Boolean): CarArrivalResumeGate.Target? {
        if (!identity.trustedAtPlayback || !trusted(identity.outputId)) return null
        val window = if (carArrival) CAR_ARRIVAL_WINDOW else ORDINARY_WINDOW
        return identity.target(CarArrivalResumeGate.Phase.HeadsetReturn, at + window).also { claim = Claim(it, at) }
    }

    fun valid(candidate: CarArrivalResumeGate.Target): Boolean {
        val current = claim ?: return false
        return current.target === candidate && now() in current.pausedAt..<requireNotNull(candidate.deadline) &&
            trusted(candidate.outputId)
    }

    fun consume(candidate: CarArrivalResumeGate.Target): Boolean {
        val current = claim ?: return false
        if (current.consumed || !valid(candidate)) return false
        current.consumed = true
        return true
    }

    fun cancelPending() {
        // The observed Play callback must not cancel the consumed claim's muted route verification.
        if (claim?.consumed != true) clear()
    }

    fun clear() {
        claim = null
    }

    private companion object {
        val ORDINARY_WINDOW = 2.seconds
        val CAR_ARRIVAL_WINDOW = 5.seconds
    }
}
