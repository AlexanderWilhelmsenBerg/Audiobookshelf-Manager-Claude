package com.example.shelfplayer.core.model.playback

import com.example.shelfplayer.core.model.LibraryItemId
import java.time.Instant
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-003 / PLAY-004 / 14.5 — one listening session, as the **server** recorded it.
 *
 * ### Why this exists when the app already derives remote history
 *
 * The history pane's remote rows were *reconstructed*, in `LibrarySnapshotWriter.recordRemoteChange`, by
 * diffing a book's stored progress against the progress a sync had just fetched. That works and it has two
 * holes it cannot close from where it stands:
 *
 *  - **It needs a previous row.** `recordRemoteChange` returns early when there is nothing to diff against,
 *    so a book listened to somewhere else that this device has never played produces no history at all.
 *  - **It only sees the endpoints.** Two sessions between one sync and the next collapse into a single row,
 *    and a session that both started and finished inside that window is invisible.
 *
 * `GET /api/me/listening-sessions` is the server's own record instead of a reconstruction: one entry per
 * session, with when it started, how much was actually listened, and on what device.
 *
 * ### The units, which the capture settled rather than the reading
 *
 * [listened], [startedFrom] and [reachedAt] are positions and durations; [startedAt] and [updatedAt] are
 * moments. The wire response carries the first three in **seconds** and the last two in **epoch
 * milliseconds**, which is the pair a reader guesses wrong — `docs/api-compatibility.md` and
 * `ListeningSessionContractTest` pin it.
 *
 * ### What is deliberately absent
 *
 * No IP address. The wire response carries one under `deviceInfo`; nothing in this app models it, so it
 * cannot be stored or logged by accident (14.5). [deviceName] and [clientName] are private self-hosted data
 * — shown to the person they belong to, never logged.
 *
 * @property id the server's own id for the session. Its identity, and what makes importing one twice
 *   idempotent — the same session fetched on two consecutive refreshes must not become two history rows.
 * @property deviceId which device recorded it, as the server knows it. The app sends a random per-install
 *   value when it opens a session (`PlaybackDeviceIdentity`), so this is what tells *this* phone's sessions
 *   apart from another's — which is the whole difference between a useful remote history and a duplicate of
 *   the local one.
 * @property listened how much was actually listened, which is not elapsed wall time: a paused session
 *   accrues none. This is the number that decides whether a session is worth showing at all.
 * @property startedFrom the position the session opened at.
 * @property reachedAt the position it got to. [startedFrom] to [reachedAt] is the span a history row
 *   describes.
 * @property updatedAt the server's last activity timestamp for the session. Unlike [startedAt], it moves
 *   when an already-open session progresses, which is what lets Play tell an old session from a genuinely
 *   newer use of the same book without comparing positions.
 */
data class ListeningSession(
    val id: String,
    val bookId: LibraryItemId,
    val deviceId: String?,
    val deviceName: String?,
    val clientName: String?,
    val listened: Duration,
    val startedFrom: Duration,
    val reachedAt: Duration,
    val startedAt: Instant,
    val updatedAt: Instant = startedAt,
)
