package com.example.shelfplayer.core.model.library

import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.ServerId
import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 11.3 — one audio file inside a multi-file audiobook.
 *
 * [startOffset] is the track's start on the global book timeline. Phase 2 resolves a global seek to
 * `(track, localOffset)` using this value; Phase 0 stores it so the offline manifest and the player
 * agree from the start rather than being retrofitted.
 */
data class AudioTrack(
    val serverId: ServerId,
    val bookId: LibraryItemId,
    val index: Int,
    val remoteFileId: String,
    val startOffset: Duration,
    val duration: Duration,
    val mimeType: String?,
    val sizeBytes: Long,
    val isExcluded: Boolean,
)

/** PRODUCT_SPEC PLAY-003 — chapters use the global book timeline, not file boundaries. */
data class Chapter(
    val serverId: ServerId,
    val bookId: LibraryItemId,
    val index: Int,
    val title: String,
    val start: Duration,
    val end: Duration,
)

/**
 * PRODUCT_SPEC PLAY-004 — a profile's position in a book.
 *
 * [isFinished] is stored rather than derived, because the finished threshold is configurable and
 * "the user explicitly marked this finished" must survive a threshold change.
 */
data class MediaProgress(
    val serverId: ServerId,
    val profileId: ProfileId,
    val bookId: LibraryItemId,
    val position: Duration,
    val duration: Duration,
    val isFinished: Boolean,
    val updatedAt: Instant,
    val hasUnsyncedChanges: Boolean,
) {
    /** Fraction in `0.0..1.0`; `0.0` when the duration is unknown rather than a division by zero. */
    val fractionComplete: Float
        get() = when {
            duration.inWholeMilliseconds <= 0L -> 0f
            else -> (position.inWholeMilliseconds.toDouble() / duration.inWholeMilliseconds)
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
}
