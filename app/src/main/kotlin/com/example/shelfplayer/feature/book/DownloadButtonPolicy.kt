package com.example.shelfplayer.feature.book

import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.download.DownloadProgress
import com.example.shelfplayer.core.model.download.OfflineBook
import com.example.shelfplayer.core.model.download.durableDownloadProgress
import com.example.shelfplayer.domain.download.DownloadExecutionSnapshot
import com.example.shelfplayer.domain.download.DownloadRecoveryPolicy
import com.example.shelfplayer.domain.download.DownloadRecoveryState
import com.example.shelfplayer.domain.download.isInFlight

/**
 * PRODUCT_SPEC DL-001 / PD-003 / PD-004 — what the Book screen's download button shows.
 *
 * Pure, so every row of its table is a plain unit test. The ordering is the *user's* mental model rather
 * than the manifest's:
 *
 *  1. No manifest or no profile: nothing here.
 *  2. A copy this profile does not claim is [DownloadButtonState.OnDevice] only when it is complete **and**
 *     some profile claims it. A complete copy with no claim left (a removed profile's orphan) is not
 *     "downloaded for another profile", and an incomplete copy is another profile's business. Tapping
 *     OnDevice adds only this profile's claim.
 *  3. A book that is here is here, even if the last thing recorded was a failure: `Complete` wins.
 *  4. Otherwise the durable manifest is refined by [execution] evidence through the same
 *     [DownloadRecoveryPolicy] the Downloads rows use. A durable `Paused` beats stale evidence, and a
 *     manifest that says `Failed` while WorkManager is backing off to retry is truthfully *downloading*.
 *
 * In-flight progress uses the live WorkManager snapshot; stopped states use the durable checkpoint.
 * The percent is the in-flight one, which never reads 100 before the Downloaded state takes over.
 */
internal fun downloadButtonStateOf(
    offline: OfflineBook?,
    profileId: ProfileId?,
    execution: DownloadExecutionSnapshot?,
): DownloadButtonState = when {
    offline == null || profileId == null -> DownloadButtonState.NotDownloaded

    profileId !in offline.requestedBy ->
        if (offline.isComplete && offline.requestedBy.isNotEmpty()) {
            DownloadButtonState.OnDevice
        } else {
            DownloadButtonState.NotDownloaded
        }

    offline.isComplete -> DownloadButtonState.Downloaded

    else -> activeStateOf(offline, execution)
}

private fun activeStateOf(offline: OfflineBook, execution: DownloadExecutionSnapshot?): DownloadButtonState {
    val recovery = DownloadRecoveryPolicy.resolve(
        durableState = offline.state,
        manifestFilesComplete = offline.isComplete,
        safeFailureSummary = offline.failureSummary,
        executionEvidence = execution?.evidence,
    ).state
    // A stopped worker's last progress can precede the final cancellation checkpoint.
    val progress = if (recovery.isInFlight) {
        execution?.progress ?: offline.durableDownloadProgress()
    } else {
        offline.durableDownloadProgress()
    }
    return when {
        recovery == DownloadRecoveryState.Paused ->
            DownloadButtonState.Paused(progress = progress.fractionOrNull(), percent = progress.inFlightPercent)

        recovery == DownloadRecoveryState.Failed -> DownloadButtonState.Failed

        recovery == DownloadRecoveryState.Complete -> DownloadButtonState.Downloaded

        else -> DownloadButtonState.Downloading(
            progress = progress.fractionOrNull(),
            percent = progress.inFlightPercent,
            phase = phaseOf(recovery),
        )
    }
}

/** Only meaningful for an in-flight [DownloadRecoveryState]; anything else reads as plain transfer. */
private fun phaseOf(recovery: DownloadRecoveryState): DownloadPhase = when {
    !recovery.isInFlight -> DownloadPhase.Transferring
    recovery == DownloadRecoveryState.Queued -> DownloadPhase.Queued
    recovery == DownloadRecoveryState.Waiting -> DownloadPhase.Waiting
    recovery == DownloadRecoveryState.Retrying -> DownloadPhase.Retrying
    else -> DownloadPhase.Transferring
}

/**
 * The fraction downloaded, or `null` before the first byte.
 *
 * `null` shows an indeterminate ring. A determinate one frozen at zero looks exactly like a download that
 * never started, and that is precisely the moment a user is deciding whether the button worked.
 */
private fun DownloadProgress.fractionOrNull(): Float? =
    fraction.takeIf { downloadedBytes > 0L && it > 0f }?.coerceIn(0f, 1f)

/** What a tap on the download button is for. One answer for the screen and the ViewModel. */
internal enum class DownloadTap {
    /** Start, join, retry or resume through `DownloadBookUseCase`. Nothing is lost by it, so it never asks. */
    Start,

    /** In flight: ask Pause / Stop / Keep downloading. A tap never pauses or stops on its own. */
    AskPauseOrStop,

    /** Downloaded: ask before deleting files. */
    AskRemove,

    /** The first tap's request is still running; a second would only repeat it. */
    Ignore,
}

internal fun DownloadButtonState.tap(): DownloadTap = when (this) {
    is DownloadButtonState.NotDownloaded,
    is DownloadButtonState.OnDevice,
    is DownloadButtonState.Failed,
    is DownloadButtonState.Paused,
    -> DownloadTap.Start

    is DownloadButtonState.Downloading -> DownloadTap.AskPauseOrStop

    is DownloadButtonState.Downloaded -> DownloadTap.AskRemove

    is DownloadButtonState.Starting -> DownloadTap.Ignore
}
