package com.example.shelfplayer.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Gate-owned short safety-pause allowance. Never observes devices, chooses an output or calls Play. */
internal class HeadsetReturnRecovery(private val now: () -> Duration, private val trusted: (String) -> Boolean) {
    private var target: CarArrivalResumeGate.Target? = null
    private var claimed = false

    fun issue(identity: CarContinuityIdentity, at: Duration): CarArrivalResumeGate.Target? {
        if (!identity.trustedAtPlayback || !trusted(identity.outputId)) return null
        claimed = false
        return identity.target(CarArrivalResumeGate.Phase.HeadsetReturn, at + WINDOW).also { target = it }
    }

    fun valid(candidate: CarArrivalResumeGate.Target): Boolean = target === candidate &&
        now() >= requireNotNull(candidate.deadline) - WINDOW && now() < candidate.deadline &&
        trusted(candidate.outputId)

    fun consume(candidate: CarArrivalResumeGate.Target): Boolean {
        if (claimed || !valid(candidate)) return false
        claimed = true
        return true
    }

    fun cancelPending() {
        // The observed Play callback must not cancel the consumed claim's muted route verification.
        if (!claimed) clear()
    }

    fun clear() {
        target = null
        claimed = false
    }

    private companion object {
        val WINDOW = 2.seconds
    }
}
