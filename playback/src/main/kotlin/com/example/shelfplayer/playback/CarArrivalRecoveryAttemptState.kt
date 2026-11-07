package com.example.shelfplayer.playback

/** Attempt proof and bounded claim count for one trusted arrival episode. */
internal class CarArrivalRecoveryAttemptState {
    var issuedTarget: CarArrivalResumeGate.Target? = null
        private set
    private var automaticPlayClaims = 0
    private var playIssuedTarget: CarArrivalResumeGate.Target? = null
    private var routeProofTarget: CarArrivalResumeGate.Target? = null
    private var playbackActiveTarget: CarArrivalResumeGate.Target? = null
    private var confirmationReported = false

    val confirmed: Boolean
        get() = issuedTarget != null && routeProofTarget === issuedTarget && playbackActiveTarget === issuedTarget

    val canClaim: Boolean get() = automaticPlayClaims < MAX_AUTOMATIC_PLAY_CLAIMS
    val hasClaim: Boolean get() = automaticPlayClaims > 0

    val nextAttemptOrdinal: Int get() = automaticPlayClaims + 1

    fun issue(target: CarArrivalResumeGate.Target) {
        issuedTarget = target
        playIssuedTarget = null
        routeProofTarget = null
        playbackActiveTarget = null
        confirmationReported = false
    }

    fun markPlayIssued(target: CarArrivalResumeGate.Target) {
        if (target === issuedTarget && automaticPlayClaims > 0) playIssuedTarget = target
    }

    fun observeActualTrack(target: CarArrivalResumeGate.Target?): Boolean {
        if (target == null || target !== issuedTarget || playIssuedTarget !== target) return false
        routeProofTarget = target
        return reportConfirmationIfComplete()
    }

    fun playbackBecameActive(target: CarArrivalResumeGate.Target): Boolean {
        if (playIssuedTarget !== target || target !== issuedTarget) return false
        playbackActiveTarget = target
        return reportConfirmationIfComplete()
    }

    fun recordClaim(target: CarArrivalResumeGate.Target) {
        if (target === issuedTarget && canClaim) automaticPlayClaims++
    }

    private fun reportConfirmationIfComplete(): Boolean {
        if (confirmationReported || !confirmed || playIssuedTarget !== issuedTarget) {
            return false
        }
        confirmationReported = true
        return true
    }

    private companion object {
        const val MAX_AUTOMATIC_PLAY_CLAIMS = 3
    }
}
