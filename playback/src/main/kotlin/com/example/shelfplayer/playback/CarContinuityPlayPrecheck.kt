package com.example.shelfplayer.playback

/**
 * Issue #128 — what the service must do with a car-arrival continuity resume before it asks
 * [CarArrivalResumeGate.consumeRecovery] for the final eligibility verdict.
 *
 * The service applies the outcome: it cancels the matching pending state, then (only for [Proceed] and an
 * accepted verdict) issues exactly one `play()`.
 */
internal enum class CarContinuityPlayPrecheck {
    /** There is no player; cancel the pending recovery. */
    NoPlayer,

    /** Nothing is loaded, so there is nothing to resume; cancel every pending recovery. */
    EmptyQueue,

    /** Playback intent is already active; cancel the pending recovery and do not issue a second Play. */
    AlreadyPlaying,

    /** Ask the gate for the final verdict. */
    Proceed,
    ;

    companion object {
        fun decide(hasPlayer: Boolean, mediaItemCount: Int, playWhenReady: Boolean): CarContinuityPlayPrecheck = when {
            !hasPlayer -> NoPlayer
            mediaItemCount == 0 -> EmptyQueue
            playWhenReady -> AlreadyPlaying
            else -> Proceed
        }
    }
}
