package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import com.example.shelfplayer.domain.realtime.RealtimeProgressEvidence
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A realtime progress row paired with the acknowledged baseline that was current when the row arrived.
 *
 * The pairing is what makes a push usable without a REST round trip. A socket event by itself is merely
 * news that arrived sometime while the app was connected; recording the baseline generation at receipt
 * proves it arrived while this exact paused agreement was still current. A seek/profile/book change bumps
 * or removes that baseline and makes the candidate unusable automatically.
 */
internal data class RealtimeResumeCandidate(val evidence: RealtimeProgressEvidence, val baselineGeneration: Long)

internal enum class FreshnessEvidenceSource {
    Realtime,
    Rest,
    RestoredBaseline,
    LocalUnverified,
}

internal sealed interface ResumeFreshnessDecision {
    val source: FreshnessEvidenceSource

    /** Resume where this device is. [source] says whether that position was verified or merely tolerated. */
    data class Current(override val source: FreshnessEvidenceSource) : ResumeFreshnessDecision

    /** Adopt a trusted position before any audio starts. */
    data class Adopt(val position: Duration, override val source: FreshnessEvidenceSource) : ResumeFreshnessDecision
}

/**
 * Issue #91 — the shared product rule for every source of resume evidence.
 *
 * The material-move threshold is two minutes: ordinary cross-client drift stays local, while a deliberate
 * forward move or rewind beyond that is adopted. The comparison is always absolute; `max(position)` would
 * silently overrule somebody who intentionally went back on another client.
 */
internal object ResumeFreshnessPolicy {
    val MATERIAL_REMOTE_MOVE: Duration = 2.minutes

    /**
     * Evaluates low-latency socket evidence, or returns `null` when REST must answer instead.
     *
     * `null` never means "resume locally": Socket.IO events are not replayed after disconnect/background/
     * process death, and BookWave's own server echo is confirmation of our write rather than another
     * session's movement. Realtime evidence is usable only when both sides carry session identity and those
     * identities differ. A missing id cannot prove that the push came from another device, so REST answers
     * that case instead of trusting an ambiguous echo.
     */
    fun realtime(
        loadedProfile: ProfileId,
        loadedBook: LibraryItemId,
        loadedSessionId: String?,
        baseline: AcknowledgedPause,
        candidate: RealtimeResumeCandidate?,
    ): ResumeFreshnessDecision? {
        val evidence = candidate?.evidence
        val evidenceSessionId = evidence?.sessionId
        val matchesCurrentPause = evidence != null &&
            baseline.bookId == loadedBook &&
            candidate.baselineGeneration == baseline.generation &&
            evidence.profileId == loadedProfile &&
            evidence.progress.bookId == loadedBook
        val trustedOtherSession = loadedSessionId != null &&
            evidenceSessionId != null &&
            evidenceSessionId != loadedSessionId

        return if (matchesCurrentPause && trustedOtherSession) {
            materialDecision(evidence.progress.position, baseline, FreshnessEvidenceSource.Realtime)
        } else {
            null
        }
    }

    /** Applies the exact same product threshold to the one-book REST fallback. */
    fun rest(baseline: AcknowledgedPause, check: ExternalSessionCheck): ResumeFreshnessDecision = when (check) {
        is ExternalSessionCheck.Ahead -> materialDecision(check.position, baseline, FreshnessEvidenceSource.Rest)
        ExternalSessionCheck.Current -> ResumeFreshnessDecision.Current(FreshnessEvidenceSource.Rest)
        ExternalSessionCheck.Unavailable -> ResumeFreshnessDecision.Current(FreshnessEvidenceSource.LocalUnverified)
    }

    private fun materialDecision(
        remotePosition: Duration,
        baseline: AcknowledgedPause,
        source: FreshnessEvidenceSource,
    ): ResumeFreshnessDecision {
        val distance = (remotePosition - baseline.position).absoluteValue
        return if (distance > MATERIAL_REMOTE_MOVE) {
            ResumeFreshnessDecision.Adopt(remotePosition, source)
        } else {
            ResumeFreshnessDecision.Current(source)
        }
    }
}
