package com.example.shelfplayer.garmin

import javax.inject.Inject
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class GarminSnapshotSendPolicy @Inject constructor() {
    private var lastSent: SentSnapshot? = null

    fun reset() {
        lastSent = null
    }

    fun shouldSend(
        candidate: GarminPlaybackSnapshot,
        elapsed: Duration,
        force: Boolean = false,
    ): Boolean {
        if (force) return true
        val previous = lastSent ?: return true

        if (candidate.profileId != previous.snapshot.profileId ||
            candidate.bookId != previous.snapshot.bookId ||
            candidate.title != previous.snapshot.title ||
            candidate.author != previous.snapshot.author ||
            candidate.chapterTitle != previous.snapshot.chapterTitle ||
            candidate.playing != previous.snapshot.playing ||
            candidate.durationMs != previous.snapshot.durationMs
        ) {
            return true
        }

        val elapsedMs = (elapsed - previous.elapsed).inWholeMilliseconds.coerceAtLeast(0L)
        val expectedPosition = previous.snapshot.positionMs +
            if (previous.snapshot.playing) elapsedMs else 0L
        if (abs(candidate.positionMs - expectedPosition) >= SEEK_DEVIATION_MS) {
            return true
        }

        return candidate.playing && elapsed - previous.elapsed >= RECONCILIATION_INTERVAL
    }

    fun markSent(
        snapshot: GarminPlaybackSnapshot,
        elapsed: Duration,
    ) {
        lastSent = SentSnapshot(snapshot, elapsed)
    }

    private data class SentSnapshot(
        val snapshot: GarminPlaybackSnapshot,
        val elapsed: Duration,
    )

    private companion object {
        const val SEEK_DEVIATION_MS = 1_500L
        val RECONCILIATION_INTERVAL = 30.seconds
    }
}
