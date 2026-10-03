package com.example.shelfplayer.core.model.download

import kotlin.math.floor

/**
 * BW-DL-08 / #29 — one narrow transient transfer snapshot shared by the downloader, WorkManager, UI and
 * notifications. This is deliberately not durable manifest state.
 *
 * [downloadedBytes] is physical byte truth known so far. [totalBytes] is present only when every file has a
 * trustworthy expected length. [fraction] may still be available when [totalBytes] is unknown because the
 * downloader has a bounded per-file weighting fallback; callers must not present estimated bytes as exact.
 */
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long?, val fraction: Float) {
    /**
     * Whole percent, floored so 99.9% never reads as done. The epsilon only absorbs float error such as
     * `0.42f * 100 = 41.99998`; it is far smaller than a displayable step.
     */
    val percent: Int
        get() = floor(fraction.coerceIn(0f, 1f) * PERCENT + PERCENT_EPSILON).toInt().coerceIn(0, PERCENT)

    /** Never 100 while a transfer is still in flight; completion is shown by the Downloaded state instead. */
    val inFlightPercent: Int
        get() = percent.coerceAtMost(PERCENT - 1)

    companion object {
        private const val PERCENT = 100
        private const val PERCENT_EPSILON = 1e-3f
    }
}

/**
 * Reconstructs the best truthful transfer progress available from the durable manifest after process death
 * or WorkManager progress pruning. Exact total bytes are withheld unless every file length is known.
 */
fun OfflineBook.durableDownloadProgress(): DownloadProgress {
    val truthfulTotal = files
        .map { it.expectedBytes }
        .takeIf { expected -> expected.all { (it ?: 0L) > 0L } }
        ?.sumOf { it ?: 0L }
        ?.takeIf { it > 0L }
    val fraction = when {
        isComplete -> 1f
        truthfulTotal != null -> (downloadedBytes.toFloat() / truthfulTotal).coerceIn(0f, 1f)
        else -> 0f
    }
    return DownloadProgress(
        downloadedBytes = downloadedBytes,
        totalBytes = truthfulTotal,
        fraction = fraction,
    )
}
