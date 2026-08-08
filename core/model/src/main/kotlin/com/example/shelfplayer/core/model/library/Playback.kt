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

/**
 * PRODUCT_SPEC PLAY-001 / 14.5 — one track of an open session, addressed by an absolute URL.
 *
 * The server sends `contentUrl` as a **server-relative path carrying no credential**
 * (`/api/items/{itemId}/file/{ino}`, observed in `item-play.json`). The gateway resolves it against the
 * profile's base URL before this type exists, for two reasons:
 *
 *  - a relative path is only meaningful next to the profile that produced it, and a type that carried
 *    one would invite a caller to resolve it against whichever server was on screen (PRODUCT_SPEC 5.2);
 *  - the *whole* path is kept rather than rebuilt from its last segment, so nothing here depends on the
 *    route's shape staying what it is today (PRODUCT_SPEC 22.4).
 *
 * [url] therefore never contains a token. It is fetched with an `Authorization` header by the app's
 * authenticated client, which is what PRODUCT_SPEC 14.5 requires and what the server's design already
 * makes natural.
 *
 * @property startOffset this track's start on the **global book timeline**, as the server reports it.
 *   `multi-item-play.json` settled that it is global rather than per-file, so a position is never
 *   derived by summing durations — a sum drifts by rounding on every track.
 */
data class PlayableTrack(
    val index: Int,
    val url: String,
    val startOffset: Duration,
    val duration: Duration,
    val mimeType: String?,
    val isExcluded: Boolean,
)

/**
 * PRODUCT_SPEC PLAY-001 — a playback session the server opened, and what it takes to play it.
 *
 * Returned by `POST /api/items/{id}/play`. Captured as `item-play.json` and `multi-item-play.json`;
 * `docs/api-compatibility.md` records what those settled, and two of the facts shape this type:
 *
 *  - **[id] is the server's**, and it is what `POST /api/session/{id}/sync` and `/close` are addressed
 *    to. It is *not* the identifier an offline session carries — PLAY-005's outbox generates its own,
 *    because a session recorded with no network never reached the server to be given one.
 *  - **[startAt] is where the server says to resume**, seeded from stored progress rather than zero.
 *
 * @property chapters on the global book timeline, so a chapter that begins mid-file reports the time in
 *   the book rather than the time in the file.
 */
data class PlaybackSession(
    val id: String,
    val bookId: LibraryItemId,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val startAt: Duration,
    val duration: Duration,
    val tracks: List<PlayableTrack>,
    val chapters: List<Chapter>,
) {
    /**
     * The tracks that are actually played, in order.
     *
     * PRODUCT_SPEC PLAY-003 — "excluded server tracks are not played". Filtering here rather than at
     * each use is what stops one caller honouring the flag and another forgetting it.
     */
    val playableTracks: List<PlayableTrack> get() = tracks.filterNot { it.isExcluded }.sortedBy { it.index }
}
