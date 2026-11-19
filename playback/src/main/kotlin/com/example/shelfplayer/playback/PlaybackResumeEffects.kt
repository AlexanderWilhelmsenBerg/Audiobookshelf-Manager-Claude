package com.example.shelfplayer.playback

import kotlin.time.Duration

/** Orders content resolution, sleep authorization and freshness without owning their policy or state. */
internal class PlaybackResumeEffects(
    private val sleep: SleepTimerController,
    private val freshness: ResumeFreshnessCoordinator,
    private val source: Source,
) {
    interface Source {
        fun selectionSequence(): Long
        suspend fun materialize(authorized: () -> Boolean): Boolean
        suspend fun resumeLoaded(authorized: () -> Boolean): Boolean
        suspend fun resumeAt(plan: ResumeFreshnessPlan, position: Duration, authorized: () -> Boolean): ResumeOutcome
        fun checked(plan: ResumeFreshnessPlan)
        fun adopted(plan: ResumeFreshnessPlan, outcome: ResumeOutcome)
    }

    suspend fun resume(explicit: Boolean, bypassFreshness: Boolean, stillAuthorized: () -> Boolean): Boolean {
        val selection = source.selectionSequence()
        val authorized = { stillAuthorized() && source.selectionSequence() == selection }
        // Materializing a holder changes book ownership; mint the sleep claim afterwards.
        if (!authorized() || !source.materialize(authorized)) return false
        val sleepRequest = sleep.onPlayRequest(explicit)
        val requested = authorized() && resolveFreshness(bypassFreshness, authorized)
        if (!requested) sleep.clearPlayRequest(sleepRequest)
        return requested
    }

    private suspend fun resolveFreshness(bypass: Boolean, authorized: () -> Boolean): Boolean = if (bypass) {
        source.resumeLoaded(authorized)
    } else {
        when (val prepared = freshness.preparePlay()) {
            ResumePlayPreparation.Bypass -> source.resumeLoaded(authorized)
            ResumePlayPreparation.Superseded -> false
            is ResumePlayPreparation.Ready -> apply(prepared.plan, authorized)
        }
    }

    private suspend fun apply(plan: ResumeFreshnessPlan, authorized: () -> Boolean): Boolean =
        when (val decision = plan.decision) {
            is ResumeFreshnessDecision.Current -> freshness.withCurrentPlan(plan) {
                if (!authorized()) {
                    false
                } else {
                    source.checked(plan)
                    source.resumeLoaded(authorized)
                }
            } ?: false

            is ResumeFreshnessDecision.Adopt -> {
                val outcome = freshness.withCurrentPlan(plan) {
                    if (!authorized()) {
                        null
                    } else {
                        source.checked(plan)
                        source.resumeAt(plan, decision.position, authorized)
                    }
                }
                if (outcome == null) {
                    false
                } else {
                    source.adopted(plan, outcome)
                    outcome == ResumeOutcome.Resumed
                }
            }
        }
}
