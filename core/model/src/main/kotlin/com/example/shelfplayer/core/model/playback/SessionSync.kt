package com.example.shelfplayer.core.model.playback

import com.example.shelfplayer.core.model.LibraryItemId
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * PRODUCT_SPEC PLAY-004 — a position to send against an open server session.
 *
 * @property position on the **global** book timeline, not a position within a file.
 * @property timeListened audio actually played since the last sync. Not the same as the change in
 *   [position]: a seek moves the position without anybody having listened to the difference, and a
 *   server counting listening time from position deltas would credit a drag across a book as an hour of
 *   reading.
 */
data class SessionProgress(val position: Duration, val duration: Duration, val timeListened: Duration)

/**
 * PRODUCT_SPEC PLAY-005 — a listening session recorded on this device, waiting to be uploaded.
 *
 * ### Why the id is ours
 *
 * `POST /api/session/{id}/sync` needs an id the *server* issued, which a session recorded on a train
 * never received. The offline route takes an id the **client** generated and treats an unseen one as new,
 * which is what makes a retry idempotent: the second attempt carries the same [id] and is recognised as
 * the same session rather than duplicated. PLAY-005's "every offline listening session has a UUIDv4
 * identifier" is this field.
 *
 * ### [updatedAt] decides conflicts, and the server trusts it
 *
 * The server applies the newer `updatedAt` and declines older progress — demonstrated by the capture,
 * which sent `0` and got `progressSynced: false` back. So this has to be the honest moment the position
 * was recorded. A device whose clock is five minutes fast wins every conflict it takes part in, which is
 * why clock skew stops being hygiene here and becomes correctness.
 */
data class OfflineSession(
    val id: String,
    val bookId: LibraryItemId,
    val title: String,
    val author: String?,
    val progress: SessionProgress,
    val startedAt: Instant,
    val updatedAt: Instant,
)

/**
 * What the server said about one uploaded session.
 *
 * Two separate answers, and conflating them is the mistake this type exists to prevent:
 *
 *  - [wasAccepted] — the session was stored. This is what an outbox drains on.
 *  - [wasProgressApplied] — the *position* was applied. A session older than what the server holds comes
 *    back accepted with the progress declined, which is PLAY-004's conflict rule working correctly. It is
 *    **not** a failure and must not be retried into.
 */
data class OfflineSessionResult(val id: String, val wasAccepted: Boolean, val wasProgressApplied: Boolean)

/**
 * PRODUCT_SPEC PLAY-004 — why a sync happened.
 *
 * Recorded rather than inferred. "The position on the server is thirty seconds stale" and "the position on
 * the server is stale because the last sync attempt failed" look identical from the outside, and the
 * trigger is what tells them apart in diagnostics. Every entry here is one of the moments PLAY-004
 * enumerates, and the enum is exhaustive on purpose: a new moment has to be named before it can sync.
 */
enum class SyncTrigger {
    /** The ~30 second cadence. */
    Interval,
    Paused,
    SeekCompleted,
    ChapterChanged,

    /**
     * A track boundary inside one book.
     *
     * Not one of the moments PLAY-004 lists, and kept separate from [ChapterChanged] rather than folded into
     * it because they are different events: a chapter can start mid-file and a file can hold several. The
     * service can see this one with no chapter list in the process; [ChapterChanged] needs the app.
     */
    TrackChanged,
    BookChanged,
    SleepTimerStopped,
    ServiceShutdown,
    AppBackgrounded,
}

/**
 * PRODUCT_SPEC PLAY-005 — how far this device's clock is from the server's, and whether that matters.
 *
 * ### Why this is measured at all
 *
 * The server resolves conflicts by taking the newer `updatedAt`, and the app sends its own. A device five
 * minutes fast therefore wins every conflict it takes part in — including against a position the listener
 * set later on another device. The app cannot fix that by lying about `updatedAt` (that is the same
 * defect pointing the other way); what it can do is *know*, and say so.
 *
 * ### The source
 *
 * The `Date` header on any response from the server. That is HTTP's own field (RFC 9110 §6.6.1), not an
 * Audiobookshelf behaviour, so reading it does not need a captured fixture (PRODUCT_SPEC 22.4).
 *
 * @property offset server time minus device time. Positive means this device is **behind** the server.
 * @property measuredAt when the reading was taken, so a stale one can be shown as stale.
 */
data class ClockSkew(val offset: Duration, val measuredAt: Instant) {
    /** PRODUCT_SPEC PLAY-005 — "clock-skew greater than five minutes is detected and shown". */
    val isSignificant: Boolean get() = offset.absoluteValue > Threshold

    companion object {
        val Threshold: Duration = 5.minutes
    }
}

/**
 * PRODUCT_SPEC SET-002 (Privacy/diagnostics) — the outbox and the sync cadence, as numbers.
 *
 * Counts and timestamps only. Which book is queued is not here for the reason
 * [com.example.shelfplayer.core.model.StorageDiagnostics] gives: a screen that proves progress is queued
 * does not need to name the books to do it (PRODUCT_SPEC 14.5).
 *
 * @property sessionsRecorded every session row this profile has, queued or already uploaded.
 * @property sessionsPending rows the server has not accepted yet. This is what an outbox drains.
 * @property sessionsOpen rows for a session still being listened to — pending, and not yet final.
 * @property sessionsSynced rows the server accepted, kept for the seven days PLAY-005 asks for.
 * @property progressDeclined accepted sessions whose *position* the server declined as older than its
 *   own. Not a failure — PLAY-004's conflict rule working — and shown separately so it cannot read as one.
 * @property lastSyncedAt the last time the server accepted a position from this device.
 * @property lastTrigger why that sync ran.
 * @property lastFailureCode the error code of the most recent failed attempt, or `null` if the last
 *   attempt succeeded. A code, never a message: a transport message can carry a host.
 * @property clockSkew the most recent reading, or `null` if the app has not talked to the server yet.
 */
data class SessionSyncDiagnostics(
    val sessionsRecorded: Int = 0,
    val sessionsPending: Int = 0,
    val sessionsOpen: Int = 0,
    val sessionsSynced: Int = 0,
    val progressDeclined: Int = 0,
    val lastSyncedAt: Instant? = null,
    val lastTrigger: SyncTrigger? = null,
    val lastFailureCode: String? = null,
    val clockSkew: ClockSkew? = null,
)
