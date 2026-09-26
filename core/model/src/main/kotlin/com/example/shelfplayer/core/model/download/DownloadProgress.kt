package com.example.shelfplayer.core.model.download

import kotlin.math.roundToInt

/**
 * BW-DL-08 / #29 — one narrow transient transfer snapshot shared by the downloader, WorkManager, UI and
 * notifications. This is deliberately not durable manifest state.
 *
 * [downloadedBytes] is physical byte truth known so far. [totalBytes] is present only when every file has a
 * trustworthy expected length. [fraction] may still be available when [totalBytes] is unknown because the
 * downloader has a bounded per-file weighting fallback; callers must not present estimated bytes as exact.
 */
data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val fraction: Float,
) {
    val percent: Int
        get() = (fraction.coerceIn(0f, 1f) * PERCENT).roundToInt().coerceIn(0, PERCENT)

    companion object {
        private const val PERCENT = 100
    }
}
