package com.example.shelfplayer.core.model.library

/**
 * Everything a gateway returns about one book in a single unit.
 *
 * PRODUCT_SPEC 2.3 — "offline means genuinely offline": a book is only useful offline when its
 * tracks, chapters and metadata were stored together. Returning them as one value means a repository
 * cannot accidentally persist a book without the track offsets the player needs to resume it.
 */
data class BookSnapshot(val book: Book, val tracks: List<AudioTrack>, val chapters: List<Chapter>)

/**
 * PRODUCT_SPEC LIB-001 — "failed optional sections do not fail the whole sync".
 *
 * One library's worth of books, plus how much of it is missing and *why* — because the two reasons a book
 * is absent lead to opposite conclusions:
 *
 * - **Removed.** The server answered `404`: the item is gone, and the cached copy should be soft-deleted.
 * - **Unreachable.** The fetch failed — a timeout, a `500`, a response the mapper could not read. This says
 *   nothing about whether the item still exists, so deleting the cached copy would be destroying data on
 *   the strength of a dropped connection.
 *
 * Reporting a book count is not enough to tell those apart, which is why this type exists rather than a
 * bare `List<BookSnapshot>`. A device run showed why it matters: syncing a 490-book library is 490 requests,
 * one of them failed, and the whole sync was discarded — the user saw an empty library and a suggestion to
 * try again.
 */
data class LibrarySnapshot(val books: List<BookSnapshot>, val removedCount: Int = 0, val unreachableCount: Int = 0) {
    /**
     * Whether every item the server listed was accounted for.
     *
     * Only a complete fetch may drive deletions. An incomplete one is still worth writing — the books it
     * did fetch are real and current — but it must not be read as "everything else is gone".
     */
    val isComplete: Boolean get() = unreachableCount == 0
}
