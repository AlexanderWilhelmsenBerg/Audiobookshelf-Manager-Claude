package com.example.shelfplayer.domain.download

import com.example.shelfplayer.core.model.download.DownloadState

/**
 * Transient execution evidence that an Android adapter may supply to download presentation.
 *
 * This is deliberately smaller than WorkManager's state model and contains no Android types. WorkManager
 * remains the owner of execution/backoff/constraint state; BW-DL-04 (#109) only has to translate the facts
 * presentation needs into this seam.
 */
enum class DownloadExecutionEvidence {
    /** Work exists and is eligible to start, but has not reported active transfer yet. */
    Queued,

    /** Work is actively executing. */
    Running,

    /** Work is deliberately blocked on an execution condition such as a network constraint. */
    Waiting,

    /** A previous attempt failed transiently and the execution owner intends to try again. */
    Retrying,

    /** The execution owner reports no more work because it finished. Durable state decides the outcome. */
    Finished,

    /** The execution owner reports cancelled/stale work. Durable state decides the outcome. */
    Cancelled,
}

/** The user-facing recovery state derived from durable state plus optional execution evidence. */
enum class DownloadRecoveryState {
    Complete,
    Paused,
    Queued,
    Running,
    Waiting,
    Retrying,
    Failed,
}

/**
 * Pure presentation result for a download row.
 *
 * [failureSummary] is present only for a terminal [DownloadRecoveryState.Failed] result. Its input must be
 * BookWave's already-sanitized persisted summary; raw exceptions, URLs, response bodies and paths never
 * belong at this boundary.
 */
data class DownloadRecoveryPresentation(val state: DownloadRecoveryState, val failureSummary: String? = null)

/**
 * BW-DL-02 / #107 — one owner for durable + transient download presentation precedence.
 *
 * Durable `Complete` is authoritative only while the manifest's member-file rows also say every required
 * file is complete. That second fact protects a downgraded build from treating an unknown future file state
 * as playable merely because the book row still says `Complete`. Durable `Paused` is authoritative because
 * it records explicit listener intent that must survive process death; stale/cancelled execution evidence
 * must never restart or relabel it.
 *
 * For the remaining durable states, live execution evidence may refine what the listener sees. This is the
 * seam BW-DL-04 (#109) will feed: a manifest can still say `Failed` while WorkManager is backing off for an
 * automatic retry, in which case `Retrying` is the truthful presentation. Finished/cancelled evidence is not
 * enough to infer file state, so it falls back to the durable manifest instead of inventing a second state
 * machine.
 */
object DownloadRecoveryPolicy {
    fun resolve(
        durableState: DownloadState,
        manifestFilesComplete: Boolean,
        safeFailureSummary: String?,
        executionEvidence: DownloadExecutionEvidence? = null,
    ): DownloadRecoveryPresentation {
        val state = when (durableState) {
            DownloadState.Complete -> if (manifestFilesComplete) {
                DownloadRecoveryState.Complete
            } else {
                DownloadRecoveryState.Failed
            }
            DownloadState.Paused -> DownloadRecoveryState.Paused
            DownloadState.Queued,
            DownloadState.Running,
            DownloadState.Failed,
            -> executionEvidence.toActiveRecoveryState() ?: durableState.toRecoveryState()
        }

        return DownloadRecoveryPresentation(
            state = state,
            failureSummary = safeFailureSummary.takeIf { state == DownloadRecoveryState.Failed },
        )
    }

    private fun DownloadExecutionEvidence?.toActiveRecoveryState(): DownloadRecoveryState? = when (this) {
        DownloadExecutionEvidence.Queued -> DownloadRecoveryState.Queued
        DownloadExecutionEvidence.Running -> DownloadRecoveryState.Running
        DownloadExecutionEvidence.Waiting -> DownloadRecoveryState.Waiting
        DownloadExecutionEvidence.Retrying -> DownloadRecoveryState.Retrying
        DownloadExecutionEvidence.Finished,
        DownloadExecutionEvidence.Cancelled,
        null,
        -> null
    }

    private fun DownloadState.toRecoveryState(): DownloadRecoveryState = when (this) {
        DownloadState.Queued -> DownloadRecoveryState.Queued
        DownloadState.Running -> DownloadRecoveryState.Running
        DownloadState.Complete -> DownloadRecoveryState.Complete
        DownloadState.Failed -> DownloadRecoveryState.Failed
        DownloadState.Paused -> DownloadRecoveryState.Paused
    }
}
