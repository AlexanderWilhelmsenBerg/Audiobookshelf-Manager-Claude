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

    fun shouldSend(candidate: GarminPlaybackSnapshot, elapsed: Duration, force: Boolean = false): Boolean {
        val previous = lastSent
        if (force || previous == null) return true
        // Compare metadata without position/projection time; new model fields stay part of transition detection.
        val metadataChanged = candidate.copy(
            positionMs = previous.snapshot.positionMs,
            updatedAt = previous.snapshot.updatedAt,
        ) != previous.snapshot
        val elapsedMs = (elapsed - previous.elapsed).inWholeMilliseconds.coerceAtLeast(0L)
        val expectedPosition = previous.snapshot.positionMs +
            if (previous.snapshot.playing) (elapsedMs * previous.snapshot.speed).toLong() else 0L
        val seek = abs(candidate.positionMs - expectedPosition) >= SEEK_DEVIATION_MS
        val refresh = candidate.playing && elapsed - previous.elapsed >= RECONCILIATION_INTERVAL
        return metadataChanged || seek || refresh
    }

    fun markSent(snapshot: GarminPlaybackSnapshot, elapsed: Duration) {
        lastSent = SentSnapshot(snapshot, elapsed)
    }

    private data class SentSnapshot(val snapshot: GarminPlaybackSnapshot, val elapsed: Duration)

    private companion object {
        const val SEEK_DEVIATION_MS = 1_500L
        val RECONCILIATION_INTERVAL = 30.seconds
    }
}
