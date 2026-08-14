package com.example.shelfplayer.domain.repository

import com.example.shelfplayer.core.model.AppResult
import com.example.shelfplayer.core.model.LibraryItemId
import com.example.shelfplayer.core.model.ProfileId
import com.example.shelfplayer.core.model.auth.AccountBookmark
import com.example.shelfplayer.core.model.library.Bookmark
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * PRODUCT_SPEC 11.1 / section 8 item 4 — the positions a listener wanted to keep.
 *
 * ### A bookmark is its position
 *
 * The server has no bookmark id: it keys them by whole seconds, and the delete route is addressed to the
 * number. So [at] identifies a bookmark in every method here, and two bookmarks in the same second cannot
 * both exist — [add] on an occupied second **replaces**. [Bookmark.roundedFrom] is the one place a player
 * position becomes a bookmark position, so the two cannot round differently.
 *
 * ### Local first, always
 *
 * Every write lands in Room before the server is called, and a server failure is **returned without being
 * undone**. A listener who bookmarked something on a train has made a decision; rolling it back because the
 * network was unavailable would be the app overruling them, which is the same rule
 * `PlaybackRepository.setFinished` follows and for the same reason (product priority 2 covers a listener's
 * notes as much as their position).
 *
 * That is what the two local flags exist for: a bookmark the server has not seen must survive a refresh,
 * and a bookmark deleted offline must not come back on one.
 */
interface BookmarkRepository {

    /** One book's bookmarks, earliest position first. Empty for a book with none. */
    fun observe(bookId: LibraryItemId): Flow<List<Bookmark>>

    /**
     * Adds a bookmark, or replaces the one already at that second.
     *
     * @param at truncated to whole seconds by [Bookmark.roundedFrom] before it reaches here; passing a
     *   finer position stores something the server can never be asked to delete.
     */
    suspend fun add(bookId: LibraryItemId, at: Duration, title: String): AppResult<Unit>

    /** Renames the bookmark at [at]. The position does not move — that would be a different bookmark. */
    suspend fun rename(bookId: LibraryItemId, at: Duration, title: String): AppResult<Unit>

    suspend fun remove(bookId: LibraryItemId, at: Duration): AppResult<Unit>

    /**
     * PRODUCT_SPEC 11.1 — the server's whole set, as `GET /api/me` reports it.
     *
     * Called by `SyncAccountUseCase` alongside the positions, because both ride on one response. Returns
     * the number of bookmarks stored.
     *
     * It replaces this profile's cache rather than merging into it, which is the correct treatment of an
     * authoritative full set — with the two exceptions the flags name. A bookmark deleted on another device
     * *should* disappear here, and a merge would keep it forever.
     */
    suspend fun writeAccountBookmarks(profileId: ProfileId, bookmarks: List<AccountBookmark>): AppResult<Int>
}
