package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
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
internal data class RealtimeResumeCandidate(
    val evidence: RealtimeProgressEvidence,
    val baselineGeneration: Long,
)

internal enum class FreshnessEvidenceSource {
    Realtime,
    Rest,
    LocalUnverified,
}

internal sealed interface ResumeFreshnessDecision {
    val source: FreshnessEvidenceSource

    /** Resume where this device is. [source] says whether that position was verified or merely tolerated. */
    data class Current(override val source: FreshnessEvidenceSource) : ResumeFreshnessDecision

    /** Adopt another session's materially different position before any audio starts. */
    data class Adopt(val position: Duration, override val source: FreshnessEvidenceSource) : ResumeFreshnessDecision
}

/**
 * Issue #91 — the shared product rule for a realtime candidate.
 *
 * It deliberately returns `null` when realtime cannot prove the answer. `null` means **ask REST**, not
 * "resume locally": Socket.IO events are not replayed after disconnect/background/process death, so absence
 * is never evidence that the server stayed put.
 *
 * The material-move threshold is two minutes, matching the device-control product decision: tiny drift or
 * another client landing within two minutes of the acknowledged pause should not make a headset Play jump.
 * Magnitude is never compared, only absolute distance — an intentional remote rewind is legitimate state.
 */
internal object ResumeFreshnessPolicy {
    val MATERIAL_REMOTE_MOVE: Duration = 2.minutes

    fun realtime(
        loadedProfile: ProfileId,
        loadedBook: LibraryItemId,
        loadedSessionId: String?,
        baseline: AcknowledgedPause,
        candidate: RealtimeResumeCandidate?,
    ): ResumeFreshnessDecision? {
        val evidence = candidate?.evidence ?: return null
        if (baseline.bookId != loadedBook || candidate.baselineGeneration != baseline.generation) return null
        if (evidence.profileId != loadedProfile || evidence.progress.bookId != loadedBook) return null
        // The server echoes BookWave's own /session/{id}/sync through the same event. That is confirmation
        // of our write, not evidence that another session moved the book.
        if (loadedSessionId != null && evidence.sessionId == loadedSessionId) return null
        val distance = (evidence.progress.position - baseline.position).absoluteValue
        return if (distance > MATERIAL_REMOTE_MOVE) {
            ResumeFreshnessDecision.Adopt(evidence.progress.position, FreshnessEvidenceSource.Realtime)
        } else {
            ResumeFreshnessDecision.Current(FreshnessEvidenceSource.Realtime)
        }
    }
}
