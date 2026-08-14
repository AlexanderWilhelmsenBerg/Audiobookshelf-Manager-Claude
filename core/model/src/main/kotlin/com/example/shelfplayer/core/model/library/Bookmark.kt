package com.example.shelfplayer.core.model.library

import com.example.shelfplayer.core.model.LibraryItemId
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * PRODUCT_SPEC 11.1 / section 8 item 4 — a position in a book the listener wanted to keep, with a note.
 *
 * ### It has no identifier, and that is the server's design
 *
 * `bookmark-create.json` and `me-with-bookmark.json` (Audiobookshelf 2.36.0, captured 2026-08-13) return
 * `{createdAt, libraryItemId, time, title}` and nothing else. The delete route is
 * `DELETE /api/me/item/{id}/bookmark/{seconds}` — **the position is the key**. So a bookmark is identified
 * by its book and its second, everywhere, and this type has no `id` field because there is nothing to put
 * in one.
 *
 * Two consequences that the rest of the app has to respect rather than work around:
 *
 *  - **[at] is whole seconds.** A listener bookmarking at 4:03:12.640 gets 4:03:12, because that is the
 *    only thing the server can be asked to delete later. [roundedFrom] is the one place that rounding
 *    happens, so it cannot be done differently in two places.
 *  - **Two bookmarks in the same second are one bookmark.** The server overwrites. The app must say so
 *    rather than appearing to keep both and losing one on the next refresh.
 *
 * ### Where they live
 *
 * On the *user*, not on the item: `GET /api/me` carries one flat `bookmarks` array across every book. So
 * a book's bookmarks are a filter, and a full refresh of every book's bookmarks arrives with a call the
 * app already makes.
 *
 * @property title the listener's own words. Never logged — PRODUCT_SPEC 14.5, and a note about a book is
 *   as private as the book's title.
 * @property createdAt as the server stamped it, so an ordering by recency is the server's ordering.
 */
data class Bookmark(val bookId: LibraryItemId, val at: Duration, val title: String, val createdAt: Instant) {
    companion object {
        /**
         * The position a bookmark would take if made *now*, at [position].
         *
         * Truncated to the second rather than rounded to the nearest: a listener presses the button
         * having just heard something, and the second they are in is the second they mean. Rounding up
         * could land after the line they wanted to keep.
         *
         * Negative positions — a player reporting `-1` for "unset" — become zero rather than a bookmark
         * before the start of the book.
         */
        fun roundedFrom(position: Duration): Duration = position.inWholeSeconds.coerceAtLeast(0).seconds
    }
}
