package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.library.PlaybackSession
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
     * @param position on the **global** book timeline, not a position within a file.
     * @param isFinished when the listener has reached the end — see ADR-0013 for what "the end" means.
     *   Never `false` for a book already finished: this call moves a position, and un-finishing a book
     *   is a decision the user makes, not a side effect of the last few seconds playing again.
     */
    suspend fun recordPosition(
        bookId: LibraryItemId,
        position: Duration,
        duration: Duration,
        isFinished: Boolean,
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
}
