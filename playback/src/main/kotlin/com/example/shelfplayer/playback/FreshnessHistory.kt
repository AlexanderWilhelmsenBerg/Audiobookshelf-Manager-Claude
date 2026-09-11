package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.playback.PlaybackEvent

/**
 * The History row that describes the freshness **check**, independent of whether an adopted seek later lands.
 *
 * Realtime has no REST check to record. A plan without an acknowledged baseline likewise made no meaningful
 * server comparison, so it produces no check row.
 */
internal fun ResumeFreshnessPlan.serverCheckHistoryEvent(): PlaybackEvent? {
    if (baselineGeneration == null || decision.source == FreshnessEvidenceSource.Realtime) return null
    return when (val value = decision) {
        is ResumeFreshnessDecision.Adopt -> when (value.source) {
            FreshnessEvidenceSource.RestoredBaseline -> PlaybackEvent.ServerCheckCurrent
            FreshnessEvidenceSource.Rest -> PlaybackEvent.ServerCheckAhead
            FreshnessEvidenceSource.LocalUnverified -> PlaybackEvent.ServerCheckUnavailable
            FreshnessEvidenceSource.Realtime -> null
        }
        is ResumeFreshnessDecision.Current -> when (value.source) {
            FreshnessEvidenceSource.LocalUnverified -> PlaybackEvent.ServerCheckUnavailable
            FreshnessEvidenceSource.Rest, FreshnessEvidenceSource.RestoredBaseline -> PlaybackEvent.ServerCheckCurrent
            FreshnessEvidenceSource.Realtime -> null
        }
    }
}

/**
 * The History row that claims playback actually moved to another device's position.
 *
 * The row is evidence of an applied movement, not merely of a decision. A lost seek, wrong book, unloaded
 * player or superseding command must therefore never produce `RemoteProgress`. Restoring a server-verified
 * baseline after an unexplained local install mismatch is likewise not another device's movement.
 */
internal fun ResumeFreshnessPlan.remoteProgressHistoryEvent(outcome: ResumeOutcome): PlaybackEvent? =
    PlaybackEvent.RemoteProgress.takeIf {
        decision is ResumeFreshnessDecision.Adopt &&
            decision.source != FreshnessEvidenceSource.RestoredBaseline &&
            outcome == ResumeOutcome.Resumed
    }
