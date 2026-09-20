package com.example.shelfplayer.playback

import com.example.shelfplayer.core.model.library.Author
import com.example.shelfplayer.core.model.library.Book
import com.example.shelfplayer.core.model.library.SeriesMembership
import com.example.shelfplayer.domain.library.booksInSeriesOrder
import java.time.Instant

/**
 * PD-001 / LIB-002 / LIB-003 — the grouped facts Android Auto renders from one profile-bound book snapshot.
 *
 * Kept independent of Media3 so ordering and representative-artwork selection can be pinned on the JVM and
 * reused by #10's browse-shape fingerprint. Rendering and invalidation must never derive Series in two
 * different orders.
 */
internal data class AutoSeriesNode(
    val membership: SeriesMembership,
    val books: List<Book>,
    val lastActivity: Instant?,
) {
    /** First canonical-series book that has artwork at all. The artwork bridge decides whether bytes exist. */
    val representativeCover: Book?
        get() = books.firstOrNull { book -> book.coverPath != null }
}

internal data class AutoAuthorNode(val author: Author, val books: List<Book>) {
    /** LIB-002 fallback when no confirmed/cached author portrait is usable. */
    val representativeCover: Book?
        get() = books.firstOrNull { book -> book.coverPath != null }
}

/**
 * PD-001 — played Series first by newest progress activity, then unplayed Series alphabetically.
 *
 * [Book.progress] is the profile-scoped Room projection for both accepted server progress and local unsynced
 * progress. Its [com.example.shelfplayer.core.model.library.MediaProgress.updatedAt] therefore already is the
 * recency evidence this surface needs; no second history/recency store is introduced.
 */
internal fun autoSeriesNodes(books: List<Book>): List<AutoSeriesNode> = books
    .flatMap(Book::seriesMemberships)
    .distinctBy { membership -> membership.series.id }
    .map { membership ->
        val ordered = booksInSeriesOrder(books, membership)
        AutoSeriesNode(
            membership = membership,
            books = ordered,
            lastActivity = ordered.mapNotNull { book -> book.progress?.updatedAt }.maxOrNull(),
        )
    }
    .sortedWith(
        compareByDescending<AutoSeriesNode> { node -> node.lastActivity != null }
            .thenByDescending { node -> node.lastActivity ?: Instant.MIN }
            .thenBy { node -> node.membership.series.name.lowercase() }
            .thenBy { node -> node.membership.series.id.value },
    )

/** LIB-002 — Authors remain alphabetical and deterministic; books below them remain title/id ordered. */
internal fun autoAuthorNodes(books: List<Book>): List<AutoAuthorNode> = books
    .flatMap(Book::authors)
    .distinctBy(Author::id)
    .sortedWith(compareBy<Author>({ author -> author.name.lowercase() }, { author -> author.id.value }))
    .map { author ->
        AutoAuthorNode(
            author = author,
            books = books
                .filter { book -> book.authors.any { candidate -> candidate.id == author.id } }
                .sortedWith(compareBy<Book>({ book -> book.title.lowercase() }, { book -> book.id.value })),
        )
    }
