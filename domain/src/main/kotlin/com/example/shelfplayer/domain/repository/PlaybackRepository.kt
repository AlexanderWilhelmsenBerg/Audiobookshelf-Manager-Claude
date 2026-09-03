package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.library.PlaybackSession
import com.example.shelfplayer.core.model.playback.AcknowledgedPause
import com.example.shelfplayer.core.model.playback.ExternalSessionCheck
import kotlin.time.Duration

/**
 * PRODUCT_SPEC PLAY-001 / PLAY-004 — opening a session, and not losing the position it produces.
 *
 * Separate from [LibraryRepository] rather than added to it. The library repository answers "what does
 * this profile have"; this one answers "what is playing and where has it got to", and those have
 * different lifetimes — the second is written every few seconds while a book plays and must keep
 * working when the first cannot reach the server at all.
 */
interface PlaybackRepository {
    /**
     * PRODUCT_SPEC PLAY-001 — asks the server to open a session for [bookId], as the active profile.
     *
     * The profile is resolved here rather than passed in, unlike every read on [LibraryRepository]. The
     * caller is a media service reacting to a transport control, and it has no profile to pass: a media
     * button arrives with no idea who is signed in. Resolving it in one place is also what keeps a
     * session from being opened for a profile that has since been switched away from (PRODUCT_SPEC 5.2).
     */
    suspend fun openSession(bookId: LibraryItemId): AppResult<PlaybackSession>

    /**
     * PRODUCT_SPEC PLAY-004 — records where the listener has got to, locally.
     *
     * **Local only, and unsynced.** The row is written with `hasUnsyncedChanges = true`, which is what
     * stops the next account sync from overwriting it with the server's older position
     * (`DefaultLibraryRepository.writeProgress` declines to touch such a row). Sending it back to the
     * server is wave 3's session outbox; until that exists the position survives a process death and
     * reaches the server the next time the app writes progress by the route Phase 1 already built.
     *
     * ### Whether the book is finished is decided here, not by the caller
     *
     * Until PR 2 of the Phase 2 closeout this took an `isFinished` flag, and the media service computed it
     * from a hard-coded thirty seconds. That put the rule in the one place that could not see either half of
     * it: ADR-0013's threshold is a *setting*, and the book's library gets a say through
     * `markAsFinishedTimeRemaining`. The implementation already resolves the profile and the book on every
     * write, so it is the only place that can resolve both halves — and one place that knows the rule cannot
     * disagree with itself.
     *
     * A book already finished stays finished: this call moves a position, and un-finishing is a decision the
     * user makes through [setFinished], not a side effect of the last few seconds playing again.
     *
     * ### Whose row it is, is the caller's to say
     *
     * [owner] closes PRODUCT_SPEC 6.5's race. The journal writes every five seconds and a profile switch takes
     * microseconds, so a write that resolved "the active profile" *here* could start under one account and
     * finish under another — landing one listener's position on the other's row, which is product priority 4.
     * The player therefore names the account whose session produced the loaded book, read from the item's own
     * extras (`MediaItems.ownerOf`), and this call honours it even when that account is no longer active.
     *
     * `null` falls back to the active profile, which is what every caller did before the parameter existed. It
     * is for a caller that genuinely has no session — not a shortcut for one that could not be bothered.
     *
     * @param position on the **global** book timeline, not a position within a file.
     * @param owner the profile the write belongs to, or `null` to use whichever is active.
     */
    suspend fun recordPosition(
        bookId: LibraryItemId,
        position: Duration,
        duration: Duration,
        owner: ProfileId? = null,
    ): AppResult<Unit>

    /**
     * PRODUCT_SPEC PLAY-004 — "marking finished is explicit", and so is un-marking it.
     *
     * The escape hatch [recordPosition] deliberately does not provide. A device run found a book marked
     * finished by the threshold with **no way to undo it**: every write path or-ed the flag, so the book
     * was finished for good and its progress could not be shown again. A destructive-feeling state with no
     * way out is exactly what product priority 5 is about.
     *
     * Writes locally first and then tells the server, in that order, for the reason every write in this app
     * does: the local row is what the UI reads, and a network failure must not lose the user's decision.
     *
     * @param position where to leave the listener. Marking finished sends the end of the book; un-marking
     *   sends where they actually are, so a book coming back from finished does not also come back from the
     *   beginning.
     */
    suspend fun setFinished(bookId: LibraryItemId, isFinished: Boolean, position: Duration): AppResult<Unit>

    /**
     * PRODUCT_SPEC SYNC-002 — asks the server whether another device has moved this book on, before an
     * in-app Play resumes it.
     *
     * ### One request, capped, and never on the account's whole history
     *
     * `GET /api/me/progress/{bookId}`, one round trip. The first version of this read
     * `/api/me/listening-sessions` page by page to exhaustion — the route is account-wide with no per-book
     * form — which made a Play on a busy account wait for several round trips before any audio. Reported
     * from a device as *"pressing play takes very long time"*.
     *
     * The read is also **capped**: an answer that has not arrived within a couple of seconds is treated as
     * [ExternalSessionCheck.Unavailable] and playback proceeds. Both reference clients do the same, and
     * `docs/api-compatibility.md` records where each of them landed.
     *
     * ### What decides
     *
     * [baseline] and nothing else. It is a position this device paused at *and the server confirmed it had
     * taken*, so a server that now reports something different can only have been moved by somebody else
     * since — which is the one form of this question that is answerable without comparing two clocks.
     *
     * Position **magnitude** never decides: an intentional rewind on another client is newer activity even
     * though its number is smaller.
     *
     * Four earlier versions decided on something else and each produced a device run where Play resumed on
     * a stale position: the server's `lastUpdate` against a row this app had itself written from that same
     * value (R-88), how long ago the listener pressed pause (R-89), and the persistence flag
     * `hasUnsyncedChanges` (R-92, R-93). None of them is read here — see R-95.
     *
     * @param baseline the last acknowledged pause for this book, or `null` when there is not one. `null`
     *   makes **no request** and returns [ExternalSessionCheck.Unavailable]: without an agreed reference
     *   point the server's answer cannot be told from our own stale write, so nothing may be moved
     *   (product priority 2). A cold start is the ordinary `null` case and needs no check — its position
     *   came from the server's own `/play` response.
     */
    suspend fun checkServerPosition(bookId: LibraryItemId, baseline: AcknowledgedPause?): ExternalSessionCheck
}
